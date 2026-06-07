(ns career-ops.tracker
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def tracker-file "data/applications.md")

(defn init-tracker! []
  (let [f (io/file tracker-file)]
    (when-not (.exists f)
      (io/make-parents f)
      (spit f (str "# Applications Tracker\n\n"
                   "| # | Date | Company | Role | Score | Status | PDF | Report | Notes |\n"
                   "|---|---|---|---|---|---|---|---|---|\n")))))

(defn parse-rows []
  (init-tracker!)
  (let [lines (str/split-lines (slurp tracker-file))
        data-lines (->> lines
                        (drop 3) ;; Skip title, header, separator
                        (map str/trim)
                        (filter #(str/starts-with? % "|")))]
    (for [line data-lines]
      (let [parts (->> (str/split line #"\|")
                       (map str/trim)
                       (drop 1) ;; Drop first empty split
                       (butlast))] ;; Drop last empty split
        (vec parts)))))

(defn next-id []
  (let [rows (parse-rows)]
    (if (empty? rows)
      1
      (let [ids (keep #(try (Integer/parseInt (first %)) (catch Exception _ nil)) rows)]
        (if (empty? ids)
          1
          (inc (apply max ids)))))))

(defn add-entry! [{:keys [date company role score status pdf report-link notes]
                   :or {status "Evaluated"
                        pdf "❌"
                        notes ""}}]
  (init-tracker!)
  (let [id (next-id)
        row (str "| " id " | " date " | " company " | " role " | " score "/5 | " status " | " pdf " | " report-link " | " notes " |\n")]
    (spit tracker-file row :append true)
    (println (str "📊 Added to tracker: " company " — " role " (Score: " score "/5)"))))

(defn list-entries []
  (let [rows (parse-rows)]
    (if (empty? rows)
      (println "📭 Tracker is empty. Add evaluations to begin.")
      (do
        (println "\n==================================================================================================")
        (println "  CAREER-OPS PIPELINE TRACKER")
        (println "==================================================================================================")
        (printf "%-4s | %-10s | %-25s | %-35s | %-6s | %-10s\n" "ID" "Date" "Company" "Role" "Score" "Status")
        (println "--------------------------------------------------------------------------------------------------")
        (doseq [row rows]
          (let [id (nth row 0 "?")
                date (nth row 1 "?")
                company (nth row 2 "?")
                role (nth row 3 "?")
                score (nth row 4 "?")
                status (nth row 5 "?")]
            (printf "%-4s | %-10s | %-25.25s | %-35.35s | %-6s | %-10s\n"
                    id date company role score status)))
        (println "==================================================================================================\n")))))

(defn mark-pdf-ready! [id pdf-path]
  (init-tracker!)
  (let [lines (str/split-lines (slurp tracker-file))
        header-part (take 3 lines)
        data-part (drop 3 lines)
        updated-data (map (fn [line]
                            (if (str/starts-with? (str/trim line) "|")
                              (let [parts (str/split line #"\|")
                                    row-id (str/trim (second parts))]
                                (if (= (str id) row-id)
                                  ;; Inject pdf-path or checkmark into PDF column (index 7 in the split parts, which is index 6 in 0-indexed)
                                  ;; Let's inspect parts:
                                  ;; index 0: "" (before first |)
                                  ;; index 1: id
                                  ;; index 2: date
                                  ;; index 3: company
                                  ;; index 4: role
                                  ;; index 5: score
                                  ;; index 6: status
                                  ;; index 7: pdf (e.g. ❌)
                                  ;; index 8: report-link
                                  ;; index 9: notes
                                  (let [updated-parts (assoc (vec parts) 7 " ✅ ")]
                                    (str/join "|" updated-parts))
                                  line))
                              line))
                          data-part)]
    (spit tracker-file (str (str/join "\n" (concat header-part updated-data)) "\n"))
    (println (str "✔ Marked ID " id " as PDF Ready (✅)"))))
