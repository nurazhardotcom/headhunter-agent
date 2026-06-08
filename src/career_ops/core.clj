(ns career-ops.core
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [career-ops.evaluator :as eval]
            [career-ops.pdf :as pdf]
            [career-ops.tracker :as tracker]
            [career-ops.profiler :as profiler]
            [career-ops.interview :as interview]))

(defn print-help []
  (println "╔══════════════════════════════════════════════════════════════════╗")
  (println "║      Local Jack & Jill — Clojure/Babashka MAS Edition            ║")
  (println "╚══════════════════════════════════════════════════════════════════╝")
  (println "")
  (println "  Manage and automate your job search pipeline entirely in your terminal.")
  (println "")
  (println "  TASKS")
  (println "    bb profile --extract <file>")
  (println "        Build your Data Vault (Master Profile & STAR Stories) from raw text.")
  (println "")
  (println "    bb evaluate [options] <JD text>")
  (println "        Run the 3-stage MAS pipeline to evaluate a JD against your Data Vault.")
  (println "")
  (println "    bb interview [options] <JD text>")
  (println "        Generate an Interview Prep Sheet using your STAR Stories.")
  (println "")
  (println "    bb pdf <company-name> [options] <JD text>")
  (println "        Tailor your CV and compile a PDF resume via Typst.")
  (println "")
  (println "    bb tracker [command]")
  (println "        Manage the job application tracker database.")
  (println "")
  (println "  OPTIONS")
  (println "    --file <path>    Read JD/Data from a text file instead of inline args")
  (println "    --model <name>   Specify Gemini model (default: gemini-2.5-flash)")
  (println "    --no-save        Do not save the evaluation report file")
  (println "")
  (println "  EXAMPLES")
  (println "    bb profile --extract ./my-linkedin-export.txt")
  (println "    bb evaluate --file ./jds/defence-collective.txt")
  (println "    bb interview --file ./jds/defence-collective.txt")
  (println "    bb tracker list")
  (System/exit 0))

