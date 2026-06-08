(ns headhunter.app
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]))

;; =============================================================================
;; Core State & LocalStorage Helpers
;; =============================================================================

(defn get-storage-item [key default-val]
  (let [val (.getItem js/localStorage key)]
    (if (nil? val)
      default-val
      (try
        (js->clj (js/JSON.parse val) :keywordize-keys true)
        (catch js/Error _
          val)))))

(defn set-storage-item! [key val]
  (.setItem js/localStorage key (js/JSON.stringify (clj->js val))))

;; Default profile configuration
(def default-profile
  {:name "Alex Tan"
   :email "alex.tan@example.com"
   :phone "+65 9123 4567"
   :location "Singapore"
   :linkedin "linkedin.com/in/alextan"
   :monthly-target "S$8,500"
   :aws-13th true
   :residency "Singapore Citizen"})

;; Global reactive application state
(defonce state
  (r/atom
   {:api-key (get-storage-item "hh_api_key" "")
    :cv-content (get-storage-item "hh_cv" "")
    :profile (get-storage-item "hh_profile" default-profile)
    :current-jd ""
    :loading? false
    :loading-msg ""
    :evaluation nil
    :company-name ""
    :pdf-status :idle ; :idle, :tailoring, :compiling, :ready, :error
    :pdf-url nil
    :pdf-filename ""
    :tracker (get-storage-item "hh_tracker" [])
    :active-tab :evaluate}))

;; Telemetry helper
(defn track-event! [event-name props]
  (when (and (exists? js/posthog) (fn? (.-capture js/posthog)))
    (.capture js/posthog event-name (clj->js props))))

;; =============================================================================
;; Gemini API Integration (100% Stateless & Client-Side)
;; =============================================================================

(defn call-gemini! [api-key system-prompt user-prompt success-fn error-fn]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" api-key)
        payload {:contents [{:parts [{:text system-prompt}
                                     {:text user-prompt}]}]
                 :generationConfig {:temperature 0.5
                                    :thinkingConfig {:thinkingBudget 0}}}
        options {:method "POST"
                 :headers {"Content-Type" "application/json"}
                 :body (js/JSON.stringify (clj->js payload))}]
    (-> (js/fetch url (clj->js options))
        (.then (fn [response]
                 (if (.-ok response)
                   (.json response)
                   (.then (.text response)
                          (fn [err-text]
                            (throw (js/Error. (str "API error " (.-status response) ": " err-text))))))))
        (.then (fn [data]
                 (let [data-clj (js->clj data :keywordize-keys true)
                       text (get-in data-clj [:candidates 0 :content :parts 0 :text])]
                   (if text
                     (success-fn text)
                     (throw (js/Error. "Empty response from Gemini API."))))))
        (.catch (fn [err]
                  (error-fn (.-message err)))))))

;; =============================================================================
;; Prompts & Guidelines (Singapore Specialized Rubric)
;; =============================================================================

(def evaluation-system-prompt
  (str "You are career-ops, an AI-powered job search assistant specialized for the Singapore job market.\n"
       "Evaluate the job description (JD) against the user's CV using this strict Singaporean rubric.\n\n"
       "Scoring system (1-5):\n"
       "- Match with CV: Cite exact lines of proof.\n"
       "- North Star alignment: How well the role matches target roles.\n"
       "- Comp (SGD): Salary vs local market base (including AWS/13th month and CPF contributions).\n"
       "- Cultural/Remote: In SG, hybrid (2-3 days remote) is the norm and scores 5.0. 100% on-site scores 3.0.\n"
       "- Red Flags: Warnings.\n\n"
       "Posting Legitimacy (Block G):\n"
       "- Check if listed on MyCareersFuture (MOM FCF 14-day advertising compliance ensures high confidence).\n"
       "- Flag suspicious salary/contradictions.\n\n"
       "RULE: NEVER make up or exaggerate metrics. Be factual.\n"
       "Output structure must contain a machine-readable summary at the end:\n"
       "---SCORE_SUMMARY---\n"
       "COMPANY: <company name>\n"
       "ROLE: <role title>\n"
       "SCORE: <decimal, e.g. 4.6>\n"
       "ARCHETYPE: <detected archetype>\n"
       "LEGITIMACY: <High Confidence | Proceed with Caution | Suspicious>\n"
       "---END_SUMMARY---"))

