(ns career-ops.m2m.registry
  "Decentralized endpoint discovery for the M2M protocol.

  Primary: DNS TXT record lookup on _m2m-apply.<domain>
  Fallback: HTTP query to a public directory service.

  DNS record format:
    _m2m-apply.example.com.  IN  TXT  \"m2m-p1;https://...;key=<b64>;\""
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(def ^:const dns-prefix "_m2m-apply.")
(def ^:const default-directory "https://directory.m2m-apply.org/v1/search")

(defn- parse-txt-record
  "Parse a single DNS TXT record into {:endpoint ... :public-key ... :version ...}.
  Expected format: \"m2m-p<version>;<base-url>;key=<base64-pubkey>;\""
  [record]
  (let [parts (str/split record #";")
        version-part (first parts)
        endpoint (second parts)
        key-part (some #(when (str/starts-with? % "key=") (subs % 4)) parts)]
    (when (and (str/starts-with? version-part "m2m-p") endpoint key-part)
      {:version (str/replace version-part "m2m-p" "")
       :endpoint endpoint
       :public-key key-part})))

(defn- dns-lookup
  "Perform a DNS TXT lookup for the given domain.
  Falls back to shell dig if Java DNS is unavailable."
  [domain]
  (try
    (let [lookup (javax.naming.directory.InitialDirContext.
                  (doto (java.util.Hashtable.)
                    (.put "java.naming.factory.initial"
                          "com.sun.jndi.dns.DnsContextFactory")
                    (.put "java.naming.provider.url" "dns:")))
          attrs (.getAttributes lookup (str dns-prefix domain) (into-array ["TXT"]))
          attr (.get attrs "TXT")]
      (when attr
        (str/join "" (.getAll (.get attr 0)))))
    (catch Exception _
      (try
        (let [{:keys [exit out]} (shell/sh "dig" "+short" "TXT" (str dns-prefix domain))]
          (when (zero? exit)
            (str/trim out)))
        (catch Exception _
          nil)))))

(defn discover-dns
  "Discover an employer's M2M endpoint via DNS TXT record.
  domain: the employer's domain (e.g. \"employer.example.com\").
  Returns {:endpoint url :public-key b64 :version str} or nil."
  [domain]
  (let [record (dns-lookup domain)]
    (when record
      (parse-txt-record record))))

(defn discover-directory
  "Discover employers via the public directory service.
  query: free-text search string (e.g. \"platform engineer singapore\").
  Returns a seq of {:domain ... :endpoint ... :public-key ...}."
  [query & {:keys [directory-url] :or {directory-url default-directory}}]
  (try
    (let [response (http/get directory-url
                             {:query-params {"q" query}
                              :headers {"Accept" "application/json"}
                              :throw false})]
      (if (= 200 (:status response))
        (json/parse-string (:body response) true)
        (do (println (str "⚠️ Directory service returned " (:status response)))
            [])))
    (catch Exception e
      (println (str "⚠️ Directory service unavailable: " (.getMessage e)))
      [])))

(defn discover
  "Discover an employer's M2M endpoint.
  Tries DNS first, falls back to directory search.
  domain: the employer's domain name.
  Returns {:endpoint url :public-key b64} or nil."
  [domain]
  (or (discover-dns domain)
      (first (discover-directory domain))))
