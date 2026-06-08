(ns career-ops.gui
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [career-ops.profiler :as profiler]
            [career-ops.evaluator :as eval]
            [career-ops.interview :as interview]
            [career-ops.tracker :as tracker])
  (:import [javafx.application Platform]
           [java.io File]))

;; Global state atom
(defonce *state
  (atom {:active-tab :vault ; :vault, :evaluate, :interview, :tracker
         
         :vault-input ""
         :vault-status :idle ; :idle, :loading, :success, :error
         :vault-error ""
         :master-profile nil
         :star-stories nil
         :selected-star-story nil
         
         :evaluator-jd ""
         :selected-model "gemini-2.5-flash"
         :save-report? true
         :evaluator-status :idle ; :idle, :loading, :success, :error
         :evaluator-result ""
         :evaluator-error ""
         
         :interview-jd ""
         :interview-status :idle
         :interview-result ""
         :interview-error ""
         
         :tracker-rows []}))

(defn run-later [f]
  (Platform/runLater f))

;; Load local files into state
(defn load-local-data! []
  (let [mp-file (io/file "data/master-profile.edn")
        ss-file (io/file "data/star-stories.edn")
        cv-file (io/file "cv.md")]
    (swap! *state assoc
           :master-profile (when (.exists mp-file) (edn/read-string (slurp mp-file)))
           :star-stories (when (.exists ss-file) (edn/read-string (slurp ss-file)))
           :vault-input (if (and (empty? (:vault-input @*state)) (.exists cv-file))
                          (slurp cv-file)
                          (:vault-input @*state))
           :tracker-rows (try (tracker/parse-rows) (catch Exception _ [])))))

;; Event Handlers
(defn handle-extract-vault [_]
  (let [input (str/trim (:vault-input @*state))]
    (if (empty? input)
      (swap! *state assoc :vault-status :error :vault-error "Error: Input data cannot be empty.")
      (do
        (swap! *state assoc :vault-status :loading :vault-error "")
        (future
          (try
            (let [temp-file (java.io.File/createTempFile "linkedin_dump" ".txt")]
              (.deleteOnExit temp-file)
              (spit temp-file input)
              (let [res (profiler/extract-profile! (.getAbsolutePath temp-file))]
                (run-later
                 #(do
                    (swap! *state assoc
                           :vault-status :success
                           :master-profile (:master-profile res)
                           :star-stories (:star-stories res)
                           :selected-star-story 0)
                    (load-local-data!)))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :vault-status :error
                       :vault-error (str "Extraction failed: " (.getMessage e)))))))))))

(defn handle-evaluate-jd [_]
  (let [jd (str/trim (:evaluator-jd @*state))
        model (:selected-model @*state "gemini-2.5-flash")
        save? (:save-report? @*state true)]
    (if (empty? jd)
      (swap! *state assoc :evaluator-status :error :evaluator-error "Error: Job Description cannot be empty.")
      (do
        (swap! *state assoc :evaluator-status :loading :evaluator-error "" :evaluator-result "")
        (future
          (try
            (let [res (eval/evaluate-jd jd :model-name model :save-report? save?)]
              (run-later
               #(do
                  (swap! *state assoc
                         :evaluator-status :success
                         :evaluator-result (:report res))
                  (load-local-data!))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :evaluator-status :error
                       :evaluator-error (str "Evaluation failed: " (.getMessage e)))))))))))

(defn handle-interview-prep [_]
  (let [jd (str/trim (:interview-jd @*state))]
    (if (empty? jd)
      (swap! *state assoc :interview-status :error :interview-error "Error: Job Description cannot be empty.")
      (do
        (swap! *state assoc :interview-status :loading :interview-error "" :interview-result "")
        (future
          (try
            (let [res (interview/prep-interview! jd)]
              (run-later
               #(swap! *state assoc
                       :interview-status :success
                       :interview-result (:result res))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :interview-status :error
                       :interview-error (str "Prep failed: " (.getMessage e)))))))))))

