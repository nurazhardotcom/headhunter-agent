# M2M Job Application Protocol v1.0.0

> **Machine-to-Machine** job application pipeline. No browsers, no CAPTCHAs, no manual portals.
> Extends the headhunter-agent architecture with cryptographic identity, decentralized discovery,
> and signed application submission.

---

## 1. Protocol Overview

The M2M Protocol defines four sub-protocols that together enable fully automated job applications:

| Sub-protocol | Description |
|---|---|
| **Discovery** | Locate employer application endpoints via DNS or directory |
| **Fetch** | Retrieve machine-readable job postings (JSON-LD) |
| **Verify** | Cryptographic handshake: mutual identity verification |
| **Submit** | Signed application package delivery + acknowledgment |

### Data Flow

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Candidate   │     │  Registry / DNS   │     │  Employer    │
│  (Agent)     │     │  (Discovery)      │     │  (Server)    │
└──────┬───────┘     └────────┬─────────┘     └──────┬────────┘
       │                      │                      │
       │  1. TXT lookup       │                      │
       │  _m2m-apply.employer │                      │
       │──────────────────────▶                      │
       │                      │                      │
       │  2. Endpoint + pubkey│                      │
       │◀──────────────────────                      │
       │                      │                      │
       │  3. GET /jobs.jsonld │                      │
       │─────────────────────────────────────────────▶
       │                      │                      │
       │  4. Job Posting (LD) │                      │
       │◀─────────────────────────────────────────────
       │                      │                      │
       │  5. MAS Evaluation   │                      │
       │  (existing pipeline) │                      │
       │                      │                      │
       │  6. POST /apply      │                      │
       │  (signed package)    │                      │
       │─────────────────────────────────────────────▶
       │                      │                      │
       │  7. Signed receipt   │                      │
       │◀─────────────────────────────────────────────
```

---

## 2. JSON-LD Schema Definitions

### 2.1 Namespaces

```
@context:
  schema: https://schema.org/
  m2m:    https://m2m-apply.org/ns/v1#
  xsd:    http://www.w3.org/2001/XMLSchema#
