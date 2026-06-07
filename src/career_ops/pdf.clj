(ns career-ops.pdf
  (:require [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [career-ops.evaluator :as eval]))

(defn get-candidate-name []
  (let [profile-file (io/file "config/profile.yml")]
    (if (.exists profile-file)
      (try
        (let [profile (yaml/parse-string (slurp profile-file))]
          (get-in profile [:candidate :full_name] "candidate"))
        (catch Exception _
          "candidate"))
      "candidate")))

(defn generate-tailored-json [api-key cv-content jd-content model-name]
  (let [system-prompt (str "You are a professional ATS resume-tailoring agent.\n"
                           "Your task is to take the candidate's CV and tailor it for the target Job Description (JD).\n"
                           "Follow these rules:\n"
                           "1. Inject relevant keywords from the JD into the Professional Summary and Work Experience bullets naturally.\n"
                           "2. Do NOT invent any skills, jobs, or achievements. Maintain 100% factual accuracy.\n"
                           "3. Reorder experience bullet points to prioritize achievements most relevant to the JD.\n"
                           "4. Output MUST be a single JSON object conforming to this schema:\n\n"
                           "{\n"
                           "  \"name\": \"Alex Chen\",\n"
                           "  \"contact\": [\"alex@example.com\", \"linkedin.com/in/alexchen\", \"alexchen.dev\", \"Austin, TX\"],\n"
                           "  \"summary\": \"3-4 lines of professional summary tailored with keywords\",\n"
                           "  \"competencies\": [\"Tag 1\", \"Tag 2\", \"Tag 3\", \"Tag 4\", \"Tag 5\"], // Choose 5 relevant terms\n"
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
                           "      \"desc\": \"Optional details or grades\"\n"
                           "    }\n"
                           "  ]\n"
                           "}")
        user-prompt (str "CANDIDATE CV:\n\n" cv-content "\n\n"
                          "TARGET JOB DESCRIPTION:\n\n" jd-content)
        url (str "https://generativelanguage.googleapis.com/v1beta/models/" model-name ":generateContent?key=" api-key)
        payload {:contents [{:parts [{:text system-prompt}
                                     {:text user-prompt}]}]
                 :generationConfig {:temperature 0.2
                                    :responseMimeType "application/json"
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

(defn compile-pdf [company-name]
  (let [company-slug (eval/slugify company-name)
        candidate-name (get-candidate-name)
        candidate-slug (eval/slugify candidate-name)
        today (eval/today-str)
        output-dir "output"
        _ (.mkdirs (io/file output-dir))
        pdf-filename (str "cv-" candidate-slug "-" company-slug "-" today ".pdf")
        pdf-path (str output-dir "/" pdf-filename)
        ; Compile typst
        result (proc/sh "typst" "compile" "resume.typ" pdf-path)]
    (if (= 0 (:exit result))
      (do
        (println (str "🎉 Success! Tailored PDF generated at: " pdf-path))
        pdf-path)
      (do
        (println "❌ Typst compilation failed:")
        (println (:err result))
        nil))))

(defn generate-pdf-resume [jd-text & {:keys [company-name model-name]
                                      :or {company-name "general"
                                           model-name "gemini-2.5-flash"}}]
  (let [env (eval/load-env)
        api-key (get env "GEMINI_API_KEY")]
    (if-not api-key
      (do
        (println "❌ Error: GEMINI_API_KEY not found in environment or .env file.")
        (System/exit 1))
      (do
        (println "📂 Loading CV source of truth...")
        (let [cv-content (eval/read-file-with-fallback "cv.md" "cv.md")]
          (println "🤖 Requesting tailored JSON CV from Gemini...")
          (try
            (let [tailored-json-str (generate-tailored-json api-key cv-content jd-text model-name)
                  _ (spit "resume_data.json" tailored-json-str)]
              (println "✔ Tailored data written to resume_data.json.")
              (println "⚡ Compiling resume.typ to PDF via Typst...")
              (compile-pdf company-name))
            (catch Exception e
              (println "❌ Error during PDF resume generation:" (.getMessage e))
              (System/exit 1))))))))
