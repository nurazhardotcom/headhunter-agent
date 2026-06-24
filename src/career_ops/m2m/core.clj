(ns career-ops.m2m.core
  "M2M Protocol CLI entry point. Routes bb bb-m2m subcommands.

  Usage:
    bb bb-m2m keygen                         Generate Ed25519 identity
    bb bb-m2m discover <domain>              Discover employer endpoint via DNS
    bb bb-m2m fetch <url>                    Fetch & validate job posting
    bb bb-m2m apply <job-url>                Full apply pipeline
    bb bb-m2m verify <package-file>          Verify signed application
    bb bb-m2m serve                          Start directory server"
  (:require [clojure.string :as str]
            [career-ops.m2m.crypto :as crypto]
            [career-ops.m2m.registry :as registry]
            [career-ops.m2m.fetch :as fetch]
            [career-ops.m2m.submit :as submit]
            [career-ops.m2m.verify :as verify]
            [career-ops.m2m.directory :as directory]
            [career-ops.evaluator :as eval]
            [career-ops.pdf :as pdf]
            [career-ops.tracker :as tracker]))

(defn print-help []
  (println "M2M Job Application Protocol v1.0.0")
  (println "")
  (println "  bb bb-m2m keygen")
  (println "      Generate a new Ed25519 identity (~/.m2m/identity.edn)")
  (println "")
  (println "  bb bb-m2m discover <domain>")
  (println "      Look up employer M2M endpoint via DNS TXT record")
  (println "")
  (println "  bb bb-m2m fetch <url>")
  (println "      Fetch and validate a JSON-LD job posting")
  (println "")
  (println "  bb bb-m2m apply <job-url> [--profile <path>]")
  (println "      Full pipeline: discover → fetch → evaluate → tailor → sign → submit")
  (println "")
  (println "  bb bb-m2m verify <package-file>")
  (println "      Verify a signed application package file")
  (println "")
  (println "  bb bb-m2m serve [--port <port>]")
  (println "      Start the Babashka directory server (experimental)"))

(defn keygen-cmd [& args]
  (println "⏳ Generating Ed25519 identity...")
  (let [identity (crypto/generate-identity)]
    (println "✅ Identity generated:")
    (println (str "   Public key: " (subs (:public-key identity) 0 48) "..."))
    (println (str "   Key ID:     " (:key-id identity)))
    (println "   Saved to:   ~/.m2m/identity.edn")))

(defn discover-cmd [& args]
  (let [domain (first args)]
    (if-not domain
      (println "Usage: bb bb-m2m discover <domain>")
      (do
        (println (str "🔍 Discovering M2M endpoint for " domain "..."))
        (let [result (registry/discover domain)]
          (if result
            (do (println (str "✅ Endpoint: " (:endpoint result)))
                (println (str "   Key:       " (:public-key result))))
            (println "❌ No M2M endpoint found for this domain.")))))))

(defn fetch-cmd [& args]
  (let [url (first args)]
    (if-not url
      (println "Usage: bb bb-m2m fetch <url>")
      (try
        (let [posting (fetch/job-posting url)]
          (println (str "✅ Fetched: " (:title posting)))
          (println (str "   Employer: " (:company posting)))
          (println (str "   Skills:   " (str/join ", " (:skills posting)))))
        (catch Exception e
          (println (str "❌ Fetch failed: " (.getMessage e))))))))

(defn apply-cmd [& args]
  (let [job-url (first args)]
    (if (nil? job-url)
      (println "Usage: bb bb-m2m apply <job-url> [--profile <path>]")
      (try
        (println (str "🎯 M2M Apply for: " job-url))
        (println "──────────────────────────────────────────")
        (println "\n1/5 🔍 Discovering employer endpoint...")
        (let [domain (-> job-url (java.net.URL.) .getHost)
              endpoint-info (registry/discover domain)]
          (println (str "   → " (:endpoint endpoint-info)))
          (println "2/5 📥 Fetching job posting...")
          (let [posting (fetch/job-posting job-url)]
            (println (str "   → " (:title posting) " @ " (:company posting)))
            (println "3/5 🧠 Evaluating fit (MAS pipeline)...")
            (let [eval-result (eval/evaluate-jd (:description posting) :save-report? true)
                  summary (:summary eval-result)]
              (println (str "   → Score: " (:score summary) "/5"))
              (println (str "   → Legitimacy: " (:legitimacy summary)))
              (when (= "NO-GO" (:recommendation summary))
                (println "   ⛔ NO-GO recommendation. Skipping submission.")
                (println "   (use --force to override)")
                (System/exit 0))
              (println "4/5 📄 Tailoring and compiling resume...")
              (let [pdf-path (pdf/generate-pdf-resume (:description posting) :company-name (:company summary))]
                (println (str "   → PDF: " pdf-path))
                (println "5/5 ✍️ Signing and submitting application...")
                (let [identity (crypto/load-identity)
                      pkg (submit/build-package posting pdf-path identity)
                      receipt (submit/send pkg (:endpoint endpoint-info))]
                  (println (str "✅ Submitted! Application ID: " (:application-id receipt)))
                  (println (str "   Status: " (:status receipt)))
                  (tracker/add-entry!
                   {:date (:today eval-result)
                    :company (:company summary)
                    :role (:role summary)
                    :score (:score summary)
                    :status "M2M Submitted"
                    :pdf pdf-path
                    :report-link (:report-path eval-result)
                     :notes (str "M2M v1 | ID: " (:application-id receipt))}))))))
         (catch Exception e
          (println (str "❌ Apply failed: " (.getMessage e)))
          (System/exit 1))))))

(defn verify-cmd [& args]
  (let [pkg-file (first args)]
    (if (nil? pkg-file)
      (println "Usage: bb bb-m2m verify <package-file>")
      (try
        (let [result (verify/package (slurp pkg-file))]
          (if (:valid? result)
            (println "✅ Signature valid. Package integrity confirmed.")
            (println (str "❌ Verification failed: " (:error result)))))
        (catch Exception e
          (println (str "❌ Verification error: " (.getMessage e))))))))

(defn serve-cmd [& args]
  (let [port (some #(when (= "--port" %) (Integer/parseInt (second %))) (partition 2 args))
        port (or port 8080)]
    (println (str "🌐 Starting M2M Directory Server on port " port "..."))
    (directory/start-server {:port port})))

(defn -main [& args]
  (let [command (first args)
        cmd-args (rest args)]
    (cond
      (= command "keygen")   (keygen-cmd)
      (= command "discover") (apply discover-cmd cmd-args)
      (= command "fetch")    (apply fetch-cmd cmd-args)
      (= command "apply")    (apply apply-cmd cmd-args)
      (= command "verify")   (apply verify-cmd cmd-args)
      (= command "serve")    (apply serve-cmd cmd-args)
      (or (nil? command) (= command "help") (= command "--help")) (print-help)
      :else (do (println (str "Unknown command: " command))
                (print-help)))))
