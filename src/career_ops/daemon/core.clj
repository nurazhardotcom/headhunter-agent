(ns career-ops.daemon.core
  "Daemon MCP Server — A personal API for the headhunter-agent system.
  
  Implements the Model Context Protocol (MCP) over HTTP using JSON-RPC 2.0.
  Exposes tools that describe the system's architecture, modules, and capabilities.
  
  Usage:
    bb daemon                          Start the server (default port 8081)
    bb daemon serve --port 9090        Start on custom port
    
  Query (from any MCP client):
    curl -X POST http://localhost:8081 \\
      -H \"Content-Type: application/json\" \\
      -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}'
    
    curl -X POST http://localhost:8081 \\
      -H \"Content-Type: application/json\" \\
      -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"get_about\"},\"id\":2}'"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [career-ops.daemon.data :as data]))

(def tools
  "Available MCP tools exposed by the Daemon."
  [{:name "get_about"
    :description "Get basic information about headhunter-agent: what it is, what it does, and its philosophy"}
   {:name "get_architecture"
    :description "Get the full system architecture including technology stack and key design decisions"}
   {:name "get_modules"
    :description "Get details about all six modules: Data Vault, Evaluator, Interview Prep, PDF Generator, Tracker, M2M Protocol"}
   {:name "get_m2m_spec"
    :description "Get the M2M Job Application Protocol specification: sub-protocols, data flow, cryptography, threat model"}
   {:name "get_config"
    :description "Get configuration reference: environment, profile, archetypes, scoring system, data contract"}
   {:name "get_usage"
    :description "Get usage guide for Desktop GUI, CLI, M2M Protocol, and installation steps"}
   {:name "get_data_contract"
    :description "Get the data ownership model: which files belong to user vs system layer"}
   {:name "get_projects"
    :description "Get current project status and related ecosystem projects"}
   {:name "get_all"
    :description "Get ALL available data at once — complete system reference"}])

(def tool-registry
  "Maps tool names to their data functions."
  {"get_about"        (fn [_] data/about)
   "get_architecture" (fn [_] data/architecture)
   "get_modules"      (fn [_] data/modules)
   "get_m2m_spec"     (fn [_] data/m2m-spec)
   "get_config"       (fn [_] data/config-ref)
   "get_usage"        (fn [_] data/usage)
   "get_data_contract" (fn [_] data/data-contract)
   "get_projects"     (fn [_] data/projects)
   "get_all"          (fn [_] data/all-data)})

(defn json-response
  "Build a JSON-RPC 2.0 success response."
  [id result]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (json/generate-string
          {"jsonrpc" "2.0"
           "result" result
           "id" id})})

(defn json-error
  "Build a JSON-RPC 2.0 error response."
  [id code message]
  {:status (if (>= code -32000) 500 400)
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/generate-string
          {"jsonrpc" "2.0"
           "error" {"code" code "message" message}
           "id" id})})

(defn handle-tools-list
  "Handle tools/list request — return available tools."
  [id]
  (json-response id
    {"tools" (vec (for [t tools]
                    {"name" (:name t)
                     "description" (:description t)}))}))

(defn handle-tools-call
  "Handle tools/call request — invoke a tool and return its content."
  [id params]
  (let [name (get params "name")
        args (get params "arguments" {})
        handler (get tool-registry name)]
    (if handler
      (try
        (let [content (handler args)]
          (json-response id
            {"content" [{"type" "text" "text" content}]}))
        (catch Exception e
          (json-error id -32603 (str "Internal error: " (.getMessage e)))))
      (json-error id -32602 (str "Unknown tool: " name)))))

(defn handle-request
  "Route a JSON-RPC 2.0 request to the appropriate handler."
  [body-str]
  (try
    (let [request (json/parse-string body-str)
          method (get request "method")
          id (get request "id")
          params (get request "params")]
      (cond
        (= "tools/list" method) (handle-tools-list id)
        (= "tools/call" method) (handle-tools-call id params)
        :else (json-error id -32601 (str "Method not found: " method))))
    (catch Exception e
      (json-error nil -32700 (str "Parse error: " (.getMessage e))))))

(defn- serve-request
  "HTTP request handler — Handles POST requests for MCP."
  [method path body m]
  (when (= "POST" method)
    (handle-request body)))

(defn start-server
  "Start the Daemon MCP server using babashka http-server.
  opts: {:port 8081}"
  [& {:keys [port] :or {port 8081}}]
  (println (str "\n╭──────────────────────────────────────────╮"))
  (println (str "│        Headhunter-Agent Daemon            │"))
  (println (str "│        MCP Server v1.0.0                  │"))
  (println (str "├──────────────────────────────────────────┤"))
  (println (str "│  Listening on http://0.0.0.0:" port (apply str (repeat (- 12 (count (str port))) " ")) "│"))
  (println (str "│                                          │"))
  (println (str "│  Tools available:                        │")))
  (doseq [t tools]
    (println (str "│    " (format "%-28s" (:name t)) "  │")))
  (println (str "│                                          │"))
  (println (str "│  Try: curl -X POST http://localhost:" port " \\  │"))
  (println (str "│    -H \"Content-Type: application/json\" \\ │"))
  (println (str "│    -d '{\"jsonrpc\":\"2.0\",               │"))
  (println (str "│    \"method\":\"tools/list\",\"id\":1}'     │"))
  (println (str "╰──────────────────────────────────────────╯\n"))
  (try
    (require '[babashka.http-server :as hs])
    (hs/defhandler "POST" "/" serve-request)
    (hs/start {:port port})
    (println "⏳ Daemon running (Ctrl+C to stop)...")
    @(promise)
    (catch Exception e
      (println (str "❌ Failed to start server: " (.getMessage e)))
      (println "   Install babashka/http-server to use this feature.")
      (println "   Add :daemon alias to deps.edn with org.babashka/http-server"))))

(defn -main
  "Daemon CLI entry point."
  [& args]
  (let [port (some #(when (= "--port" %) (Integer/parseInt (second %))) (partition 2 args))
        port (or port 8081)]
    (start-server {:port port})))
