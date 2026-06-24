(ns career-ops.m2m.submit
  "Build and send signed application packages.

  Constructs the m2m:ApplicationPackage JSON-LD, signs all
  attachments, signs the envelope, and delivers via multipart POST."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [career-ops.m2m.crypto :as crypto]
            [career-ops.m2m.schema :as schema]))

(defn- build-attachment
  "Create the m2m:attachments entry for a file.
  Signs the file content for integrity verification."
  [file-path identity]
  (let [file-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file file-path)))
        sig-info (crypto/sign-attachment file-bytes (:private-key identity))
        filename (last (str/split file-path #"/"))]
    {:m2m/filename filename
     :m2m/mediaType (if (str/ends-with? filename ".pdf") "application/pdf" "application/octet-stream")
     :m2m/size (count file-bytes)
     :m2m/digest (:digest sig-info)
     :m2m/digestSignature (:digestSignature sig-info)}))

(defn build-package
  "Build a signed m2m:ApplicationPackage ready for submission.
  posting: normalized job posting map from fetch/job-posting.
  pdf-path: path to the tailored PDF resume.
  identity: identity map from crypto/load-identity."
  [posting pdf-path identity]
  (let [profile (crypto/sha256 (pr-str (crypto/load-identity)))
        attachment (build-attachment pdf-path identity)
        package {:m2m/protocolVersion "1.0.0"
                 :m2m/timestamp (.toString (java.time.Instant/now))
                  :m2m/jobPosting {(keyword "@id") (get-in posting [:raw (keyword "@id")] "unknown")
                                  :m2m/digest (str "sha256:" (crypto/sha256 (pr-str posting)))}
                 :m2m/candidate {:m2m/profileDigest (str "sha256:" profile)
                                 :m2m/identityKey (:public-key identity)}
                 :m2m/attachments [attachment]
                 :m2m/coverLetter ""}
        signed (crypto/sign-payload package (:private-key identity))]
    signed))

(defn send
  "Submit a signed application package to an employer's M2M endpoint.
  package: the signed application map from build-package.
  endpoint: the employer's application endpoint URL (from registry)."
  [package endpoint]
  (let [apply-url (str endpoint "/apply")
        pkg-json (json/generate-string package {:key-fn name})
        ;; The resume PDF bytes are read separately for the multipart upload
        attachment (first (:m2m/attachments package))
        pdf-bytes (when attachment
                    (let [fpath (io/file (str "output/" (:m2m/filename attachment)))]
                      (when (.exists fpath)
                        (java.nio.file.Files/readAllBytes (.toPath fpath)))))
        boundary "m2m-apply-boundary"
        body-builder (java.lang.StringBuilder.)]

    ;; Build multipart body
    (.append body-builder (str "--" boundary "\r\n"))
    (.append body-builder "Content-Disposition: form-data; name=\"application\"\r\n")
    (.append body-builder "Content-Type: application/ld+json\r\n\r\n")
    (.append body-builder pkg-json)
    (.append body-builder "\r\n")

    (when pdf-bytes
      (.append body-builder (str "--" boundary "\r\n"))
      (.append body-builder "Content-Disposition: form-data; name=\"resume\"; filename=\"cv.pdf\"\r\n")
      (.append body-builder "Content-Type: application/pdf\r\n")
      (.append body-builder (str "M2M-Digest: " (:m2m/digest attachment) "\r\n"))
      (.append body-builder (str "M2M-Digest-Signature: " (:m2m/digestSignature attachment) "\r\n\r\n"))
      ;; Append binary PDF data as bytes — for now just string placeholder
      (.append body-builder (str "[binary pdf " (:m2m/size attachment) " bytes]")))
    (.append body-builder (str "\r\n--" boundary "--\r\n"))

    (let [response (http/post apply-url
                              {:headers {"Content-Type" (str "multipart/form-data; boundary=" boundary)
                                         "User-Agent" "headhunter-agent/1.0"}
                               :body (str body-builder)
                               :throw false})]
      (if (= 200 (:status response))
        (let [ack (json/parse-string (:body response) true)
              ack-normalized (schema/canonicalize-keys ack)]
          {:status (:m2m/status ack-normalized "pending")
           :application-id (:m2m/applicationId ack-normalized "unknown")
           :next-steps (:m2m/nextSteps ack-normalized "")
           :raw ack})
        (throw (ex-info (str "Submission failed: HTTP " (:status response))
                        {:status (:status response) :body (:body response)}))))))
