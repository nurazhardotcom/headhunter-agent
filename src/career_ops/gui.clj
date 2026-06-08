(ns career-ops.gui
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [career-ops.profiler :as profiler]
            [career-ops.evaluator :as eval]
            [career-ops.interview :as interview])
  (:import [javafx.application Platform]))

(defonce *state
  (atom {:vault-input ""
         :vault-status :idle ; :idle, :loading, :success, :error
         :vault-error ""
         
         :evaluator-jd ""
         :evaluator-status :idle
         :evaluator-result ""
         :evaluator-error ""
         
         :interview-jd ""
         :interview-status :idle
         :interview-result ""
         :interview-error ""}))

(defn run-later [f]
  (Platform/runLater f))

;; Event Handlers
(defn handle-extract-vault [_]
  (let [input (str/trim (:vault-input @*state))]
    (if (empty? input)
      (swap! *state assoc :vault-status :error :vault-error "Error: Input data cannot be empty.")
      (do
        (swap! *state assoc :vault-status :loading :vault-error "")
        (future
          (try
            ;; Create a temp file to feed to extract-profile!
            (let [temp-file (java.io.File/createTempFile "linkedin_dump" ".txt")]
              (.deleteOnExit temp-file)
              (spit temp-file input)
              (let [res (profiler/extract-profile! (.getAbsolutePath temp-file))]
                (run-later
                 #(swap! *state assoc
                         :vault-status :success
                         :vault-input ""))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :vault-status :error
                       :vault-error (str "Extraction failed: " (.getMessage e)))))))))))

(defn handle-evaluate-jd [_]
  (let [jd (str/trim (:evaluator-jd @*state))]
    (if (empty? jd)
      (swap! *state assoc :evaluator-status :error :evaluator-error "Error: Job Description cannot be empty.")
      (do
        (swap! *state assoc :evaluator-status :loading :evaluator-error "" :evaluator-result "")
        (future
          (try
            (let [res (eval/evaluate-jd jd :save-report? true)]
              (run-later
               #(swap! *state assoc
                       :evaluator-status :success
                       :evaluator-result (:report res))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :evaluator-status :error
                       :evaluator-error (str "Evaluation failed: " (.getMessage e)))))))))))

(defn handle-interview-prep [_]
  (let [jd (str/trim (:interview-jd @*state))]
    (if (empty? jd)
      (swap! *state assoc :interview-status :error :interview-error "Error: Job Description cannot be empty.")
      (do
        (swap! *state assoc :interview-status :loading :interview-error "" :interview-result "")
        (future
          (try
            (let [res (interview/prep-interview! jd)]
              (run-later
               #(swap! *state assoc
                       :interview-status :success
                       :interview-result (:result res))))
            (catch Exception e
              (run-later
               #(swap! *state assoc
                       :interview-status :error
                       :interview-error (str "Prep failed: " (.getMessage e)))))))))))

;; UI Views
(defn vault-tab [{:keys [vault-input vault-status vault-error]}]
  {:fx/type :v-box
   :spacing 15
   :padding 20
   :children
   [{:fx/type :label
     :text "1. Data Vault Builder"
     :style "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #eceff4;"}
    {:fx/type :label
     :text "Paste your raw LinkedIn export text, resumes, or certificates below to build your local structured profile."
     :wrap-text true
     :style "-fx-text-fill: #d8dee9;"}
    {:fx/type :text-area
     :v-box/vgrow :always
     :text vault-input
     :prompt-text "Paste raw CV text or LinkedIn export..."
     :on-text-changed #(swap! *state assoc :vault-input %)
     :style "-fx-font-family: monospace; -fx-control-inner-background: #2e3440; -fx-text-fill: #eceff4;"}
    {:fx/type :h-box
     :spacing 10
     :alignment :center-left
     :children
     [(if (= vault-status :loading)
        {:fx/type :button :text "Extracting..." :disable true}
        {:fx/type :button
         :text "Extract Data Vault"
         :on-action handle-extract-vault
         :style "-fx-background-color: #5e81ac; -fx-text-fill: #eceff4; -fx-font-weight: bold;"})
      (cond
        (= vault-status :loading)
        {:fx/type :label :text "Processing with Gemini..." :style "-fx-text-fill: #ebcb8b;"}
        
        (= vault-status :success)
        {:fx/type :label :text "✅ Master Profile and STAR Stories extracted successfully!" :style "-fx-text-fill: #a3be8c;"}
        
        (= vault-status :error)
        {:fx/type :label :text vault-error :style "-fx-text-fill: #bf616a;"}
        
        :else
        {:fx/type :label :text "Status: Ready" :style "-fx-text-fill: #81a1c1;"})]}]})