```

### 2.2 Job Posting (`m2m:JobPosting`)

Extends `schema:JobPosting` with M2M-specific fields.

```json
{
  "@context": {
    "schema": "https://schema.org/",
    "m2m": "https://m2m-apply.org/ns/v1#"
  },
  "@type": "m2m:JobPosting",
  "@id": "https://employer.example/jobs/42",
  "schema:title": "Senior Platform Engineer",
  "schema:description": "We are looking for...",
  "schema:datePosted": "2026-06-01",
  "schema:validThrough": "2026-07-01",
  "schema:employmentType": "FULL_TIME",
  "schema:identifier": {
    "@type": "schema:PropertyValue",
    "schema:name": "Requisition ID",
    "schema:value": "REQ-2026-0042"
  },
  "schema:skills": ["Kubernetes", "Terraform", "Go", "AWS"],
  "schema:experienceRequirements": {
    "@type": "schema:OccupationalExperienceRequirements",
    "schema:monthsOfExperience": 60
  },

  "m2m:applyEndpoint": "https://employer.example/m2m/apply/v1",
  "m2m:publicKey": "MCowBQYDK2VwAyEA5PfG1o...",
  "m2m:protocolVersion": "1.0.0",
  "m2m:acceptsSignedApplications": true,
  "m2m:applicationSchema": "https://employer.example/schemas/application-v1.json",
  "m2m:supportsCoverLetter": true,
  "m2m:maxResumeBytes": 5242880,
  "m2m:notificationHooks": [
    "https://hooks.employer.example/m2m/ack"
  ],
  "m2m:compensation": {
    "schema:currency": "SGD",
    "schema:minValue": 120000,
    "schema:maxValue": 180000,
    "schema:unitText": "YEAR"
  }
}
```

### 2.3 Candidate Profile (`m2m:CandidateProfile`)

```json
{
  "@context": {
    "schema": "https://schema.org/",
    "m2m": "https://m2m-apply.org/ns/v1#"
  },
  "@type": "m2m:CandidateProfile",
  "@id": "did:key:z6Mkq...",
  "schema:name": "Nur Azhar",
  "schema:email": "nur@example.com",
  "schema:telephone": "+65-...",
  "schema:address": {
    "@type": "schema:PostalAddress",
    "schema:addressRegion": "Singapore"
  },
  "schema:knowsAbout": ["Platform Engineering", "Clojure", "Kubernetes"],
  "schema:hasOccupation": {
    "@type": "schema:Occupation",
    "schema:name": "Platform Engineer"
  },
  "schema:totalYearsExperience": 8,

  "m2m:authorization": {
    "m2m:workRight": "SINGAPORE_CITIZEN",
    "m2m:nationalServiceStatus": "COMPLETED",
    "m2m:noticePeriodDays": 30
  },
  "m2m:publicKey": "MCowBQYDK2VwAyEA5PfG1o...",
  "m2m:profileDigest": "sha256:abc123..."
}
```

### 2.4 Application Package (`m2m:ApplicationPackage`)

The signed submission envelope:

```json
{
  "@type": "m2m:ApplicationPackage",
  "m2m:protocolVersion": "1.0.0",
  "m2m:timestamp": "2026-06-24T12:00:00+08:00",
  "m2m:jobPosting": {
    "@id": "https://employer.example/jobs/42",
    "m2m:digest": "sha256:def456..."
  },

  "m2m:candidate": {
    "m2m:profileDigest": "sha256:ghi789...",
    "m2m:identityKey": "MCowBQYDK2VwAyEA5PfG1o..."
  },

  "m2m:attachments": [
    {
      "m2m:filename": "cv-nur-azhar-platform-engineer.pdf",
      "m2m:mediaType": "application/pdf",
      "m2m:size": 284712,
      "m2m:digest": "sha256:att123...",
      "m2m:digestSignature": "MEYCIQDFv..."
    }
  ],

  "m2m:coverLetter": "Dear Hiring Team, ...",

  "m2m:signature": {
    "m2m:signedBy": "MCowBQYDK2VwAyEA5PfG1o...",
    "m2m:signedAt": "2026-06-24T12:00:00+08:00",
    "m2m:signedPayload": "MEUCIQDFvm...",
    "m2m:algorithm": "Ed25519"
  }
}
```

---

## 3. Decentralized Endpoint Registry

### 3.1 DNS-Based Discovery (Primary)

Employers publish a TXT record on their domain:

```
_m2m-apply.employer.example.  IN  TXT  "m2m-p1;https://employer.example/m2m/v1;key=MCowBQYDK2VwAyEA5PfG1o..."
```

Format: `m2m-p{protocol-version};{base-url};key={base64-public-key};`

The candidate agent performs a DNS TXT lookup:

```
bb m2m discover employer.example
→ https://employer.example/m2m/v1
→ key: MCowBQYDK2VwAyEA5PfG1o...
```

### 3.2 Directory Service (Fallback / Aggregation)

A lightweight Babashka HTTP service that aggregates known M2M endpoints.
Serves as a discovery fallback when DNS is unavailable.

```
GET https://directory.m2m-apply.org/v1/search?q=platform+engineer+sg
→ [ { "domain": "employer.example", "endpoint": "...", "key": "..." } ]
```

### 3.3 Registry Client (Babashka)

```clojure
;; src/career_ops/m2m/registry.clj

(defn discover-dns [domain]
  (let [txt (dns-lookup (str "_m2m-apply." domain))]
    (when txt
      (parse-txt-record txt))))

(defn discover-directory [query]
  (http/get (str "https://directory.m2m-apply.org/v1/search")
            {:query-params {"q" query}}))
```

### 3.4 Registry Server (Babashka)

```clojure
;; src/career_ops/m2m/directory.clj

