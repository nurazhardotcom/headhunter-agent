(ns career-ops.core
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [career-ops.evaluator :as eval]
            [career-ops.pdf :as pdf]
            [career-ops.tracker :as tracker]))

(defn print-help []
  (println "╔══════════════════════════════════════════════════════════════════╗")
  (println "║           career-ops — Clojure/Babashka Edition                  ║")
  (println "╚══════════════════════════════════════════════════════════════════╝")
  (println "")
  (println "  Manage and automate your job search pipeline entirely in Clojure.")
  (println "")
  (println "  TASKS")
  (println "    bb evaluate [options] <JD text>")
  (println "        Evaluate a job description against your CV using Gemini.")
  (println "")
  (println "    bb pdf <company-name> [options] <JD text>")
  (println "        Tailor your CV and compile a PDF resume via Typst.")
  (println "")
  (println "    bb tracker [command]")
  (println "        Manage the job application tracker database.")
  (println "")
  (println "  OPTIONS")
  (println "    --file <path>    Read JD from a text file instead of inline args")
  (println "    --model <name>   Specify Gemini model (default: gemini-2.5-flash)")
  (println "    --no-save        Do not save the evaluation report file")
  (println "")
  (println "  EXAMPLES")
  (println "    bb evaluate --file ./jds/defence-collective.txt")
  (println "    bb pdf \"Defence Collective\" --file ./jds/defence-collective.txt")
  (println "    bb tracker list")
  (System/exit 0))

(defn parse-evaluate-args [args]
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

(defn evaluate-cmd [& args]
  (let [opts (parse-evaluate-args args)
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
        (println "❌ Error: No Job Description provided.")
        (println "   Provide JD text as arguments or use --file <path>.")
        (System/exit 1))
      (let [result (eval/evaluate-jd jd-text
                                     :model-name (:model-name opts)
                                     :save-report? (:save-report? opts))]
        ;; If report was saved and we got a score summary, auto-log to tracker
        (when (and (:save-report? opts) (:summary result))
          (let [summary (:summary result)
                filename (last (str/split (:report-path result) #"/"))]
            (tracker/add-entry! {:date (:today result)
                                 :company (:company summary)
                                 :role (:role summary)
                                 :score (:score summary)
                                 :report-link (str "[" (:num result) "](reports/" filename ")")
                                 :notes (str "Evaluated with Gemini " (:model-name opts))})))))))

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
      (= command "evaluate") (apply evaluate-cmd cmd-args)
      (= command "pdf")      (apply pdf-cmd cmd-args)
      (= command "tracker")  (apply tracker-cmd cmd-args)
      (or (nil? command) (= command "help") (= command "--help") (= command "-h")) (print-help)
      :else (do
              (println (str "❌ Error: Unknown command '" command "'"))
              (print-help)))))
