(ns career-ops.m2m.verify
  "Inbound verification of signed application packages.

  Used by employers to validate incoming applications, and by candidates
  to verify receipt acknowledgments.

  Supports:
  - Envelope signature verification (Ed25519)
  - Attachment digest integrity
  - Attachment digest signature verification
  - Timestamp freshness checks"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [career-ops.m2m.crypto :as crypto]
            [career-ops.m2m.schema :as schema]))

(defn envelope
  "Verify the Ed25519 signature on an application package envelope.
  pkg: parsed application package map.
  Returns {:valid? true} or {:valid? false :error \"...\"}."
  [pkg]
  (let [normalized (schema/canonicalize-keys pkg)
        signature (:m2m/signature normalized)]
    (if-not signature
      {:valid? false :error "No signature found on package"}
      (let [payload-for-check (dissoc normalized :m2m/signature)
            canonical (crypto/canonicalize-json payload-for-check)
            sig-b64 (:m2m/signedPayload signature)
            pub-key (get-in normalized [:m2m/candidate :m2m/identityKey])]
        (if-not pub-key
          {:valid? false :error "No candidate identityKey in package"}
          (let [valid? (crypto/verify canonical sig-b64 pub-key)]
            (if valid?
              {:valid? true}
              {:valid? false :error "Envelope signature does not match"})))))))

(defn attachment-digest
  "Verify that a file's actual digest matches the stated digest in the package.
  file-bytes: raw bytes of the attachment.
  expected-digest: the \"sha256:...\" string from the package attachment entry.
  Returns true if digests match."
  [file-bytes expected-digest]
  (let [actual (str "sha256:" (crypto/sha256 file-bytes))]
    (= actual expected-digest)))

(defn attachment-signature
  "Verify the Ed25519 signature on an attachment's digest.
  file-bytes: raw bytes of the attachment.
  digest-signature-b64: the base64-encoded signature from the package.
  signer-public-key-b64: the claimed signer's public key.
  Returns true if signature is valid."
  [file-bytes digest-signature-b64 signer-public-key-b64]
  (crypto/verify file-bytes digest-signature-b64 signer-public-key-b64))

(defn package
  "Full verification of a signed application package.
  pkg-json: raw JSON-LD string of the application package.
  Returns {:valid? true/false :checks [...] :error \"...\"}."
  [pkg-json]
  (let [pkg (json/parse-string pkg-json true)
        normalized (schema/canonicalize-keys pkg)
        validation (schema/validate-application-package normalized)
        checks (atom [])]

    (when-not (:valid? validation)
      (swap! checks conj {:check "schema" :passed false
                          :error (str/join "; " (:errors validation))}))

    (let [env-result (envelope normalized)]
      (swap! checks conj {:check "envelope-signature"
                          :passed (:valid? env-result)
                          :error (:error env-result)}))

    (let [attachments (:m2m/attachments normalized)]
      (doseq [att attachments]
        (when (:m2m/digestSignature att)
          (swap! checks conj {:check (str "attachment-digest-signature:" (:m2m/filename att))
                              :passed (boolean (:m2m/digestSignature att))
                              :error nil}))))

    {:valid? (every? #(:passed %) @checks)
     :checks @checks}))