(require '[babashka.http-server :as hs])

(def registry (atom {}))

(hs/defhandler POST "/v1/register" [body]
  (let [entry (json/parse-string body true)]
    (swap! registry assoc (:domain entry) entry)
    {:status 200 :body "registered"}))

(hs/defhandler GET "/v1/search" [params]
  {:status 200
   :body (json/generate-string
          (filter-by-query @registry (:q params)))})

(hs/start {:port 8080})
```

---

## 4. Cryptographic Verification

### 4.1 Key Generation

Ed25519 keypair generated once per identity:

```bash
# CLI via Babashka
bb m2m keygen --output ~/.m2m/identity.edn
```

```clojure
;; src/career_ops/m2m/crypto.clj

(defn generate-identity []
  (let [keypair (ed25519/generate-keypair)]
    {:private-key (-> keypair :secret (secret->pem))
     :public-key  (-> keypair :public (public->pem))
     :key-id      (-> keypair :public (sha256-hex))}))
```

### 4.2 Signing

Every outgoing application package is signed. Additionally, each file attachment
carries its own digest and signature for independent verification.

```clojure
(defn sign-payload [payload private-key]
  (let [canonical (canonicalize-json payload)
        signature (ed25519/sign (.getBytes canonical) private-key)]
    (assoc payload :m2m/signature
           {:signedPayload (base64/encode signature)
            :algorithm "Ed25519"
            :signedAt (now-iso-8601)
            :signedBy (public-key-fingerprint private-key)})))

(defn sign-attachment [file-bytes private-key]
  (let [digest (sha256 file-bytes)
        signature (ed25519/sign digest private-key)]
    {:digest (str "sha256:" digest)
     :digestSignature (base64/encode signature)}))
```

### 4.3 Verification

```clojure
(defn verify-application [package employer-public-key]
  (let [signature (-> package :m2m/signature :signedPayload base64/decode)
        payload (dissoc package :m2m/signature)
        canonical (canonicalize-json payload)]
    (ed25519/verify signature (.getBytes canonical) employer-public-key)))

(defn verify-attachment [file-bytes expected-digest expected-signature signer-key]
  (let [actual-digest (sha256 file-bytes)]
    (and (= expected-digest (str "sha256:" actual-digest))
         (ed25519/verify (base64/decode expected-signature)
                         (.getBytes actual-digest)
                         signer-key))))
```

### 4.4 Identity Model

| Entity | Key Material | Published Where |
|---|---|---|
| Candidate | Ed25519 keypair | Attached to every application |
| Employer | Ed25519 keypair | DNS TXT record + job posting |
| Directory | TLS + optional signing key | HTTPS only |

No CA hierarchy. Trust is established via:
1. **DNS-based trust**: Employer controls their domain's TXT records
2. **Key continuity**: Keys are long-lived; first-use trust with out-of-band verification
3. **Optional DID integration**: `did:key:` identifiers for future proofing

---

## 5. Protocol Messages

### 5.1 Discovery Request (Candidate → DNS)

Standard DNS TXT query. No HTTP involved.

### 5.2 Discovery Response (DNS → Candidate)

```
_m2m-apply.employer.example. 300 IN TXT "m2m-p1;https://employer.example/m2m/v1;key=MCowBQYDK2VwAyEA5PfG1o..."
```

### 5.3 Job Listing Fetch (Candidate → Employer)

```
GET /m2m/v1/jobs HTTP/1.1
Host: employer.example
Accept: application/ld+json
User-Agent: headhunter-agent/1.0
```

Response:

```
HTTP/1.1 200 OK
Content-Type: application/ld+json
M2M-PublicKey: MCowBQYDK2VwAyEA5PfG1o...
M2M-Protocol: 1.0.0

[ { "@type": "m2m:JobPosting", ... } ]
```

### 5.4 Application Submission (Candidate → Employer)

```
POST /m2m/v1/apply HTTP/1.1
Host: employer.example
Content-Type: multipart/form-data; boundary=m2m-boundary
User-Agent: headhunter-agent/1.0

--m2m-boundary
Content-Disposition: form-data; name="application"
Content-Type: application/ld+json

{ "@type": "m2m:ApplicationPackage", ... }

--m2m-boundary
Content-Disposition: form-data; name="resume"; filename="cv.pdf"
Content-Type: application/pdf
M2M-Digest: sha256:abc123...
M2M-Digest-Signature: MEYCIQDFv...

[binary PDF data]

--m2m-boundary--
```

### 5.5 Acknowledgment (Employer → Candidate)

```json
{
  "@type": "m2m:Acknowledgment",
  "m2m:status": "accepted",
  "m2m:applicationId": "APP-2026-06-24-0042",
  "m2m:receivedAt": "2026-06-24T12:00:05+08:00",
  "m2m:nextSteps": "We will review and respond within 5 business days.",
  "m2m:signature": {
    "signedPayload": "...",
    "algorithm": "Ed25519"
  }
}
```

Possible statuses: `accepted`, `rejected` (pre-screening), `duplicate`, `malformed`.

---

## 6. Clojure / Babashka Implementation

### 6.1 Namespace Map

```
src/career_ops/m2m/
├── core.clj          # Entry point, CLI routing
├── crypto.clj        # Ed25519 keygen, sign, verify
├── schema.clj        # JSON-LD validation & generation
├── registry.clj      # DNS & directory discovery client
├── directory.clj     # Directory service server (optional)
├── fetch.clj         # Job posting HTTP fetcher
├── submit.clj        # Application package builder + sender
└── verify.clj        # Inbound verification for employers
```

### 6.2 CLI Integration (`bb.edn`)

```clojure
:bb-m2m {:doc "M2M Protocol CLI"
         :task (do (require 'career-ops.m2m.core)
                   (apply (resolve 'career-ops.m2m.core/-main) *command-line-args*))}
```

Usage:

```bash
bb bb-m2m keygen                              # Generate identity
bb bb-m2m discover <domain>                   # Discover employer endpoint
bb bb-m2m fetch <url>                         # Fetch & validate job posting
bb bb-m2m apply <job-url> [--profile <file>]  # Evaluate + sign + submit
bb bb-m2m verify <package-file>               # Verify signed application
bb bb-m2m serve                               # Start directory server
```

### 6.3 Integration with Existing Pipeline

The `bb bb-m2m apply` command chains the existing MAS pipeline into the M2M flow:

```clojure
(defn apply-flow [job-url profile-path]
  (let [posting    (fetch-posting job-url)          ;; GET JSON-LD
        employer   (verify-endpoint posting)        ;; Verify key
        _          (println "⏳ Evaluating...")
        evaluation (eval/evaluate-jd posting)       ;; Existing MAS
        _          (println "⏳ Tailoring resume...")
        pdf-path   (pdf/generate-pdf-resume posting) ;; Existing PDF gen
        _          (println "⏳ Signing package...")
        pkg        (build-application posting pdf-path)
        _          (println "⏳ Submitting...")
        receipt    (submit-application pkg employer)
        _          (println "✅ Submitted!")
        _          (tracker/add-entry! ...)]         ;; Existing tracker
    receipt))
```

---

## 7. Security Model

### 7.1 Threat Model

| Threat | Mitigation |
|---|---|
| Impersonation of employer | DNS-based key binding; TLS |
| Impersonation of candidate | Ed25519 signature on every application |
| Replay attack | Timestamp + nonce in application package; server enforces window |
| Tampered resume | Attachment digest + separate digest signature |
| Man-in-the-middle | TLS for all HTTP; signed payloads provide end-to-end integrity |
| Registry poisoning | Directory entries are signed; DNS is DNSSEC-ready |

### 7.2 No-CAPTCHA Guarantee

The protocol explicitly eliminates human verification:
- **Endpoints opt-in** by publishing `m2m:acceptsSignedApplications: true`
- **Signed identity** replaces cookie/session/auth-wall
- **Rate limiting** by public key fingerprint, not IP
- **Reputation** based on application quality, not manual review

---

## 8. Protocol Extension Points

| Extension | Mechanism |
|---|---|
| Custom screening questions | `m2m:applicationSchema` points to a JSON Schema |
| Skills assessments | `m2m:assessmentUrl` in job posting |
| Portfolio attachments | Additional `m2m:attachments[]` entries |
| Multi-party applications | `m2m:ApplicationPackage` can reference multiple candidate profiles |
| Decentralized identity | DID integration via `did:key:` in place of raw public key |
| Encrypted submissions | Future: envelope encryption using employer's public key |

---

## 9. Implementation Roadmap

| Phase | Components | Depends On |
|---|---|---|
| **P0** | `crypto.clj`, `schema.clj`, `core.clj` (keygen + verify) | None |
| **P1** | `registry.clj` (DNS discovery), `fetch.clj` | P0 |
| **P2** | `submit.clj` (package build + send), `verify.clj` | P0, P1 |
| **P3** | Babashka directory server (`directory.clj`) | P0 |
| **P4** | Full `bb bb-m2m apply` integration with MAS pipeline | P0-P3 |
| **P5** | Employer-side verification library | P0, P2 |

---

## 10. References

- [schema.org/JobPosting](https://schema.org/JobPosting)
- [Ed25519 RFC 8032](https://datatracker.ietf.org/doc/html/rfc8032)
- [JSON-LD 1.1 W3C Recommendation](https://www.w3.org/TR/json-ld11/)
- [DNS-Based Service Discovery (RFC 6763)](https://datatracker.ietf.org/doc/html/rfc6763)
- [DID Core W3C Recommendation](https://www.w3.org/TR/did-core/)