(def tailoring-system-prompt
  (str "You are a professional ATS resume-tailoring agent.\n"
       "Your task is to take the candidate's CV and tailor it for the target Job Description (JD).\n"
       "Follow these rules:\n"
       "1. Inject relevant keywords from the JD into the Professional Summary and Work Experience bullets naturally.\n"
       "2. Do NOT invent any skills, jobs, or achievements. Maintain 100% factual accuracy.\n"
       "3. Reorder experience bullet points to prioritize achievements most relevant to the JD.\n"
       "4. Output MUST be a single JSON object conforming to this schema:\n\n"
       "{\n"
       "  \"name\": \"Alex Tan\",\n"
       "  \"contact\": [\"alex.tan@example.com\", \"linkedin.com/in/alextan\", \"Singapore\"],\n"
       "  \"summary\": \"3-4 lines of professional summary tailored with keywords\",\n"
       "  \"competencies\": [\"Tag 1\", \"Tag 2\", \"Tag 3\", \"Tag 4\", \"Tag 5\"],\n"
       "  \"experience\": [\n"
       "    {\n"
       "      \"company\": \"Company Name\",\n"
       "      \"role\": \"Role Title\",\n"
       "      \"period\": \"Date Range\",\n"
       "      \"location\": \"Location\",\n"
       "      \"bullets\": [\"Bullet 1\", \"Bullet 2\", ...]\n"
       "    }\n"
       "  ],\n"
       "  \"education\": [\n"
       "    {\n"
       "      \"title\": \"Degree/Diploma Name\",\n"
       "      \"org\": \"Institution Name\",\n"
       "      \"year\": \"Year\",\n"
       "      \"desc\": \"Optional details\"\n"
       "    }\n"
       "  ]\n"
       "}"))

;; =============================================================================
;; Typst Template & Wasm Compilation
;; =============================================================================

