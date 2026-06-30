(ns career-ops.daemon.core
  (:require [clojure.java.io :as io])
  (:gen-class :extends java.lang.Runnable))

(defn start-daemon []
  (println "Daemon MCP Server started")
  (let [running? (atom true)]
    (while @running?
      (Thread/sleep 1000)
      (println "Daemon heartbeat: " (java.time.Instant/now))))))

(defn -main [& args]
  (start-daemon))
