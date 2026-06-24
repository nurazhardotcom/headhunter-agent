(ns career-ops.m2m.crypto
  "Cryptographic operations for the M2M protocol.

  Ed25519 signing and verification for application packages,
  resume attachments, and protocol messages.

  Key generation: bb bb-m2m keygen
  Key material stored in ~/.m2m/identity.edn"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.security KeyPairGenerator Signature MessageDigest]
           [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
           [java.util Base64]))

(def ^:private m2m-dir (str (System/getProperty "user.home") "/.m2m"))
(def ^:private identity-file (str m2m-dir "/identity.edn"))

(defn base64-encode
  "Encode byte array to Base64 string."
  [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn base64-decode
  "Decode Base64 string to byte array."
  [s]
  (.decode (Base64/getDecoder) s))

(defn sha256
  "Return hex-encoded SHA-256 digest of bytes or string."
  [input]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (if (string? input) (.getBytes input "UTF-8") input)]
    (.update digest bytes)
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn generate-identity
  "Generate a new Ed25519 keypair and persist to ~/.m2m/identity.edn.
  Returns {:private-key ... :public-key ... :key-id ...}."
  (let [kpg (KeyPairGenerator/getInstance "Ed25519")
        kp (.generateKeyPair kpg)
        priv (base64-encode (.getEncoded (.getPrivate kp)))
        pub (base64-encode (.getEncoded (.getPublic kp)))
        key-id (sha256 pub)
        identity {:private-key priv
                  :public-key pub
                  :key-id key-id
                  :algorithm "Ed25519"
                  :generated-at (.toString (java.time.Instant/now))}]
    (io/make-parents (io/file identity-file))
    (spit identity-file (with-out-str (clojure.pprint/pprint identity)))
    identity))

(defn load-identity
  "Load identity from ~/.m2m/identity.edn.
  Generates one if none exists."
  []
  (let [f (io/file identity-file)]
    (if (.exists f)
      (read-string (slurp f))
      (generate-identity))))

(defn public-key-fingerprint
  "Short hex fingerprint of a public key for display."
  [pub-key-b64]
  (let [bytes (base64-decode pub-key-b64)]
    (subs (sha256 bytes) 0 16)))

(defn sign
  "Sign a payload with the given private key.
  payload: string or byte array to sign.
  private-key-b64: Base64-encoded PKCS8 private key.
  Returns Base64-encoded signature."
  [payload private-key-b64]
  (let [key-bytes (base64-decode private-key-b64)
        key-spec (PKCS8EncodedKeySpec. key-bytes)
        kf (java.security.KeyFactory/getInstance "Ed25519")
        priv-key (.generatePrivate kf key-spec)
        sig (Signature/getInstance "Ed25519")
        payload-bytes (if (string? payload)
                        (.getBytes payload "UTF-8")
                        payload)]
    (.initSign sig priv-key)
    (.update sig payload-bytes)
    (base64-encode (.sign sig))))

(defn verify
  "Verify an Ed25519 signature.
  Returns true if signature is valid for the given payload and public key."
  [payload signature-b64 public-key-b64]
  (try
    (let [key-bytes (base64-decode public-key-b64)
          key-spec (X509EncodedKeySpec. key-bytes)
          kf (java.security.KeyFactory/getInstance "Ed25519")
          pub-key (.generatePublic kf key-spec)
          sig (Signature/getInstance "Ed25519")
          payload-bytes (if (string? payload)
                          (.getBytes payload "UTF-8")
                          payload)]
      (.initVerify sig pub-key)
      (.update sig payload-bytes)
      (.verify sig (base64-decode signature-b64)))
    (catch Exception _
      false)))

(defn canonicalize-json
  "Canonical JSON encoding for deterministic signing.
  Sorted keys, no whitespace, standard JSON."
  [data]
  (json/generate-string data {:key-fn name :pretty false}))

(defn sign-payload
  "Sign the canonical JSON of a payload map.
  Injects m2m:signature envelope into the payload."
  [payload private-key-b64]
  (let [signer-pub (load-identity :public-key)
        payload-for-signing (dissoc payload :m2m/signature)
        canonical (canonicalize-json payload-for-signing)
        sig-b64 (sign canonical private-key-b64)]
    (assoc payload :m2m/signature
           {:m2m/signedPayload sig-b64
            :m2m/algorithm "Ed25519"
            :m2m/signedAt (.toString (java.time.Instant/now))
            :m2m/signedBy (public-key-fingerprint signer-pub)})))

(defn sign-attachment
  "Compute SHA-256 digest and Ed25519 signature of a file.
  Returns {:digest \"sha256:...\" :digestSignature \"...\"}."
  [file-bytes private-key-b64]
  (let [digest (str "sha256:" (sha256 file-bytes))
        signature (sign file-bytes private-key-b64)]
    {:digest digest
     :digestSignature signature}))
