(ns career-ops.interview
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [career-ops.evaluator :as eval]))

(def interview-prep-system-prompt
  (str "You are an expert interview coach for a senior tech role.\n"
       "Using the candidate's STAR stories and the Job Description, map the 5 most likely behavioral/technical interview questions for this specific role.\n"
       "For each likely question, select the best STAR story from the candidate's library and explain WHY it fits and HOW to deliver it.\n"
       "Provide actionable, hard-hitting advice on how to stand out and control the narrative."))

(defn load-star-stories []
  (let [f (io/file "data/star-stories.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      nil)))

(defn prep-interview! [jd-text]
  (let [env (eval/load-env)
        api-key (get env "GEMINI_API_KEY")]
    (if-not api-key
      (throw (ex-info "GEMINI_API_KEY not found in environment or .env file" {}))
      (let [stories (load-star-stories)]
        (if-not stories
          (throw (ex-info "data/star-stories.edn not found. Please extract your profile first." {}))
          (let [user-prompt (str "CANDIDATE STAR STORIES:\n" (pr-str stories) "\n\nJOB DESCRIPTION:\n" jd-text)
                result-text (eval/call-gemini api-key interview-prep-system-prompt user-prompt "gemini-2.5-flash")
                reports-dir "reports"
                _ (.mkdirs (io/file reports-dir))
                today (eval/today-str)
                filename (str "prep-" today "-" (System/currentTimeMillis) ".md")
                report-path (str reports-dir "/" filename)]
            (spit report-path (str "# Interview Strategy & STAR Mapping\n**Date:** " today "\n\n---\n\n" result-text))
            {:report-path report-path :result result-text}))))))
