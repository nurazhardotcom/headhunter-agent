(ns career-ops.m2m.schema
  "JSON-LD schema validation and canonicalization for the M2M protocol.

  Validates job postings, candidate profiles, and application packages
  against the M2M JSON-LD context and structure rules."
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

(def ^:const m2m-context
  "The M2M JSON-LD @context definition."
  {"schema" "https://schema.org/"
   "m2m" "https://m2m-apply.org/ns/v1#"
   "xsd" "http://www.w3.org/2001/XMLSchema#"})

(def ^:const m2m-types
  "Recognized M2M @type values."
  #{"m2m:JobPosting" "m2m:CandidateProfile" "m2m:ApplicationPackage" "m2m:Acknowledgment"})

(def ^:const required-job-posting-fields
  "Required fields for a valid M2M job posting."
  [:schema/title :schema/description :m2m/applyEndpoint :m2m/publicKey])

(def ^:const required-application-fields
  "Required fields for a valid application package."
  [:m2m/protocolVersion :m2m/timestamp :m2m/jobPosting :m2m/candidate :m2m/attachments :m2m/signature])

(defn infer-ns
  "Infer the namespace keyword from a JSON-LD key.
  schema:title → :schema/title, m2m:publicKey → :m2m/publicKey"
  [k]
  (if (str/includes? (name k) ":")
    (let [[ns name] (str/split (name k) #":" 2)]
      (keyword ns name))
    k))

(defn canonicalize-keys
  "Convert JSON-LD string keys to Clojure namespaced keywords."
  [m]
  (into {} (map (fn [[k v]]
                  [(infer-ns (keyword k))
                   (if (map? v) (canonicalize-keys v) v)])
                m)))

(defn validate-structure
  "Validate a parsed map against expected M2M structure.
  Returns {:valid? true} or {:valid? false :errors [...]}."
  [data required-fields]
  (let [missing (remove (fn [fld]
                          (let [v (get data fld)]
                            (and v (not (empty? v))))
                          required-fields))]
    (if (empty? missing)
      {:valid? true}
      {:valid? false
       :errors [(str "Missing required fields: " (pr-str missing))]})))

(defn validate-job-posting
  "Validate a parsed job posting map."
  [posting]
  (let [normalized (canonicalize-keys posting)
        structure (validate-structure normalized required-job-posting-fields)]
    (if-not (:valid? structure)
      structure
      (let [errors (atom [])]
        (when (str/blank? (:schema/title normalized))
          (swap! errors conj "title is blank"))
        (when (str/blank? (:m2m/applyEndpoint normalized))
          (swap! errors conj "applyEndpoint is blank"))
        (when-not (re-matches #"^https?://" (str (:m2m/applyEndpoint normalized)))
          (swap! errors conj "applyEndpoint must be a valid HTTP(S) URL"))
        (when-not (and (:m2m/publicKey normalized) (> (count (str (:m2m/publicKey normalized))) 20))
          (swap! errors conj "publicKey appears invalid (too short)"))
        (if (empty? @errors)
          {:valid? true :posting normalized}
          {:valid? false :errors @errors})))))

(defn validate-application-package
  "Validate a parsed application package map."
  [pkg]
  (let [normalized (canonicalize-keys pkg)
        structure (validate-structure normalized required-application-fields)]
    (if-not (:valid? structure)
      structure
      (let [errors (atom [])]
        (when-not (= "1.0.0" (:m2m/protocolVersion normalized))
          (swap! errors conj "unsupported protocol version"))
        (when (empty? (:m2m/attachments normalized))
          (swap! errors conj "at least one attachment required"))
        (when-not (:m2m/signature normalized)
          (swap! errors conj "signature is required"))
        (if (empty? @errors)
          {:valid? true :package normalized}
          {:valid? false :errors @errors})))))

(defn normalize-job-posting
  "Parse a JSON-LD job posting string into a Clojure map with
  namespaced keywords and the M2M @context applied."
  [json-str]
  (let [parsed (json/parse-string json-str true)]
    (canonicalize-keys parsed)))

(defn normalize-application-package
  "Parse a JSON-LD application package string into a Clojure map."
  [json-str]
  (let [parsed (json/parse-string json-str true)]
    (canonicalize-keys parsed)))