(defn handle-refresh-tracker []
  (swap! *state assoc :tracker-rows (try (tracker/parse-rows) (catch Exception _ []))))

(defn handle-mark-pdf [id]
  (try
    (tracker/mark-pdf-ready! id "")
    (handle-refresh-tracker)
    (catch Exception e
      (println "Error marking PDF:" (.getMessage e)))))

(defn handle-view-report [report-link]
  (when-let [path (re-find #"reports/[^\)]+" report-link)]
    (let [f (io/file path)]
      (if (.exists f)
        (let [content (slurp f)]
          (swap! *state assoc
                 :active-tab :evaluate
                 :evaluator-status :success
                 :evaluator-result content
                 :evaluator-jd ""))
        (println "Warning: report file not found at" path)))))

;; Layout helper
(defn get-stylesheet-url []
  (let [f (io/file "src/career_ops/style.css")]
    (if (.exists f)
      (-> f .toURI .toURL .toExternalForm)
      (if-let [res (io/resource "career_ops/style.css")]
        (.toExternalForm res)
        ""))))

(defn score-color [score-str]
  (let [num (try (Double/parseDouble (str/replace score-str #"/5" "")) (catch Exception _ 0.0))]
    (cond
      (>= num 4.0) "#10b981"
      (>= num 3.0) "#fbbf24"
      :else "#f87171")))

(defn split-report [report]
  (if (empty? report)
    ["" "" ""]
    (let [parts (str/split report #"\n*---\n*")]
      (if (>= (count parts) 4)
        [(nth parts 1 "")
         (nth parts 2 "")
         (nth parts 3 "")]
        [(nth parts 0 "")
         (nth parts 1 "")
         (nth parts 2 "")]))))

;; --- VIEW COMPONENTS ---

;; Sidebar View
(defn sidebar-view [{:keys [state]}]
  (let [active (:active-tab state :vault)
        env (eval/load-env)
        has-key? (not (empty? (get env "GEMINI_API_KEY")))]
    {:fx/type :v-box
     :style-class ["sidebar"]
     :pref-width 220
     :spacing 25
     :children
     [{:fx/type :v-box
       :spacing 5
       :children
       [{:fx/type :label
         :text "💼 HEADHUNTER-AGENT"
         :style-class ["sidebar-title"]}
        {:fx/type :label
         :text "MAS DESKTOP CONSOLE"
         :style-class ["sidebar-subtitle"]}]}
      {:fx/type :v-box
       :spacing 8
       :v-box/vgrow :always
       :children
       [{:fx/type :button
         :text "🗄️  Data Vault"
         :max-width Double/MAX_VALUE
         :style-class [(if (= active :vault) "sidebar-btn-active" "sidebar-btn")]
         :on-action (fn [_] (swap! *state assoc :active-tab :vault))}
        {:fx/type :button
         :text "📈  JD Evaluator"
         :max-width Double/MAX_VALUE
         :style-class [(if (= active :evaluate) "sidebar-btn-active" "sidebar-btn")]
         :on-action (fn [_] (swap! *state assoc :active-tab :evaluate))}
        {:fx/type :button
         :text "🧠  Interview Prep"
         :max-width Double/MAX_VALUE
         :style-class [(if (= active :interview) "sidebar-btn-active" "sidebar-btn")]
         :on-action (fn [_] (swap! *state assoc :active-tab :interview))}
        {:fx/type :button
         :text "📊  Pipeline Tracker"
         :max-width Double/MAX_VALUE
         :style-class [(if (= active :tracker) "sidebar-btn-active" "sidebar-btn")]
         :on-action (fn [_] (swap! *state assoc :active-tab :tracker))}]}
      {:fx/type :v-box
       :spacing 5
       :children
       [{:fx/type :label
         :text "ENVIRONMENT STATUS"
         :style "-fx-font-size: 10px; -fx-text-fill: #475569; -fx-font-weight: bold;"}
        {:fx/type :h-box
         :spacing 5
         :alignment :center-left
         :children
         [{:fx/type :label
           :text "Gemini API:"
           :style "-fx-text-fill: #64748b; -fx-font-size: 11px;"}
          {:fx/type :label
           :text (if has-key? "Connected" "Missing Key")
           :style-class [(if has-key? "badge-green" "badge-red")]
           :style "-fx-font-size: 10px; -fx-padding: 2px 6px;"}]}]}]}))

;; Interactive Data Vault Explorer
(defn data-vault-explorer [{:keys [state]}]
  (let [profile (:master-profile state)
        stories (:star-stories state)
        selected-story (:selected-star-story state)]
    {:fx/type :v-box
     :spacing 15
     :children
     [{:fx/type :label
       :text "Vault Explorer"
       :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f1f5f9;"}
      {:fx/type :scroll-pane
       :fit-to-width true
       :v-box/vgrow :always
       :content
       {:fx/type :v-box
        :spacing 15
        :children
        [{:fx/type :v-box
          :style-class ["card"]
          :spacing 10
          :children
          [{:fx/type :label
            :text (get-in profile [:contact_info :name] "Candidate Profile")
            :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;"}
           {:fx/type :label
            :text (str "Experience: " (:total_years_experience profile "7+") " years")
            :style "-fx-text-fill: #38bdf8; -fx-font-weight: bold;"}
           {:fx/type :h-box
            :spacing 20
            :children
            [{:fx/type :v-box
              :h-box/hgrow :always
              :spacing 5
              :children
              [{:fx/type :label :text "Technical Skills" :style "-fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
               {:fx/type :label
                :text (str/join ", " (:core_technical_skills profile []))
                :wrap-text true
                :style "-fx-text-fill: #e2e8f0;"}]}
             {:fx/type :v-box
              :h-box/hgrow :always
              :spacing 5
              :children
              [{:fx/type :label :text "Infrastructure & Security" :style "-fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
               {:fx/type :label
                :text (str/join ", " (:infrastructure_security_skills profile []))
                :wrap-text true
                :style "-fx-text-fill: #e2e8f0;"}]}]}]}
         
         ;; Certifications Card
         {:fx/type :v-box
          :style-class ["card"]
          :spacing 5
          :children
          [{:fx/type :label :text "Certifications" :style "-fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
           {:fx/type :label
            :text (str/join "  |  " (:certifications profile []))
            :wrap-text true
            :style "-fx-text-fill: #e2e8f0;"}]}
         
         ;; STAR Stories Section
         {:fx/type :label
          :text "STAR Stories Library"
          :style "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f1f5f9;"}
         
         {:fx/type :h-box
          :spacing 15
          :children
          [{:fx/type :v-box
            :pref-width 280
            :spacing 8
            :children
            (map-indexed
             (fn [idx story]
               {:fx/type :v-box
                :style-class [(if (= selected-story idx) "star-list-item-active" "star-list-item")]
                :on-mouse-clicked (fn [_] (swap! *state assoc :selected-star-story idx))
                :children
                [{:fx/type :label
                  :text (:title story "Untitled Story")
                  :style (str "-fx-font-weight: bold; " (if (= selected-story idx) "-fx-text-fill: #ffffff;" "-fx-text-fill: #f1f5f9;"))}
                 {:fx/type :label
                  :text (str/join ", " (:tags story []))
                  :style (str "-fx-font-size: 11px; " (if (= selected-story idx) "-fx-text-fill: #e0f2fe;" "-fx-text-fill: #64748b;"))}]})
             stories)}
           
           ;; STAR Story Details Card
           {:fx/type :v-box
            :h-box/hgrow :always
            :style-class ["card"]
            :spacing 10
            :children
            (if-let [story (and selected-story (nth stories selected-story nil))]
              [{:fx/type :label
                :text (:title story)
                :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;"}
               {:fx/type :scroll-pane
                :fit-to-width true
                :content
                {:fx/type :v-box
                 :spacing 12
                 :children
                 [{:fx/type :v-box
                   :spacing 2
                   :children
                   [{:fx/type :label :text "SITUATION" :style "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;"}
                    {:fx/type :label :text (:situation story) :wrap-text true :style "-fx-text-fill: #e2e8f0;"}]}
                  {:fx/type :v-box
                   :spacing 2
                   :children
                   [{:fx/type :label :text "TASK" :style "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;"}
                    {:fx/type :label :text (:task story) :wrap-text true :style "-fx-text-fill: #e2e8f0;"}]}
                  {:fx/type :v-box
                   :spacing 2
                   :children
                   [{:fx/type :label :text "ACTION" :style "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;"}
                    {:fx/type :label :text (:action story) :wrap-text true :style "-fx-text-fill: #e2e8f0;"}]}
                  {:fx/type :v-box
                   :spacing 2
                   :children
                   [{:fx/type :label :text "RESULT" :style "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;"}
                    {:fx/type :label :text (:result story) :wrap-text true :style "-fx-text-fill: #e2e8f0;"}]}]}}]
              [{:fx/type :label
                :text "Select a STAR story from the list to view details."
                :style "-fx-text-fill: #64748b;"}])}]}]}}]}))

;; Data Vault Main View
(defn vault-view [{:keys [state]}]
  (let [status (:vault-status state)
        error (:vault-error state)
        profile (:master-profile state)]
    {:fx/type :h-box
     :spacing 20
     :padding 20
     :style "-fx-background-color: #0f172a;"
     :children
     [{:fx/type :v-box
       :pref-width 400
       :spacing 15
       :children
       [{:fx/type :label
         :text "1. Data Vault Builder"
         :style-class ["header-title"]}
        {:fx/type :label
         :text "Paste your raw LinkedIn export text or resume below to build your structured local profile."
         :wrap-text true
         :style-class ["header-subtitle"]}
        {:fx/type :text-area
         :v-box/vgrow :always
         :text (:vault-input state)
         :prompt-text "Paste raw CV or LinkedIn export..."
         :on-text-changed #(swap! *state assoc :vault-input %)
         :style "-fx-font-family: monospace;"}
        {:fx/type :h-box
         :spacing 10
         :alignment :center-left
         :children
         [(if (= status :loading)
            {:fx/type :button :text "Extracting..." :disable true :style-class ["button-primary"]}
            {:fx/type :button
             :text "Extract Data Vault"
             :style-class ["button-primary"]
             :on-action handle-extract-vault})
          (cond
            (= status :loading)
            {:fx/type :label :text "Processing with Gemini..." :style "-fx-text-fill: #fbbf24;"}
            
            (= status :success)
            {:fx/type :label :text "✅ Extraction complete!" :style "-fx-text-fill: #34d399;"}
            
            (= status :error)
            {:fx/type :label :text error :style "-fx-text-fill: #f87171;" :wrap-text true :max-width 250}
            
            :else
            {:fx/type :label :text "Status: Ready" :style "-fx-text-fill: #60a5fa;"})]}]}
      
      {:fx/type :separator
       :orientation :vertical}
      
      {:fx/type :v-box
       :h-box/hgrow :always
       :children
       [(if profile
          {:fx/type data-vault-explorer :state state}
          {:fx/type :v-box
           :alignment :center
           :v-box/vgrow :always
           :style-class ["card"]
           :spacing 15
           :children
           [{:fx/type :label
             :text "📭 Data Vault Empty"
             :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
            {:fx/type :label
             :text "Extract your resume or LinkedIn data to populate your profile dashboard."
             :wrap-text true
             :style "-fx-text-fill: #64748b; -fx-text-alignment: center;"}]})]}]}))

;; Evaluator Details Tabs
(defn evaluator-details-pane [{:keys [stage1 stage2 stage3]}]
  {:fx/type :tab-pane
   :v-box/vgrow :always
   :tabs
   [{:fx/type :tab
     :text "Stage 1: Legitimacy"
     :closable false
     :content
     {:fx/type :text-area
      :editable false
      :text stage1
      :style "-fx-font-family: sans-serif; -fx-font-size: 13px;"}}
    {:fx/type :tab
     :text "Stage 2: Fit Analysis"
     :closable false
     :content
     {:fx/type :text-area
      :editable false
      :text stage2
      :style "-fx-font-family: sans-serif; -fx-font-size: 13px;"}}
    {:fx/type :tab
     :text "Stage 3: Cheat Sheet"
     :closable false
     :content
     {:fx/type :text-area
      :editable false
      :text stage3
      :style "-fx-font-family: sans-serif; -fx-font-size: 13px;"}}]})

;; JD Evaluator Main View
(defn evaluate-view [{:keys [state]}]
  (let [status (:evaluator-status state)
        error (:evaluator-error state)
        result (:evaluator-result state)
        summary (eval/parse-summary result)
        [stage1 stage2 stage3] (split-report result)]
    {:fx/type :h-box
     :spacing 20
     :padding 20
     :style "-fx-background-color: #0f172a;"
     :children
     [{:fx/type :v-box
       :pref-width 400
       :spacing 15
       :children
       [{:fx/type :label
         :text "2. 3-Stage JD Evaluator"
         :style-class ["header-title"]}
        {:fx/type :label
         :text "Paste a job description to evaluate FCF legitimacy, perform a critical fit analysis, and build interview cheat sheets."
         :wrap-text true
         :style-class ["header-subtitle"]}
        {:fx/type :text-area
         :v-box/vgrow :always
         :text (:evaluator-jd state)
         :prompt-text "Paste target Job Description here..."
         :on-text-changed #(swap! *state assoc :evaluator-jd %)
         :style "-fx-font-family: monospace;"}
        
        {:fx/type :h-box
         :spacing 15
         :alignment :center-left
         :children
         [{:fx/type :label :text "Model:"}
          {:fx/type :combo-box
           :value (:selected-model state "gemini-2.5-flash")
           :items ["gemini-2.5-flash" "gemini-2.5-pro"]
           :on-value-changed #(swap! *state assoc :selected-model %)}
          {:fx/type :check-box
           :text "Save Report"
           :selected (:save-report? state true)
           :on-selected-changed #(swap! *state assoc :save-report? %)}]}
        
        {:fx/type :h-box
         :spacing 10
         :alignment :center-left
         :children
         [(if (= status :loading)
            {:fx/type :button :text "Evaluating..." :disable true :style-class ["button-primary"]}
            {:fx/type :button
             :text "Evaluate JD"
             :style-class ["button-primary"]
             :on-action handle-evaluate-jd})
          (cond
            (= status :loading)
            {:fx/type :label :text "Agents working..." :style "-fx-text-fill: #fbbf24;"}
            
            (= status :success)
            {:fx/type :label :text "✅ Evaluation complete!" :style "-fx-text-fill: #34d399;"}
            
            (= status :error)
            {:fx/type :label :text error :style "-fx-text-fill: #f87171;" :wrap-text true :max-width 250}
            
            :else
            {:fx/type :label :text "Status: Ready" :style "-fx-text-fill: #60a5fa;"})]}]}
      
      {:fx/type :separator
       :orientation :vertical}
      
      {:fx/type :v-box
       :h-box/hgrow :always
       :spacing 15
       :children
       [(cond
          (= status :loading)
          {:fx/type :v-box
           :alignment :center
           :v-box/vgrow :always
           :style-class ["card"]
           :spacing 20
           :children
           [{:fx/type :label :text "⏳ Running Multi-Agent Pipeline" :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #fbbf24;"}
            {:fx/type :v-box
             :spacing 10
             :alignment :center-left
             :children
             [{:fx/type :label :text "  Stage 1: FCF & Red Flags check... [⌛]"}
              {:fx/type :label :text "  Stage 2: Profile Fit & Gaps... [⌛]"}
              {:fx/type :label :text "  Stage 3: Corporate Cheat Sheet... [⌛]"}]}]}
          
          (= status :success)
          {:fx/type :v-box
           :v-box/vgrow :always
           :spacing 15
           :children
           [{:fx/type :h-box
             :style-class ["card"]
             :spacing 25
             :alignment :center-left
             :children
             [{:fx/type :v-box
               :style-class ["score-circle"]
               :style (str "-fx-border-color: " (score-color (:score summary "0.0")) ";")
               :children
               [{:fx/type :label
                 :text (:score summary "?")
                 :style-class ["score-text"]
                 :style (str "-fx-text-fill: " (score-color (:score summary "0.0")) ";")}
                {:fx/type :label
                 :text "Score"
                 :style "-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;"}]}
              {:fx/type :v-box
               :spacing 8
               :children
               [{:fx/type :label
                 :text (:role summary "Unknown Role")
                 :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;"}
                {:fx/type :label
                 :text (:company summary "Unknown Company")
                 :style "-fx-font-size: 14px; -fx-text-fill: #94a3b8; -fx-font-weight: bold;"}
                {:fx/type :h-box
                 :spacing 10
                 :children
                 [{:fx/type :label
                   :text (:archetype summary "Unknown Archetype")
                   :style-class ["badge" "badge-blue"]}
                  {:fx/type :label
                   :text (:legitimacy summary "Unknown Legitimacy")
                   :style-class ["badge" (cond
                                           (= (:legitimacy summary) "High Confidence") "badge-green"
                                           (= (:legitimacy summary) "Proceed with Caution") "badge-orange"
                                           :else "badge-red")]}]}]}]}
            
            {:fx/type evaluator-details-pane :stage1 stage1 :stage2 stage2 :stage3 stage3}]}
          
          :else
          {:fx/type :v-box
           :alignment :center
           :v-box/vgrow :always
           :style-class ["card"]
           :spacing 15
           :children
           [{:fx/type :label
             :text "📈 Fit scorecard pending"
             :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
            {:fx/type :label
             :text "Paste a job description and click Evaluate to run the 3-stage agent analysis."
             :wrap-text true
             :style "-fx-text-fill: #64748b; -fx-text-alignment: center;"}]}
          )]}]}))

;; Interview Prep Main View
(defn interview-view [{:keys [state]}]
  (let [status (:interview-status state)
        error (:interview-error state)
        result (:interview-result state)]
    {:fx/type :h-box
     :spacing 20
     :padding 20
     :style "-fx-background-color: #0f172a;"
     :children
     [{:fx/type :v-box
       :pref-width 400
       :spacing 15
       :children
       [{:fx/type :label
         :text "3. Interview Prep Sheet"
         :style-class ["header-title"]}
        {:fx/type :label
         :text "Generate interview strategies mapping likely questions directly to your STAR stories library."
         :wrap-text true
         :style-class ["header-subtitle"]}
        {:fx/type :text-area
         :v-box/vgrow :always
         :text (:interview-jd state)
         :prompt-text "Paste target Job Description here..."
         :on-text-changed #(swap! *state assoc :interview-jd %)
         :style "-fx-font-family: monospace;"}
        {:fx/type :h-box
         :spacing 10
         :alignment :center-left
         :children
         [(if (= status :loading)
            {:fx/type :button :text "Strategizing..." :disable true :style-class ["button-primary"]}
            {:fx/type :button
             :text "Generate Prep Sheet"
             :style-class ["button-primary"]
             :on-action handle-interview-prep})
          (cond
            (= status :loading)
            {:fx/type :label :text "Mapping STAR stories..." :style "-fx-text-fill: #fbbf24;"}
            
            (= status :success)
            {:fx/type :label :text "✅ Prep sheet ready!" :style "-fx-text-fill: #34d399;"}
            
            (= status :error)
            {:fx/type :label :text error :style "-fx-text-fill: #f87171;" :wrap-text true :max-width 250}
            
            :else
            {:fx/type :label :text "Status: Ready" :style "-fx-text-fill: #60a5fa;"})]}]}
      
      {:fx/type :separator
       :orientation :vertical}
      
      {:fx/type :v-box
       :h-box/hgrow :always
       :spacing 15
       :children
       [{:fx/type :label
         :text "STAR Questions & Strategies"
         :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f1f5f9;"}
        (cond
          (= status :loading)
          {:fx/type :v-box
           :alignment :center
           :v-box/vgrow :always
           :style-class ["card"]
           :spacing 15
           :children
           [{:fx/type :label :text "Mapping STAR Stories... ⌛" :style "-fx-font-size: 16px; -fx-text-fill: #fbbf24; -fx-font-weight: bold;"}]}
          
          (= status :success)
          {:fx/type :text-area
           :v-box/vgrow :always
           :editable false
           :text result
           :style "-fx-font-family: sans-serif; -fx-font-size: 13px;"}
          
          :else
          {:fx/type :v-box
           :alignment :center
           :v-box/vgrow :always
           :style-class ["card"]
           :spacing 15
           :children
           [{:fx/type :label
             :text "🧠 Strategy sheet pending"
             :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
            {:fx/type :label
             :text "Paste the job description and click Generate to construct your customized behavioral prep sheet."
             :wrap-text true
             :style "-fx-text-fill: #64748b; -fx-text-alignment: center;"}]}
          )]}]}))

;; Visual Tracker Card
(defn tracker-card [row]
  (let [id (nth row 0 "?")
        date (nth row 1 "?")
        company (nth row 2 "?")
        role (nth row 3 "?")
        score (nth row 4 "?")
        status (nth row 5 "?")
        pdf (nth row 6 "?")
        report-link (nth row 7 "?")
        notes (nth row 8 "")
        has-pdf? (= (str/trim pdf) "✅")
        score-num (try (Double/parseDouble (str/replace score #"/5" "")) (catch Exception _ 0.0))
        score-color (cond (>= score-num 4.0) "badge-green" (>= score-num 3.0) "badge-orange" :else "badge-red")]
    {:fx/type :h-box
     :style-class ["card" "card-hover"]
     :spacing 15
     :alignment :center-left
     :children
     [{:fx/type :v-box
       :alignment :center
       :min-width 45
       :style "-fx-background-color: #334155; -fx-background-radius: 20px; -fx-padding: 5px;"
       :children
       [{:fx/type :label
         :text (str "#" id)
         :style "-fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-font-size: 12px;"}]}
      
      {:fx/type :v-box
       :pref-width 200
       :spacing 3
       :children
       [{:fx/type :label
         :text company
         :style "-fx-font-weight: bold; -fx-text-fill: #f8fafc; -fx-font-size: 14px;"}
        {:fx/type :label
         :text role
         :style "-fx-text-fill: #94a3b8; -fx-font-size: 12px;"}]}
      
      {:fx/type :v-box
       :pref-width 120
       :spacing 3
       :children
       [{:fx/type :label :text "DATE EVALUATED" :style "-fx-font-size: 9px; -fx-text-fill: #475569; -fx-font-weight: bold;"}
        {:fx/type :label :text date :style "-fx-text-fill: #cbd5e1; -fx-font-size: 11px;"}]}

      {:fx/type :v-box
       :pref-width 100
       :spacing 3
       :children
       [{:fx/type :label :text "SCORE" :style "-fx-font-size: 9px; -fx-text-fill: #475569; -fx-font-weight: bold;"}
        {:fx/type :label :text score :style-class ["badge" score-color] :style "-fx-alignment: center;"}]}

      {:fx/type :v-box
       :pref-width 100
       :spacing 3
       :children
       [{:fx/type :label :text "STATUS" :style "-fx-font-size: 9px; -fx-text-fill: #475569; -fx-font-weight: bold;"}
        {:fx/type :label :text status :style-class ["badge" "badge-blue"] :style "-fx-alignment: center;"}]}

      {:fx/type :v-box
       :pref-width 100
       :spacing 3
       :children
       [{:fx/type :label :text "PDF RESUME" :style "-fx-font-size: 9px; -fx-text-fill: #475569; -fx-font-weight: bold;"}
        {:fx/type :label :text (if has-pdf? "✅ Ready" "❌ Pending") :style-class ["badge" (if has-pdf? "badge-green" "badge-red")] :style "-fx-alignment: center;"}]}

      {:fx/type :h-box
       :h-box/hgrow :always
       :alignment :center-right
       :spacing 10
       :children
       [(when-not has-pdf?
          {:fx/type :button
           :text "Mark PDF Ready"
           :style-class ["button-success"]
           :style "-fx-font-size: 11px; -fx-padding: 5px 10px;"
           :on-action (fn [_] (handle-mark-pdf id))})
        (when-not (empty? report-link)
          {:fx/type :button
           :text "View Report"
           :style-class ["button-secondary"]
           :style "-fx-font-size: 11px; -fx-padding: 5px 10px;"
           :on-action (fn [_] (handle-view-report report-link))})]}]}))

;; Main Application Tracker View
(defn tracker-view [{:keys [state]}]
  (let [rows (:tracker-rows state [])]
    {:fx/type :v-box
     :spacing 15
     :padding 20
     :style "-fx-background-color: #0f172a;"
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :children
       [{:fx/type :v-box
         :h-box/hgrow :always
         :spacing 5
         :children
         [{:fx/type :label
           :text "4. Job Application Tracker"
           :style-class ["header-title"]}
          {:fx/type :label
           :text "View, track, and manage your evaluated opportunities entirely local-first."
           :style-class ["header-subtitle"]}]}
        {:fx/type :button
         :text "🔄 Refresh Tracker"
         :style-class ["button-secondary"]
         :on-action (fn [_] (handle-refresh-tracker))}]}
      
      {:fx/type :scroll-pane
       :fit-to-width true
       :v-box/vgrow :always
       :content
       {:fx/type :v-box
        :spacing 10
        :children
        (if (empty? rows)
          [{:fx/type :v-box
            :alignment :center
            :v-box/vgrow :always
            :style-class ["card"]
            :spacing 15
            :children
            [{:fx/type :label
              :text "📭 Tracker is empty"
              :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;"}
             {:fx/type :label
              :text "Run an evaluation on a job description to add entries here."
              :style "-fx-text-fill: #64748b;"}]}]
          (map tracker-card rows))}}]}))

;; Main Window Root View
(defn root-view [state]
  (let [active (:active-tab state :vault)]
    {:fx/type :stage
     :showing true
     :title "Local Headhunter-Agent — Clojure Desktop MAS Console"
     :width 1200
     :height 750
     :scene {:fx/type :scene
             :stylesheets [(get-stylesheet-url)]
             :root {:fx/type :border-pane
                    :left {:fx/type sidebar-view :state state}
                    :center (cond
                              (= active :vault)
                              {:fx/type vault-view :state state}
                              
                              (= active :evaluate)
                              {:fx/type evaluate-view :state state}
                              
                              (= active :interview)
                              {:fx/type interview-view :state state}
                              
                              (= active :tracker)
                              {:fx/type tracker-view :state state}
                              
                              :else
                              {:fx/type vault-view :state state})}}}))

;; JVM Entry Point
(defn -main [& _args]
  (Platform/setImplicitExit true)
  (load-local-data!)
  (fx/mount-renderer
   *state
   (fx/create-renderer
    :middleware (fx/wrap-map-desc #'root-view))))