(def typst-layout-function
  (str "#let cv(\n"
       "  name: \"Alex Tan\",\n"
       "  contact: (),\n"
       "  summary: \"\",\n"
       "  competencies: (),\n"
       "  experience: (),\n"
       "  education: (),\n"
       ") = {\n"
       "  set page(\n"
       "    paper: \"a4\",\n"
       "    margin: (x: 1.5cm, top: 1.2cm, bottom: 1.2cm),\n"
       "  )\n"
       "  set text(\n"
       "    font: (\"Liberation Sans\", \"Arial\", \"DejaVu Sans\"),\n"
       "    size: 10pt,\n"
       "    fill: rgb(\"#2f2f2f\"),\n"
       "    lang: \"en\"\n"
       "  )\n"
       "  let brand-color = rgb(\"#157384\")\n"
       "  let dark-color = rgb(\"#1a1a2e\")\n"
       "  align(center)[\n"
       "    #text(size: 24pt, weight: \"bold\", fill: dark-color)[#name]\n"
       "    #v(-6pt)\n"
       "    #text(size: 9pt, fill: rgb(\"#555\"))[\n"
       "      #contact.join(\"   |   \")\n"
       "    ]\n"
       "  ]\n"
       "  v(4pt)\n"
       "  if summary != \"\" {\n"
       "    text(size: 11pt, weight: \"bold\", fill: brand-color)[PROFESSIONAL SUMMARY]\n"
       "    v(-4pt)\n"
       "    line(length: 100%, stroke: 1.5pt + brand-color)\n"
       "    v(2pt)\n"
       "    text(size: 10pt, weight: \"regular\", fill: rgb(\"#333\"))[#summary]\n"
       "    v(8pt)\n"
       "  }\n"
       "  if competencies.len() > 0 {\n"
       "    text(size: 11pt, weight: \"bold\", fill: brand-color)[CORE COMPETENCIES]\n"
       "    v(-4pt)\n"
       "    line(length: 100%, stroke: 1.5pt + brand-color)\n"
       "    v(4pt)\n"
       "    grid(\n"
       "      columns: (1fr, 1fr, 1fr, 1fr, 1fr),\n"
       "      gutter: 8pt,\n"
       "      ..competencies.map(c => align(center)[\n"
       "        #rect(fill: rgb(\"#f4f9fa\"), radius: 3pt, inset: (x: 6pt, y: 4pt), stroke: 0.5pt + rgb(\"#e2e9eb\"))[\n"
       "          #text(size: 8.5pt, weight: \"medium\", fill: brand-color)[#c]\n"
       "        ]\n"
       "      ])\n"
       "    )\n"
       "    v(8pt)\n"
       "  }\n"
       "  if experience.len() > 0 {\n"
       "    text(size: 11pt, weight: \"bold\", fill: brand-color)[WORK EXPERIENCE]\n"
       "    v(-4pt)\n"
       "    line(length: 100%, stroke: 1.5pt + brand-color)\n"
       "    v(4pt)\n"
       "    for job in experience {\n"
       "      block(width: 100%, breakable: false)[\n"
       "        #grid(\n"
       "          columns: (1fr, auto),\n"
       "          text(weight: \"bold\", size: 11pt, fill: dark-color)[#job.company],\n"
       "          text(size: 9pt, fill: rgb(\"#777\"))[#job.period]\n"
       "        )\n"
       "        #v(-4pt)\n"
       "        #grid(\n"
       "          columns: (1fr, auto),\n"
       "          text(weight: \"semibold\", size: 9.5pt, fill: rgb(\"#444\"))[#job.role],\n"
       "          text(size: 8.5pt, fill: rgb(\"#888\"), style: \"italic\")[#job.location]\n"
       "        )\n"
       "        #v(2pt)\n"
       "        #list(\n"
       "          marker: text(fill: brand-color)[•],\n"
       "          tight: true,\n"
       "          ..job.bullets.map(b => text(size: 9.5pt, fill: rgb(\"#333\"))[#b])\n"
       "        )\n"
       "        #v(6pt)\n"
       "      ]\n"
       "    }\n"
       "  }\n"
       "  if education.len() > 0 {\n"
       "    text(size: 11pt, weight: \"bold\", fill: brand-color)[EDUCATION]\n"
       "    v(-4pt)\n"
       "    line(length: 100%, stroke: 1.5pt + brand-color)\n"
       "    v(4pt)\n"
       "    for edu in education {\n"
       "      block(width: 100%, breakable: false)[\n"
       "        #grid(\n"
       "          columns: (1fr, auto),\n"
       "          text(weight: \"bold\", size: 10pt, fill: dark-color)[#edu.title],\n"
       "          text(size: 9pt, fill: rgb(\"#777\"))[#edu.year]\n"
       "        )\n"
       "        #v(-4pt)\n"
       "        #text(size: 9pt, fill: rgb(\"#444\"))[#edu.org #if edu.keys().contains(\"desc\") [ — #edu.desc ]]\n"
       "        #v(4pt)\n"
       "      ]\n"
       "    }\n"
       "  }\n"
       "}\n"))

(defn json-to-typst-call [json-str]
  (let [data (js/JSON.parse json-str)
        name (.-name data)
        contact (js/JSON.stringify (.-contact data))
        summary (js/JSON.stringify (.-summary data))
        competencies (js/JSON.stringify (.-competencies data))
        experience (js/JSON.stringify (.-experience data))
        education (js/JSON.stringify (.-education data))]
    (str "\n#cv(\n"
         "  name: " (js/JSON.stringify name) ",\n"
         "  contact: json(\"\"\"" contact "\"\"\"),\n"
         "  summary: " summary ",\n"
         "  competencies: json(\"\"\"" competencies "\"\"\"),\n"
         "  experience: json(\"\"\"" experience "\"\"\"),\n"
         "  education: json(\"\"\"" education "\"\"\")\n"
         ")\n")))

;; =============================================================================
;; Action Handlers
;; =============================================================================