(defn evaluator-tab [{:keys [evaluator-jd evaluator-status evaluator-result evaluator-error]}]
  {:fx/type :h-box
   :spacing 15
   :padding 20
   :children
   [{:fx/type :v-box
     :h-box/hgrow :always
     :spacing 15
     :children
     [{:fx/type :label
       :text "Paste Job Description"
       :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #eceff4;"}
      {:fx/type :text-area
       :v-box/vgrow :always
       :text evaluator-jd
       :prompt-text "Paste Job Description here..."
       :on-text-changed #(swap! *state assoc :evaluator-jd %)
       :style "-fx-font-family: monospace; -fx-control-inner-background: #2e3440; -fx-text-fill: #eceff4;"}
      {:fx/type :h-box
       :spacing 10
       :alignment :center-left
       :children
       [(if (= evaluator-status :loading)
          {:fx/type :button :text "Evaluating..." :disable true}
          {:fx/type :button
           :text "Run 3-Stage MAS Evaluation"
           :on-action handle-evaluate-jd
           :style "-fx-background-color: #5e81ac; -fx-text-fill: #eceff4; -fx-font-weight: bold;"})
        (cond
          (= evaluator-status :loading)
          {:fx/type :label :text "Agents are parsing and deep-diving..." :style "-fx-text-fill: #ebcb8b;"}
          
          (= evaluator-status :success)
          {:fx/type :label :text "✅ Evaluation completed!" :style "-fx-text-fill: #a3be8c;"}
          
          (= evaluator-status :error)
          {:fx/type :label :text evaluator-error :style "-fx-text-fill: #bf616a;"}
          
          :else
          nil)]}]}
    {:fx/type :v-box
     :h-box/hgrow :always
     :spacing 15
     :children
     [{:fx/type :label
       :text "Evaluation Results"
       :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #eceff4;"}
      {:fx/type :text-area
       :v-box/vgrow :always
       :editable false
       :text evaluator-result
       :prompt-text "Results will appear here..."
       :style "-fx-font-family: sans-serif; -fx-control-inner-background: #3b4252; -fx-text-fill: #eceff4;"}]}]})

(defn interview-tab [{:keys [interview-jd interview-status interview-result interview-error]}]
  {:fx/type :h-box
   :spacing 15
   :padding 20
   :children
   [{:fx/type :v-box
     :h-box/hgrow :always
     :spacing 15
     :children
     [{:fx/type :label
       :text "Paste Job Description"
       :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #eceff4;"}
      {:fx/type :text-area
       :v-box/vgrow :always
       :text interview-jd
       :prompt-text "Paste target Job Description here..."
       :on-text-changed #(swap! *state assoc :interview-jd %)
       :style "-fx-font-family: monospace; -fx-control-inner-background: #2e3440; -fx-text-fill: #eceff4;"}
      {:fx/type :h-box
       :spacing 10
       :alignment :center-left
       :children
       [(if (= interview-status :loading)
          {:fx/type :button :text "Strategizing..." :disable true}
          {:fx/type :button
           :text "Generate Interview Strategy"
           :on-action handle-interview-prep
           :style "-fx-background-color: #5e81ac; -fx-text-fill: #eceff4; -fx-font-weight: bold;"})
        (cond
          (= interview-status :loading)
          {:fx/type :label :text "Mapping STAR stories to expected questions..." :style "-fx-text-fill: #ebcb8b;"}
          
          (= interview-status :success)
          {:fx/type :label :text "✅ Strategy generated!" :style "-fx-text-fill: #a3be8c;"}
          
          (= interview-status :error)
          {:fx/type :label :text interview-error :style "-fx-text-fill: #bf616a;"}
          
          :else
          nil)]}]}
    {:fx/type :v-box
     :h-box/hgrow :always
     :spacing 15
     :children
     [{:fx/type :label
       :text "STAR Questions & Prep Sheet"
       :style "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #eceff4;"}
      {:fx/type :text-area
       :v-box/vgrow :always
       :editable false
       :text interview-result
       :prompt-text "STAR Mapping strategy will appear here..."
       :style "-fx-font-family: sans-serif; -fx-control-inner-background: #3b4252; -fx-text-fill: #eceff4;"}]}]})

(defn root-view [state]
  {:fx/type :stage
   :showing true
   :title "Local Jack & Jill — Clojure Desktop MAS Console"
   :width 1100
   :height 700
   :scene {:fx/type :scene
           :style "-fx-background-color: #2e3440;"
           :root {:fx/type :tab-pane
                  :side :top
                  :tabs [{:fx/type :tab
                          :text "Data Vault"
                          :closable false
                          :content {:fx/type vault-tab
                                    :vault-input (:vault-input state)
                                    :vault-status (:vault-status state)
                                    :vault-error (:vault-error state)}}
                         {:fx/type :tab
                          :text "Evaluate JD"
                          :closable false
                          :content {:fx/type evaluator-tab
                                    :evaluator-jd (:evaluator-jd state)
                                    :evaluator-status (:evaluator-status state)
                                    :evaluator-result (:evaluator-result state)
                                    :evaluator-error (:evaluator-error state)}}
                         {:fx/type :tab
                          :text "Interview Prep"
                          :closable false
                          :content {:fx/type interview-tab
                                    :interview-jd (:interview-jd state)
                                    :interview-status (:interview-status state)
                                    :interview-result (:interview-result state)
                                    :interview-error (:interview-error state)}}]}}})

(defn -main [& _args]
  (Platform/setImplicitExit true)
  (fx/mount-renderer
   *state
   (fx/create-renderer
    :middleware (fx/wrap-map-desc #'root-view))))
