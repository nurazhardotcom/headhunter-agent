(ns career-ops.evaluator
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn load-env []
  (let [env-file (io/file ".env")
        local-env (if (.exists env-file)
                    (with-open [rdr (io/reader env-file)]
                      (->> (line-seq rdr)
                           (map str/trim)
                           (filter #(not (str/starts-with? % "#")))
                           (filter #(str/includes? % "="))
                           (map #(str/split % #"=" 2))
                           (into {})))
                    {})]
    (merge (into {} (System/getenv)) local-env)))

(defn read-file-with-fallback [path label]
  (let [f (io/file path)]
    (if (.exists f)
      (str/trim (slurp f))
      (do
        (println (str "⚠️  Warning: " label " not found at " path))
        (str "[" label " not found - skipping]")))))

(defn slugify [s]
  (-> s
      (str/lower-case)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn next-report-num [reports-dir]
  (let [dir (io/file reports-dir)]
    (if-not (.exists dir)
      "001"
      (let [files (->> (.listFiles dir)
                       (map #(.getName %))
                       (filter #(re-matches #"^\d{3}-.*" %))
                       (map #(Integer/parseInt (subs % 0 3))))]
        (if (empty? files)
          "001"
          (format "%03d" (inc (apply max files))))))))

(defn today-str []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
           (java.time.LocalDate/now)))

(defn parse-summary [evaluation-text]
  (when-let [summary-block (re-find #"(?s)---SCORE_SUMMARY---(.*?)---END_SUMMARY---" evaluation-text)]
    (let [lines (str/split-lines (nth summary-block 1))
          pairs (->> lines
                     (map str/trim)
                     (filter #(str/includes? % ":"))
                     (map #(str/split % #":" 2))
                     (map (fn [[k v]] [(str/trim k) (str/trim v)]))
                     (into {}))]
      {:company (get pairs "COMPANY" "Unknown")
       :role (get pairs "ROLE" "Unknown")
       :score (get pairs "SCORE" "?")
       :archetype (get pairs "ARCHETYPE" "Unknown")
       :legitimacy (get pairs "LEGITIMACY" "Unknown")})))

(defn call-gemini [api-key system-prompt jd-text model-name]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" model-name ":generateContent?key=" api-key)
        payload {:contents [{:parts [{:text system-prompt}
                                     {:text (str "\n\nJOB DESCRIPTION TO EVALUATE:\n\n" jd-text)}]}]
                 :generationConfig {:temperature 0.5
                                    :maxOutputTokens 8192
                                    :thinkingConfig {:thinkingBudget 0}}}
        response (http/post url
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string payload)
                             :timeout 60000
                             :throw false})]
    (if (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)
            candidates (:candidates body)
            first-candidate (first candidates)
            content (:content first-candidate)
            parts (:parts content)
            first-part (first parts)]
        (:text first-part))
      (throw (Exception. (str "API call failed with status " (:status response) ": " (:body response)))))))

(defn evaluate-jd [jd-text & {:keys [model-name save-report?]
                               :or {model-name "gemini-2.5-flash"
                                    save-report? true}}]
  (let [env (load-env)
        api-key (get env "GEMINI_API_KEY")]
    (if-not api-key
      (do
        (println "❌ Error: GEMINI_API_KEY not found in environment or .env file.")
        (println "   Please create a .env file in this directory and add:")
        (println "   GEMINI_API_KEY=your_actual_api_key_here")
        (System/exit 1))
      (do
        (println "📂 Loading context files...")
        (let [shared (read-file-with-fallback "modes/_shared.md" "modes/_shared.md")
              oferta (read-file-with-fallback "modes/oferta.md" "modes/oferta.md")
              cv (read-file-with-fallback "cv.md" "cv.md")
              profile-yml (read-file-with-fallback "config/profile.yml" "config/profile.yml")
              profile-md (read-file-with-fallback "modes/_profile.md" "modes/_profile.md")
              
              system-prompt (str "You are career-ops, an AI-powered job search assistant.\n"
                                 "You evaluate job offers against the user's CV using a structured A-G scoring system.\n\n"
                                 "Your evaluation methodology is defined below. Follow it exactly.\n\n"
                                 "=======================================================\n"
                                 "SYSTEM CONTEXT (_shared.md)\n"
                                 "=======================================================\n"
                                 shared "\n\n"
                                 "=======================================================\n"
                                 "EVALUATION MODE (oferta.md)\n"
                                 "=======================================================\n"
                                 oferta "\n\n"
                                 "=======================================================\n"
                                 "CANDIDATE RESUME (cv.md)\n"
                                 "=======================================================\n"
                                 cv "\n\n"
                                 "=======================================================\n"
                                 "CANDIDATE PROFILE & TARGETS (config/profile.yml)\n"
                                 "=======================================================\n"
                                 profile-yml "\n\n"
                                 "=======================================================\n"
                                 "USER ARCHETYPES & NARRATIVE (_profile.md)\n"
                                 "=======================================================\n"
                                 profile-md "\n\n"
                                 "=======================================================\n"
                                 "IMPORTANT OPERATING RULES FOR THIS CLI SESSION\n"
                                 "=======================================================\n"
                                 "1. You do NOT have access to WebSearch, Playwright, or file writing tools.\n"
                                 "   - For Block D (Comp research): provide salary estimates based on your training data, clearly noted as estimates.\n"
                                 "   - For Block G (Legitimacy): analyze the JD text only; skip URL/page freshness checks.\n"
                                 "   - Post-evaluation file saving is handled by the script, not by you.\n"
                                 "2. Generate Blocks A through G in full, in English, unless the JD is in another language.\n"
                                 "3. When generating markdown tables, keep the header separator rows extremely short, using exactly three hyphens (e.g., |---|---|). Do NOT output long lines of hyphens to align columns, as this causes token limits to be exceeded.\n"
                                 "4. At the very end, output a machine-readable summary block in this exact format:\n\n"
                                 "---SCORE_SUMMARY---\n"
                                 "COMPANY: <company name or \"Unknown\">\n"
                                 "ROLE: <role title>\n"
                                 "SCORE: <global score as decimal, e.g. 3.8>\n"
                                 "ARCHETYPE: <detected archetype>\n"
                                 "LEGITIMACY: <High Confidence | Proceed with Caution | Suspicious>\n"
                                 "---END_SUMMARY---")]
          (println (str "🤖 Calling Gemini (" model-name ")..."))
          (try
            (let [eval-text (call-gemini api-key system-prompt jd-text model-name)
                  summary (parse-summary eval-text)]
              (println "\n==================================================================")
              (println "  CAREER-OPS EVALUATION - Clojure/Babashka Edition")
              (println "==================================================================\n")
              (println eval-text)
              (println "\n==================================================================")
              
              (if summary
                (let [{:keys [company role score archetype legitimacy]} summary]
                  (println (str "  Score: " score "/5  |  Archetype: " archetype "  |  Legitimacy: " legitimacy))
                  (println "==================================================================\n")
                  
                  (if save-report?
                    (let [reports-dir "reports"
                          _ (.mkdirs (io/file reports-dir))
                          num (next-report-num reports-dir)
                          today (today-str)
                          company-slug (slugify company)
                          filename (str num "-" company-slug "-" today ".md")
                          report-path (str reports-dir "/" filename)
                          clean-eval (str/trim (str/replace eval-text #"(?s)---SCORE_SUMMARY---.*?---END_SUMMARY---" ""))
                          report-content (str "# Evaluation: " company " — " role "\n\n"
                                              "**Date:** " today "\n"
                                              "**Archetype:** " archetype "\n"
                                              "**Score:** " score "/5\n"
                                              "**Legitimacy:** " legitimacy "\n"
                                              "**PDF:** pending\n"
                                              "**Tool:** Babashka Gemini (" model-name ")\n\n"
                                              "---\n\n"
                                              clean-eval "\n")]
                      (spit report-path report-content)
                      (println (str "✅ Report saved: " report-path))
                      {:summary summary :report-path report-path :num num :today today})
                    {:summary summary}))
                (do
                  (println "⚠️ Warning: Could not parse machine-readable summary from evaluation.")
                  {})))
            (catch Exception e
              (println "❌ Error calling Gemini API:" (.getMessage e))
              (System/exit 1))))))))