(defn parse-score-summary [eval-text]
  (let [match (re-find #"(?s)---SCORE_SUMMARY---(.*?)---END_SUMMARY---" eval-text)]
    (if match
      (let [lines (str/split-lines (nth match 1))
            pairs (->> lines
                       (map str/trim)
                       (filter #(str/includes? % ":"))
                       (map #(str/split % #":" 2))
                       (map (fn [[k v]] [(str/trim k) (str/trim v)]))
                       (into {}))]
        {:company (get pairs "COMPANY" "Unknown")
         :role (get pairs "ROLE" "Unknown")
         :score (get pairs "SCORE" "0")
         :archetype (get pairs "ARCHETYPE" "Unknown")
         :legitimacy (get pairs "LEGITIMACY" "Unknown")})
      nil)))

(defn handle-evaluate! []
  (let [api-key (:api-key @state)
        cv (:cv-content @state)
        jd (:current-jd @state)]
    (if (or (empty? api-key) (empty? cv) (empty? jd))
      (js/alert "Please configure your API key, CV, and paste a job description first!")
      (do
        (swap! state assoc :loading? true :loading-msg "Evaluating job description via Gemini...")
        (track-event! "evaluation_started" {})
        (call-gemini!
         api-key
         evaluation-system-prompt
         (str "CANDIDATE CV:\n\n" cv "\n\n"
              "CANDIDATE PROFILE:\n\n" (js/JSON.stringify (clj->js (:profile @state))) "\n\n"
              "JOB DESCRIPTION:\n\n" jd)
         (fn [res-text]
           (let [summary (parse-score-summary res-text)]
             (swap! state assoc 
                    :loading? false 
                    :evaluation {:text res-text :summary summary}
                    :company-name (get summary :company ""))
             (track-event! "evaluation_completed" 
                           {:score (get summary :score)
                            :archetype (get summary :archetype)
                            :company (get summary :company)})))
         (fn [err-msg]
           (swap! state assoc :loading? false)
           (js/alert (str "Evaluation failed: " err-msg))
           (track-event! "evaluation_failed" {:error err-msg})))))))

(defn get-sg-today []
  (let [options (clj->js {:timeZone "Asia/Singapore"
                          :day "2-digit"
                          :month "2-digit"
                          :year "numeric"})
        formatter (js/Intl.DateTimeFormat. "en-SG" options)
        parts (.formatToParts formatter (js/Date.))
        day (.value (.find parts (fn [p] (= (.-type p) "day"))))
        month (.value (.find parts (fn [p] (= (.-type p) "month"))))
        year (.value (.find parts (fn [p] (= (.-type p) "year"))))]
    (str day "-" month "-" year)))

(defn handle-add-to-tracker! []
  (let [summary (get-in @state [:evaluation :summary])]
    (if-not summary
      (js/alert "No active evaluation summary found to log.")
      (let [today (get-sg-today)
            new-item {:id (str (inc (count (:tracker @state))))
                      :date today
                      :company (:company summary)
                      :role (:role summary)
                      :score (:score summary)
                      :status "Evaluated"
                      :pdf "❌"
                      :report-name (str (:company summary) " Evaluation")}
            updated (conj (:tracker @state) new-item)]
        (swap! state assoc :tracker updated)
        (set-storage-item! "hh_tracker" updated)
        (js/alert "Application logged to tracker board!")
        (track-event! "tracker_item_added" {:company (:company new-item)})))))

(defn handle-compile-pdf! []
  (let [api-key (:api-key @state)
        cv (:cv-content @state)
        jd (:current-jd @state)
        company (:company-name @state)]
    (if (or (empty? api-key) (empty? cv) (empty? company))
      (js/alert "API Key, CV, and Target Company Name are required!")
      (do
        (swap! state assoc :pdf-status :tailoring :loading? true :loading-msg "Requesting tailored CV text from Gemini...")
        (track-event! "pdf_tailoring_started" {:company company})
        (call-gemini!
         api-key
         tailoring-system-prompt
         (str "CANDIDATE CV:\n\n" cv "\n\n"
              "TARGET JOB DESCRIPTION:\n\n" (if (empty? jd) "Tailor standard profile matching company domain." jd))
         (fn [tailored-json]
           (swap! state assoc :loading-msg "Compiling tailored CV via Typst WASM...")
           (swap! state assoc :pdf-status :compiling)
           (let [typst-source (str typst-layout-function (json-to-typst-call tailored-json))]
             (-> (js/window.compileTypstToPdf typst-source)
                 (.then (fn [pdf-bytes]
                          (let [blob (js/Blob. (cljs.core/array pdf-bytes) (clj->js {:type "application/pdf"}))
                                url (js/URL.createObjectURL blob)
                                today (get-sg-today)
                                slug (str/lower-case (str/replace company #"[^a-zA-Z0-9]+" "-"))
                                filename (str "cv-alextan-" slug "-" today ".pdf")]
                            (swap! state assoc 
                                   :loading? false
                                   :pdf-status :ready
                                   :pdf-url url
                                   :pdf-filename filename)
                            (track-event! "pdf_compiled_success" {:company company})
                            ;; Update tracker PDF status if company matches
                            (let [updated-tracker (map (fn [item]
                                                         (if (= (:company item) company)
                                                           (assoc item :pdf "✅")
                                                           item))
                                                       (:tracker @state))]
                              (swap! state assoc :tracker (vec updated-tracker))
                              (set-storage-item! "hh_tracker" (vec updated-tracker))))))
                 (.catch (fn [err]
                           (swap! state assoc :loading? false :pdf-status :error)
                           (js/alert (str "Typst compilation failed: " (.-message err)))
                           (track-event! "pdf_compile_failed" {:error (.-message err)}))))))
         (fn [err]
           (swap! state assoc :loading? false :pdf-status :error)
           (js/alert (str "Tailoring failed: " err))
           (track-event! "pdf_tailoring_failed" {:error err})))))))

;; =============================================================================
;; Reagent UI Views (Aesthetic Dark Glassmorphism)
;; =============================================================================

(defn header []
  [:header.glass-card.border-b.border-slate-800.sticky.top-0.z-40
   [:div.max-w-7xl.mx-auto.px-6.h-16.flex.items-center.justify-between
    [:div.flex.items-center.space-x-3
     [:span.font-serif.text-2xl.text-white.tracking-tight "co"
      [:span.text-brand ", headhunter-agent"]]
     [:span.bg-slate-800.text-xs.font-mono.px-2.py-0.5.rounded.text-slate-400 "v1.1.0 (SG)"]]
    
    [:nav.flex.space-x-1
     (for [[tab label icon] [[:evaluate "Evaluate" "fa-solid fa-bolt"]
                             [:pdf "Tailor PDF" "fa-solid fa-file-pdf"]
                             [:tracker "Tracker" "fa-solid fa-columns"]
                             [:profile "Profile Setup" "fa-solid fa-cog"]]]
       ^{:key tab}
       [:button.px-4.py-2.rounded-lg.text-sm.font-medium.flex.items-center.space-x-2.transition-all
        {:class (if (= (:active-tab @state) tab)
                  "bg-brand text-white shadow-lg"
                  "text-slate-400 hover:text-white hover:bg-slate-800/50")
         :on-click #(swap! state assoc :active-tab tab)}
        [:i {:class icon}]
        [:span label]])]]])

(defn profile-tab []
  (let [prof (r/atom (:profile @state))
        key-val (r/atom (:api-key @state))
        cv-val (r/atom (:cv-content @state))]
    (fn []
      [:div.max-w-5xl.mx-auto.p-6.space-y-6
       [:div.glass-card.p-6.rounded-xl
        [:h2.text-xl.font-serif.text-white.mb-4 "Credentials & Core Configurations"]
        [:div.space-y-4
         [:div
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2 "Google Gemini API Key"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "password"
            :placeholder "paste phc_ or gemini api key here"
            :value @key-val
            :on-change #(reset! key-val (-> % .-target .-value))}]]]
        
        [:div.grid.grid-cols-1.md:grid-cols-2.gap-4.mt-6
         [:div
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2 "Full Name"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "text" :value (:name @prof) :on-change #(swap! prof assoc :name (-> % .-target .-value))}]
          
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2.mt-4 "Email"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "text" :value (:email @prof) :on-change #(swap! prof assoc :email (-> % .-target .-value))}]
          
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2.mt-4 "Monthly Base Target (SGD)"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "text" :value (:monthly-target @prof) :on-change #(swap! prof assoc :monthly-target (-> % .-target .-value))}]]
         
         [:div
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2 "Phone"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "text" :value (:phone @prof) :on-change #(swap! prof assoc :phone (-> % .-target .-value))}]
          
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2.mt-4 "Residency Status (MOM FCF)"]
          [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:type "text" :value (:residency @prof) :on-change #(swap! prof assoc :residency (-> % .-target .-value))}]
          
          [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2.mt-4 "AWS (13th Month) Expected?"]
          [:select.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
           {:value (str (:aws-13th @prof))
            :on-change #(swap! prof assoc :aws-13th (= (-> % .-target .-value) "true"))}
           [:option {:value "true"} "Yes, expected standard"]
           [:option {:value "false"} "No, flexible"]]]]]
       
       [:div.glass-card.p-6.rounded-xl
        [:h2.text-xl.font-serif.text-white.mb-4 "Master CV Source (Markdown)"]
        [:textarea.w-full.h-80.bg-slate-900/80.border.border-slate-800.rounded-lg.p-4.text-sm.font-mono.text-slate-300.focus:outline-none.focus:border-brand.no-scrollbar
         {:placeholder "# CV -- My Name\n\n## Experience..."
          :value @cv-val
          :on-change #(reset! cv-val (-> % .-target .-value))}]
        
        [:div.flex.justify-end.space-x-4.mt-6
         [:button.px-6.py-2.5.rounded-lg.bg-brand.hover:bg-brandDark.text-white.text-sm.font-semibold.transition-all
          {:on-click (fn []
                       (swap! state assoc 
                              :api-key @key-val
                              :cv-content @cv-val
                              :profile @prof)
                       (set-storage-item! "hh_api_key" @key-val)
                       (set-storage-item! "hh_cv" @cv-val)
                       (set-storage-item! "hh_profile" @prof)
                       (js/alert "Core profile saved successfully!")
                       (track-event! "profile_saved" {}))}
          "Save Configuration"]]]])))

(defn evaluate-tab []
  [:div.max-w-6xl.mx-auto.p-6.grid.grid-cols-1.lg:grid-cols-12.gap-6
   [:div.lg:col-span-5.space-y-6
    [:div.glass-card.p-6.rounded-xl
     [:h2.text-xl.font-serif.text-white.mb-4 "Evaluate Singapore Opportunities"]
     [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2 "Paste Job Description (JD)"]
     [:textarea.w-full.h-96.bg-slate-900/80.border.border-slate-800.rounded-lg.p-4.text-sm.text-slate-300.focus:outline-none.focus:border-brand.no-scrollbar
      {:placeholder "Paste raw job post description here..."
       :value (:current-jd @state)
       :on-change #(swap! state assoc :current-jd (-> % .-target .-value))}]
     
     [:button.w-full.mt-6.py-3.rounded-lg.bg-brand.hover:bg-brandDark.text-white.text-sm.font-semibold.flex.items-center.justify-center.space-x-2.transition-all
      {:on-click handle-evaluate!}
      [:i.fa-solid.fa-bolt]
      [:span "Evaluate Compatibility"]]]]
   
   [:div.lg:col-span-7
    (if-let [eval-data (:evaluation @state)]
      [:div.glass-card.p-6.rounded-xl.space-y-6
       [:div.flex.items-center.justify-between.border-b.border-slate-800.pb-4
        [:div
         [:h2.text-2xl.font-serif.text-white (get-in eval-data [:summary :company] "Analyzed Offer")]
         [:p.text-sm.text-slate-400 (get-in eval-data [:summary :role] "Evaluation details")]]
        
        [:div.flex.items-center.space-x-4
         [:div.text-center
          [:div.text-2xl.font-bold.text-brand (str (get-in eval-data [:summary :score] "0") "/5")]
          [:div.text-[10px].font-mono.text-slate-400.uppercase "Grade"]]
         [:div.text-center
          [:div.text-sm.font-bold.text-white (get-in eval-data [:summary :legitimacy] "Unknown")]
          [:div.text-[10px].font-mono.text-slate-400.uppercase "FCF Legitimacy"]]]]
       
       [:div.prose.prose-invert.max-w-none.h-[450px].overflow-y-auto.no-scrollbar.p-2.bg-slate-900/40.rounded-lg.border.border-slate-800/60
        [:pre.whitespace-pre-wrap.font-sans.text-sm.text-slate-300 (:text eval-data)]]
       
       [:div.flex.justify-between.items-center.pt-4.border-t.border-slate-800
        [:button.px-5.py-2.rounded-lg.bg-slate-800.hover:bg-slate-700.text-slate-300.text-xs.font-semibold.flex.items-center.space-x-2.transition-all
         {:on-click handle-add-to-tracker!}
         [:i.fa-solid.fa-folder-plus]
         [:span "Log to Application Board"]]
        
        [:button.px-5.py-2.rounded-lg.bg-brand.hover:bg-brandDark.text-white.text-xs.font-semibold.flex.items-center.space-x-2.transition-all
         {:on-click #(swap! state assoc :active-tab :pdf)}
         [:span "Tailor Resume PDF"]
         [:i.fa-solid.fa-arrow-right]]]]
      
      [:div.glass-card.p-6.rounded-xl.h-full.flex.flex-col.items-center.justify-center.text-center.text-slate-500.py-24
       [:i.fa-solid.fa-chart-pie.text-4xl.text-slate-700.mb-4]
       [:p.text-sm "Paste a Job Description and click Evaluate to start analyzing match scores and drafting interview behaviors."]])]])

(defn pdf-compiler-tab []
  [:div.max-w-4xl.mx-auto.p-6.space-y-6
   [:div.glass-card.p-6.rounded-xl
    [:h2.text-xl.font-serif.text-white.mb-4 "Tailor ATS Resume (Typst Wasm)"]
    [:p.text-sm.text-slate-400.mb-6 "Generates tailored CV summary/bullets mapped with JD keywords fact-based only, compiling PDF instantly in browser."]
    
    [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
     [:div
      [:label.block.text-xs.font-mono.text-slate-400.uppercase.mb-2 "Target Company Name"]
      [:input.w-full.bg-slate-900/80.border.border-slate-800.rounded-lg.px-4.py-2.5.text-sm.text-white.focus:outline-none.focus:border-brand
       {:type "text"
        :placeholder "e.g. Defence Collective Singapore"
        :value (:company-name @state)
        :on-change #(swap! state assoc :company-name (-> % .-target .-value))}]]
     [:div.flex.items-end
      [:button.w-full.py-3.rounded-lg.bg-brand.hover:bg-brandDark.text-white.text-sm.font-semibold.flex.items-center.justify-center.space-x-2.transition-all
       {:on-click handle-compile-pdf!}
       [:i.fa-solid.fa-wand-magic-sparkles]
       [:span "Tailor & Compile Resume PDF"]]]]]
   
   (when-not (= (:pdf-status @state) :idle)
     [:div.glass-card.p-6.rounded-xl.space-y-4
      [:h3.text-lg.font-medium.text-white "Compilation Pipeline Status"]
      [:div.flex.items-center.space-x-4
       (case (:pdf-status @state)
         :tailoring [:div.flex.items-center.space-x-2.text-brand
                     [:i.fa-solid.fa-spinner.animate-spin]
                     [:span "Gemini tailoring experience bullets factually..."]]
         :compiling [:div.flex.items-center.space-x-2.text-cyan-400
                     [:i.fa-solid.fa-spinner.animate-spin]
                     [:span "Compiling resume.typ via Typst WASM compiler..."]]
         :ready     [:div.flex.items-center.space-x-2.text-green-400
                     [:i.fa-solid.fa-check-circle]
                     [:span "PDF Resume Tailored and Compiled Successfully!"]]
         :error     [:div.flex.items-center.space-x-2.text-red-400
                     [:i.fa-solid.fa-exclamation-triangle]
                     [:span "An error occurred during compilation."]]
         nil)]
      
      (when (= (:pdf-status @state) :ready)
        [:div.flex.space-x-4.pt-4.border-t.border-slate-800
         [:a.px-6.py-3.rounded-lg.bg-green-600.hover:bg-green-700.text-white.text-sm.font-semibold.flex.items-center.space-x-2.transition-all
          {:href (:pdf-url @state)
           :download (:pdf-filename @state)}
          [:i.fa-solid.fa-download]
          [:span "Download Tailored PDF"]]
         
         [:a.px-6.py-3.rounded-lg.bg-slate-800.hover:bg-slate-700.text-slate-300.text-sm.font-semibold.flex.items-center.space-x-2.transition-all
          {:href (:pdf-url @state)
           :target "_blank"}
          [:i.fa-solid.fa-eye]
          [:span "Preview PDF Resume"]]])])])

(defn tracker-tab []
  (let [tracker-list (:tracker @state)]
    [:div.max-w-6xl.mx-auto.p-6.space-y-6
     [:div.glass-card.p-6.rounded-xl.flex.items-center.justify-between
      [:div
       [:h2.text-xl.font-serif.text-white "Application Tracking Board"]
       [:p.text-sm.text-slate-400 "Sovereign log of evaluations and PDFs compiled (locally persisted)."]]
      
      [:button.px-4.py-2.rounded-lg.bg-slate-800.hover:bg-slate-700.text-slate-300.text-xs.font-semibold.flex.items-center.space-x-2.transition-all
       {:on-click (fn []
                    (let [header-row "| # | Date | Company | Role | Score | Status | PDF |\n|---|---|---|---|---|---|---|\n"
                          rows (->> tracker-list
                                    (map (fn [item]
                                           (str "| " (:id item) " | " (:date item) " | " (:company item) " | " (:role item) " | " (:score item) " | " (:status item) " | " (:pdf item) " |\n")))
                                    (apply str))
                          content (str "# Applications Tracker\n\n" header-row rows)
                          blob (js/Blob. (cljs.core/array [content]) (clj->js {:type "text/markdown"}))
                          url (js/URL.createObjectURL blob)
                          a (.createElement js/document "a")]
                      (set! (.-href a) url)
                      (set! (.-download a) "applications.md")
                      (.click a)
                      (track-event! "tracker_exported" {})))}
       [:i.fa-solid.fa-download]
       [:span "Export Tracker (applications.md)"]]]
     
     [:div.glass-card.rounded-xl.overflow-hidden
      (if (empty? tracker-list)
       [:div.p-12.text-center.text-slate-500
        [:i.fa-solid.fa-folder-open.text-4xl.text-slate-700.mb-4]
        [:p "No logged applications. Start by evaluating a JD and click log to applications tracker."]]
       
       [:table.w-full.text-left.border-collapse.text-sm
        [:thead.bg-slate-900/60.text-slate-400.font-mono.text-xs.uppercase.border-b.border-slate-800
         [:tr
          [:th.p-4 "#"]
          [:th.p-4 "Date"]
          [:th.p-4 "Company"]
          [:th.p-4 "Role"]
          [:th.p-4 "Score"]
          [:th.p-4 "Status"]
          [:th.p-4 "PDF"]
          [:th.p-4.text-center "Actions"]]]
        
        [:tbody.divide-y.divide-slate-800/40
         (for [item tracker-list]
           ^{:key (:id item)}
           [:tr.hover:bg-slate-800/10.transition-all
            [:td.p-4.font-mono.text-slate-400 (:id item)]
            [:td.p-4 (:date item)]
            [:td.p-4.font-medium.text-white (:company item)]
            [:td.p-4.text-slate-300 (:role item)]
            [:td.p-4.font-semibold.text-brand (:score item)]
            [:td.p-4
             [:select.bg-slate-900.border.border-slate-800.text-slate-300.rounded.px-2.py-1.text-xs
              {:value (:status item)
               :on-change (fn [e]
                            (let [new-status (-> e .-target .-value)
                                  updated (map (fn [i]
                                                 (if (= (:id i) (:id item))
                                                   (assoc i :status new-status)
                                                   i))
                                               (:tracker @state))]
                              (swap! state assoc :tracker (vec updated))
                              (set-storage-item! "hh_tracker" (vec updated))
                              (track-event! "tracker_status_changed" {:company (:company item) :status new-status})))}
              [:option "Evaluated"]
              [:option "Applied"]
              [:option "Interviewing"]
              [:option "Offered"]
              [:option "Rejected"]]]
            [:td.p-4 (:pdf item)]
            [:td.p-4.text-center
             [:button.text-red-400.hover:text-red-300.transition-colors
              {:on-click (fn []
                           (let [updated (filter #(not= (:id %) (:id item)) (:tracker @state))
                                 re-indexed (map-indexed (fn [idx itm] (assoc itm :id (str (inc idx)))) updated)]
                             (swap! state assoc :tracker (vec re-indexed))
                             (set-storage-item! "hh_tracker" (vec re-indexed))
                             (track-event! "tracker_item_deleted" {})))}
              [:i.fa-solid.fa-trash-can]]]]])])]])))

(defn loading-overlay []
  (when (:loading? @state)
    [:div.fixed.inset-0.bg-slate-950/80.z-50.flex.flex-col.items-center.justify-center.space-y-4
     [:div.w-12.h-12.border-4.border-brand.border-t-transparent.rounded-full.animate-spin]
     [:div.text-lg.font-medium.text-slate-200.font-serif.tracking-wide (:loading-msg @state)]]))

(defn main-layout []
  [:div.min-h-screen.flex.flex-col
   [header]
   [:main.flex-1.py-8
    (case (:active-tab @state)
      :evaluate [evaluate-tab]
      :pdf      [pdf-compiler-tab]
      :tracker  [tracker-tab]
      :profile  [profile-tab])]
   [loading-overlay]])

;; =============================================================================
;; Entrypoint & Initialization
;; =============================================================================

(defn init []
  (rdom/render [main-layout] (.getElementById js/document "app"))
  (track-event! "gui_opened" {}))

(init)
