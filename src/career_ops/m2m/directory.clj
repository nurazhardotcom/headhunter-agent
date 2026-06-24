(ns career-ops.m2m.directory
  "Optional Babashka-based directory service for M2M endpoint discovery.

  A lightweight HTTP server that aggregates employer M2M endpoint registrations.
  Serves as a discovery fallback when DNS is unavailable.

  Endpoints:
    POST /v1/register  — Register an employer M2M endpoint
    GET  /v1/search    — Search registered endpoints
    GET  /v1/health    — Health check"
  (:require [cheshire.core :as json]
            [babashka.http-client :as http]))

(defonce registry (atom {}))

(defn register
  "Register an employer M2M endpoint.
  entry: {:domain \"...\" :endpoint \"...\" :public-key \"...\" :name \"...\" :tags [...]}"
  [entry]
  (let [normalized {:domain (:domain entry)
                    :endpoint (:endpoint entry)
                    :public-key (:public-key entry)
                    :name (:name entry (str "Unknown"))
                    :tags (:tags entry [])
                    :registered-at (.toString (java.time.Instant/now))}]
    (swap! registry assoc (:domain entry) normalized)
    normalized))

(defn search
  "Search registered endpoints by query string.
  Matches against name, domain, and tags."
  [query]
  (let [q (clojure.string/lower-case (or query ""))]
    (if (clojure.string/blank? q)
      (vals @registry)
      (filter (fn [entry]
                (or (clojure.string/includes? (clojure.string/lower-case (:name entry)) q)
                    (clojure.string/includes? (clojure.string/lower-case (:domain entry)) q)
                    (some #(clojure.string/includes? (clojure.string/lower-case %) q) (:tags entry))))
              (vals @registry)))))

(defn start-server
  "Start the Babashka HTTP directory server.
  opts: {:port 8080}"
  [& {:keys [port] :or {port 8080}}]
  (println (str "  Listening on 0.0.0.0:" port))
  (println "  POST /v1/register  — Register an endpoint")
  (println "  GET  /v1/search    — Search endpoints (?q=...)")
  (println "  GET  /v1/health    — Health check")
  (println "❌ Directory server requires babashka.http-server")
  (println "   Usage: bb -m career-ops.m2m.directory/start-server")
  (println "   Or use the :m2m alias with babashka."))