(defn parse-jd-args [args]
  (loop [remaining args
         opts {:save-report? true
               :model-name "gemini-2.5-flash"
               :jd-text ""}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= arg "--file")
          (recur (drop 2 remaining)
                 (assoc opts :file (second remaining)))
          
          (= arg "--model")
          (recur (drop 2 remaining)
                 (assoc opts :model-name (second remaining)))
          
          (= arg "--no-save")
          (recur (rest remaining)
                 (assoc opts :save-report? false))
          
          (str/starts-with? arg "--")
          (do
            (println (str "⚠️  Unknown option: " arg))
            (recur (rest remaining) opts))
          
          :else
          (recur (rest remaining)
                 (update opts :jd-text #(if (empty? %) arg (str % " " arg)))))))))

(defn get-jd-text [opts]
  (let [jd-raw (if (:file opts)
                 (let [f (io/file (:file opts))]
                   (if (.exists f)
                     (str/trim (slurp f))
                     (do (println (str "❌ Error: File not found: " (:file opts)))
                         (System/exit 1))))
                 (:jd-text opts))
        jd-text (str/trim (or jd-raw ""))]
    (if (empty? jd-text)
      (do
        (println "❌ Error: No Job Description provided.")
        (println "   Provide JD text as arguments or use --file <path>.")
        (System/exit 1))
      jd-text)))

(defn profile-cmd [& args]
  (if (and (= (first args) "--extract") (second args))
    (try
      (let [res (profiler/extract-profile! (second args))]
        (println "✅ Successfully extracted Data Vault!")
        (println "📂 Saved to data/master-profile.edn and data/star-stories.edn"))
      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (System/exit 1)))
    (do
      (println "Usage: bb profile --extract <path/to/raw-cv.txt>")
      (System/exit 1))))

(defn evaluate-cmd [& args]
  (let [opts (parse-jd-args args)
        jd-text (get-jd-text opts)]
    (try
      (println "⏳ Running 3-stage MAS pipeline...")
      (let [result (eval/evaluate-jd jd-text
                                     :model-name (:model-name opts)
                                     :save-report? (:save-report? opts))]
        (println "\n==================================================================")
        (println (:report result))
        (println "\n==================================================================")
        (let [summary (:summary result)
              score (:score summary)
              archetype (:archetype summary)
              legitimacy (:legitimacy summary)]
          (println (str "  Score: " score "/5  |  Archetype: " archetype "  |  Legitimacy: " legitimacy))
          (println "==================================================================\n")
          (when (:report-path result)
            (println (str "✅ Report saved: " (:report-path result))))
          (when (and (:save-report? opts) (:summary result))
            (let [filename (last (str/split (:report-path result) #"/"))]
              (tracker/add-entry! {:date (:today result)
                                   :company (:company summary)
                                   :role (:role summary)
                                   :score (:score summary)
                                   :report-link (str "[" (:num result) "](reports/" filename ")")
                                   :notes (str "3-Stage MAS (" (:model-name opts) ")")}))))
        (catch Exception e
          (println (str "❌ Error: " (.getMessage e)))
          (System/exit 1))))))

(defn interview-cmd [& args]
  (let [opts (parse-jd-args args)
        jd-text (get-jd-text opts)]
    (try
      (let [res (interview/prep-interview! jd-text)]
        (println "\n==================================================================")
        (println "  INTERVIEW PREP STRATEGY")
        (println "==================================================================\n")
        (println (:result res))
        (println "\n==================================================================")
        (println (str "✅ Prep Sheet saved: " (:report-path res))))
      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (System/exit 1)))))

(defn parse-pdf-args [args]
  (loop [remaining args
         opts {:company-name "general"
               :model-name "gemini-2.5-flash"
               :jd-text ""}]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)]
        (cond
          (= arg "--file")
          (recur (drop 2 remaining)
                 (assoc opts :file (second remaining)))
          
          (= arg "--model")
          (recur (drop 2 remaining)
                 (assoc opts :model-name (second remaining)))
          
          (str/starts-with? arg "--")
          (do
            (println (str "⚠️  Unknown option: " arg))
            (recur (rest remaining) opts))
          
          :else
          (if (= (:company-name opts) "general")
            (recur (rest remaining) (assoc opts :company-name arg))
            (recur (rest remaining)
                   (update opts :jd-text #(if (empty? %) arg (str % " " arg))))))))))

(defn pdf-cmd [& args]
  (let [opts (parse-pdf-args args)
        jd-raw (if (:file opts)
                 (let [f (io/file (:file opts))]
                   (if (.exists f)
                     (str/trim (slurp f))
                     (do (println (str "❌ Error: File not found: " (:file opts)))
                         (System/exit 1))))
                 (:jd-text opts))
        jd-text (str/trim (or jd-raw ""))]
    (if (empty? jd-text)
      (do
        (println "❌ Error: No Job Description provided for tailoring.")
        (println "   Provide JD text or use --file <path>.")
        (System/exit 1))
      (do
        (println (str "⚡ Tailoring resume for company: " (:company-name opts)))
        (let [pdf-path (pdf/generate-pdf-resume jd-text
                                                :company-name (:company-name opts)
                                                :model-name (:model-name opts))]
          ;; Try to auto-update PDF status in tracker if we find a match
          (when pdf-path
            (let [rows (tracker/parse-rows)
                  match (first (filter (fn [row]
                                         (let [company-cell (nth row 2 "")]
                                           (str/includes? (str/lower-case company-cell)
                                                          (str/lower-case (:company-name opts)))))
                                       rows))]
              (if match
                (tracker/mark-pdf-ready! (first match) pdf-path)
                (println "💡 Note: No matching tracker entry found to mark as PDF Ready (✅).")))))))))

(defn tracker-cmd [& args]
  (cond
    (or (empty? args) (= (first args) "list"))
    (tracker/list-entries)
    
    (and (= (first args) "mark") (second args))
    (tracker/mark-pdf-ready! (second args) "")
    
    :else
    (do
      (println "Usage:")
      (println "  bb tracker list       - List all tracked applications")
      (println "  bb tracker mark <id>  - Mark an application's PDF as ready (✅)"))))

(defn -main [& args]
  (let [command (first args)
        cmd-args (rest args)]
    (cond
      (= command "profile")  (apply profile-cmd cmd-args)
      (= command "evaluate") (apply evaluate-cmd cmd-args)
      (= command "interview") (apply interview-cmd cmd-args)
      (= command "pdf")      (apply pdf-cmd cmd-args)
      (= command "tracker")  (apply tracker-cmd cmd-args)
      (or (nil? command) (= command "help") (= command "--help") (= command "-h")) (print-help)
      :else (do
              (println (str "❌ Error: Unknown command '" command "'"))
              (print-help)))))
