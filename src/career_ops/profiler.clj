(ns career-ops.profiler
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

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

(def deep-profiling-prompt
  (str "You are a specialized Career Profiling Agent.\n"
       "Your goal is to parse raw, unstructured candidate data (LinkedIn export, raw CV text, etc.) and extract a highly structured data vault.\n"
       "You must output ONLY valid JSON format containing two top-level keys: \"master_profile\" and \"star_stories\".\n\n"
       "1. \"master_profile\": Extract core identity, contact info, total years of experience, core technical skills (array), infrastructure/security skills (array), certifications (array), and education (array).\n"
       "2. \"star_stories\": Extract 8-12 distinct professional stories from their work history. Each story must follow the STAR format:\n"
       "   - \"title\": Short descriptive title.\n"
       "   - \"situation\": Context and background.\n"
       "   - \"task\": The challenge or goal.\n"
       "   - \"action\": The specific actions taken by the candidate.\n"
       "   - \"result\": The measurable impact or outcome.\n"
       "   - \"tags\": Array of skills/keywords related to the story.\n\n"
       "Output strictly as a JSON object, no markdown formatting outside of the JSON block."))

(defn call-gemini [api-key system-prompt user-prompt model-name]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" model-name ":generateContent?key=" api-key)
        payload {:contents [{:parts [{:text system-prompt}
                                     {:text user-prompt}]}]
                 :generationConfig {:temperature 0.2
                                    :maxOutputTokens 8192}}
        response (http/post url
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string payload)
                             :timeout 120000
                             :throw false})]
    (if (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)
            text (-> body :candidates first :content :parts first :text)
            clean-text (-> text
                           (str/replace #"```json" "")
                           (str/replace #"```" "")
                           str/trim)]
        (json/parse-string clean-text true))
      (throw (ex-info "API call failed" {:status (:status response) :body (:body response)})))))

(defn extract-profile! [file-path]
  (let [env (load-env)
        api-key (get env "GEMINI_API_KEY")]
    (if-not api-key
      (throw (ex-info "GEMINI_API_KEY not found in environment or .env file" {}))
      (let [f (io/file file-path)]
        (if-not (.exists f)
          (throw (ex-info (str "File not found: " file-path) {}))
          (let [raw-data (slurp f)
                result (call-gemini api-key deep-profiling-prompt raw-data "gemini-2.5-flash")]
            (io/make-parents "data/master-profile.edn")
            (with-open [w (io/writer "data/master-profile.edn")]
              (binding [*out* w]
                (pprint/pprint (:master_profile result))))
            (with-open [w (io/writer "data/star-stories.edn")]
              (binding [*out* w]
                (pprint/pprint (:star_stories result))))
            {:status :success
             :master-profile (:master_profile result)
             :star-stories (:star_stories result)}))))))
