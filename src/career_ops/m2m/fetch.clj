(ns career-ops.m2m.fetch
  "Fetch and validate JSON-LD job postings from employer M2M endpoints.

  Uses the existing HTTP client infrastructure from babashka.http-client.
  Validates the response against the M2M JSON-LD schema."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [career-ops.m2m.schema :as schema]
            [career-ops.m2m.crypto :as crypto]))

(defn job-posting
  "Fetch a job posting from a URL.
  url: the JSON-LD job posting URL.
  Returns a normalized posting map with namespaced keys.

  The response must:
  - Return HTTP 200 with Content-Type: application/ld+json
  - Contain a valid m2m:JobPosting or schema:JobPosting
  - Pass schema validation"
  [url & {:keys [verify-tls?] :or {verify-tls? true}}]
  (let [response (http/get url
                           {:headers {"Accept" "application/ld+json"
                                      "User-Agent" "headhunter-agent/1.0"}
                            :throw false
                            :verify verify-tls?})]
    (when-not (= 200 (:status response))
      (throw (ex-info (str "Failed to fetch job posting: HTTP " (:status response))
                      {:status (:status response) :url url})))
    (let [content-type (get-in response [:headers "content-type"] "")
          body (:body response)
          _ (when-not (or (str/includes? content-type "application/ld+json")
                          (str/includes? content-type "application/json"))
              (println (str "⚠️ Unexpected Content-Type: " content-type
                            " (expected application/ld+json)")))
          parsed (schema/normalize-job-posting body)
          validation (schema/validate-job-posting parsed)]
      (when-not (:valid? validation)
        (throw (ex-info "Job posting failed schema validation"
                        {:errors (:errors validation) :posting parsed})))
      (let [posting (:posting validation)]
        {:title (or (:schema/title posting) "Untitled")
         :description (or (:schema/description posting) "")
         :company (or (:schema/name posting) "Unknown")
         :skills (or (:schema/skills posting) [])
         :apply-endpoint (:m2m/applyEndpoint posting)
         :public-key (:m2m/publicKey posting)
         :protocol-version (:m2m/protocolVersion posting)
         :raw posting}))))

(defn list-postings
  "List available job postings from an employer's M2M listing endpoint.
  Returns a sequence of posting summaries."
  [base-url]
  (let [list-url (str base-url "/jobs")
        response (http/get list-url
                           {:headers {"Accept" "application/ld+json"}
                            :throw false})]
    (if (= 200 (:status response))
      (let [parsed (json/parse-string (:body response) true)]
        (if (vector? parsed)
          (map #(select-keys (schema/canonicalize-keys %)
                             [:schema/title :schema/datePosted :@id])
               parsed)
          []))
      (throw (ex-info (str "Failed to list postings: HTTP " (:status response))
                      {:status (:status response) :url list-url})))))
