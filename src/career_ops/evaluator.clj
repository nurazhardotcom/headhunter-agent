(ns career-ops.evaluator
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]))

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
  (.format (java.time.format.DateTimeFormatter/ofPattern "dd-MM-yyyy")
           (java.time.ZonedDateTime/now (java.time.ZoneId/of "Asia/Singapore"))))

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

(defn call-gemini [api-key system-prompt user-prompt model-name]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" model-name ":generateContent?key=" api-key)
        payload {:contents [{:parts [{:text system-prompt}
                                     {:text user-prompt}]}]
                 :generationConfig {:temperature 0.5
                                    :maxOutputTokens 8192}}
        response (http/post url
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string payload)
                             :timeout 120000
                             :throw false})]
    (if (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)
            text (-> body :candidates first :content :parts first :text)]
        text)
      (throw (Exception. (str "API call failed with status " (:status response) ": " (:body response)))))))

(def agent1-prompt
  (str "You are career-ops, an AI-powered job search assistant.\n"
       "Evaluate the provided Job Description.\n"
       "Check for Fair Consideration Framework (FCF) legitimacy and general red flags.\n"
       "Output a brief analysis, followed by this EXACT summary block at the very end:\n\n"
       "---SCORE_SUMMARY---\n"
       "COMPANY: <company name or \"Unknown\">\n"
       "ROLE: <role title>\n"
       "SCORE: <decimal, e.g. 4.6>\n"
       "ARCHETYPE: <detected archetype>\n"
       "LEGITIMACY: <High Confidence | Proceed with Caution | Suspicious>\n"
       "---END_SUMMARY---"))

(def agent2-prompt
  (str "You are an expert career strategist.\n"
       "Compare the candidate's Master Profile against the parsed Job Description.\n"
       "Provide a highly critical, 2-3 paragraph Fit Analysis.\n"
       "Identify exact gaps (what they lack) and exact strengths (where they over-index).\n"
       "End with a definitive GO or NO-GO recommendation for applying."))

(def agent3-prompt
  (str "You are a specialized corporate researcher.\n"
       "Generate a concise 'Pre-Interview Cheat Sheet' for the target company and role.\n"
       "Include:\n"
       "1. Business Model & Market Position (How they make money, main competitors).\n"
       "2. Suspected Tech Stack & Operational Reality (Based on JD clues).\n"
       "3. Cold Outreach Strategy: Identify 2 specific job titles the candidate should search for on LinkedIn to send a warm outreach message to (e.g., 'VP of Engineering', 'Director of Product'). Provide a 1-sentence outreach template.\n"))

(defn load-master-profile []
  (let [f (io/file "data/master-profile.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      nil)))

(defn evaluate-jd [jd-text & {:keys [model-name save-report?]
                               :or {model-name "gemini-2.5-flash"
                                    save-report? true}}]
  (let [env (load-env)
        api-key (get env "GEMINI_API_KEY")]
    (if-not api-key
      (do
        (println "❌ Error: GEMINI_API_KEY not found in environment or .env file.")
        (System/exit 1))
      (let [master-profile (load-master-profile)]
        (when-not master-profile
          (println "⚠️ Warning: data/master-profile.edn not found. Run 'bb profile --extract' first for better results."))
        
        (println "\n==================================================================")
        (println "  LOCAL MAS EVALUATOR - 3-STAGE PIPELINE")
        (println "==================================================================\n")
        
        (try
          ;; Stage 1
          (print "🤖 Agent 1/3: Analyzing JD & Legitimacy... ")
          (flush)
          (let [stage1-text (call-gemini api-key agent1-prompt (str "JOB DESCRIPTION:\n" jd-text) model-name)
                summary (parse-summary stage1-text)
                company-name (get summary :company "Unknown")]
            (println "Done.")
            
            ;; Stage 2
            (print "🤖 Agent 2/3: Benchmarking & Fit Analysis... ")
            (flush)
            (let [stage2-text (call-gemini api-key agent2-prompt
                                           (str "CANDIDATE MASTER PROFILE:\n" (pr-str master-profile) "\n\nJD CONTEXT:\n" stage1-text)
                                           model-name)]
              (println "Done.")
              
              ;; Stage 3
              (print "🤖 Agent 3/3: Generating Company Deep Dive Memo... ")
              (flush)
              (let [stage3-text (call-gemini api-key agent3-prompt
                                             (str "COMPANY: " company-name "\nJD:\n" jd-text)
                                             model-name)]
                (println "Done.\n")
                
                (let [full-report (str "### Stage 1: JD Analysis\n\n" stage1-text "\n\n"
                                       "---\n### Stage 2: Fit Analysis & Go/No-Go\n\n" stage2-text "\n\n"
                                       "---\n### Stage 3: Pre-Interview Cheat Sheet\n\n" stage3-text)]
                  
                  (println full-report)
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
                              clean-eval (str/trim (str/replace full-report #"(?s)---SCORE_SUMMARY---.*?---END_SUMMARY---" ""))
                              report-content (str "# Evaluation: " company " — " role "\n\n"
                                                  "**Date:** " today "\n"
                                                  "**Archetype:** " archetype "\n"
                                                  "**Score:** " score "/5\n"
                                                  "**Legitimacy:** " legitimacy "\n"
                                                  "**PDF:** pending\n"
                                                  "**Tool:** Babashka MAS Pipeline\n\n"
                                                  "---\n\n"
                                                  clean-eval "\n")]
                          (spit report-path report-content)
                          (println (str "✅ Report saved: " report-path))
                          {:summary summary :report-path report-path :num num :today today})
                        {:summary summary}))
                    (do
                      (println "⚠️ Warning: Could not parse machine-readable summary from Stage 1.")
                      {})))))))
          (catch Exception e
            (println "\n❌ Error in MAS Pipeline:" (.getMessage e))
            (System/exit 1)))))))
