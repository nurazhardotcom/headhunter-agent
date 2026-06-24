# M2M Protocol Architecture

## System Context

```
┌─────────────────────────────────────────────────────────────┐
│                     M2M Job Application Universe              │
│                                                              │
│  ┌──────────────────┐     ┌──────────────────┐              │
│  │   Candidate Side   │     │   Employer Side    │              │
│  │  (headhunter-agent)│     │  (M2M Server)      │              │
│  └────────┬─────────┘     └────────┬─────────┘              │
│           │                        │                         │
│           ▼                        ▼                         │
│  ┌──────────────────┐     ┌──────────────────┐              │
│  │  M2M Client Lib   │◀───▶│  M2M Server Lib   │              │
│  │  (Clojure/BB)     │     │  (Clojure/BB)     │              │
│  └────────┬─────────┘     └────────┬─────────┘              │
│           │                        │                         │
│           ▼                        ▼                         │
│  ┌──────────────────┐     ┌──────────────────┐              │
│  │   DNS / Directory │     │  Job Posting DB   │              │
│  │   (Discovery)     │     │  (JSON-LD Store)  │              │
│  └──────────────────┘     └──────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## Component Diagram

```
headhunter-agent/
│
├── src/career_ops/
│   ├── core.clj           ◄── CLI router (existing)
│   ├── evaluator.clj      ◄── MAS pipeline (existing)
│   ├── profiler.clj       ◄── Profile extraction (existing)
│   ├── pdf.clj            ◄── Resume compilation (existing)
│   ├── tracker.clj        ◄── Pipeline tracker (existing)
│   │
│   └── m2m/               ◄── NEW: M2M Protocol Module
│       ├── core.clj       ◄── CLI router for bb bb-m2m commands
│       ├── crypto.clj     ◄── Ed25519 keygen, sign, verify
│       ├── schema.clj     ◄── JSON-LD validation, canonicalization
│       ├── registry.clj   ◄── DNS TXT + directory lookup
│       ├── directory.clj  ◄── Babashka directory server (optional)
│       ├── fetch.clj      ◄── Job posting HTTP fetcher + validator
│       ├── submit.clj     ◄── Application package builder
│       └── verify.clj     ◄── Inbound signature verification
```

## Data Flow: Apply Command

```
bb bb-m2m apply https://employer.example/jobs/42

Step 1: DISCOVER
  registry/discover!("employer.example")
  │
  ├─ DNS TXT _m2m-apply.employer.example → endpoint + key
  └─ If DNS fails: HTTP directory search
  │
  ▼
Step 2: FETCH
  fetch/posting!("https://employer.example/m2m/v1/jobs/42")
  │
  ├─ HTTP GET → JSON-LD JobPosting
  ├─ Validate against JSON Schema
  └─ Verify employer public key matches DNS record
  │
  ▼
Step 3: EVALUATE (existing MAS pipeline)
  evaluator/evaluate-jd!(posting)
  │
  ├─ Stage 1: Legitimacy check
  ├─ Stage 2: Fit analysis → GO/NO-GO
  └─ Stage 3: Cheat sheet (optional)
  │
  ▼
Step 4: TAILOR (existing pipeline)
  pdf/generate-pdf-resume!(posting)
  │
  └─ Gemini-tailored → Typst compiled → PDF
  │
  ▼
Step 5: SIGN
  crypto/sign-attachment!(pdf-bytes, private-key)
  crypto/sign-payload!(package, private-key)
  │
  ├─ SHA-256 digest of PDF
  ├─ Ed25519 signature on digest
  └─ Ed25519 signature on whole application
  │
  ▼
Step 6: SUBMIT
  submit/send!("https://employer.example/m2m/v1/apply", package)
  │
  ├─ Multipart POST with JSON + PDF
  ├─ Receive signed Acknowledgment
  └─ Tracker update
  │
  ▼
Step 7: VERIFY receipt
  verify/receipt!(acknowledgment, employer-public-key)
  │
  └─ Validate Ed25519 on receipt
```

## Protocol Stack

```
┌─────────────────────────────────────────┐
│         Application Layer                │
│  Application Package, Acknowledgment    │
│  JSON-LD with Ed25519 signatures        │
├─────────────────────────────────────────┤
│         Presentation Layer               │
│  Canonical JSON, SHA-256 digests        │
│  Base64 encoding, JSON Schema validation │
├─────────────────────────────────────────┤
│         Session Layer                    │
│  M2M Handshake: key exchange, nonce    │
│  Token-less, signature-based            │
├─────────────────────────────────────────┤
│         Transport Layer                  │
│  HTTPS (TLS 1.3 mandatory)             │
│  DNS UDP/TCP for discovery              │
├─────────────────────────────────────────┤
│         Network Layer                    │
│  IPv4/IPv6                              │
└─────────────────────────────────────────┘
```

## State Machine: Application Lifecycle

```
                    ┌───────────┐
                    │  DISCOVER │
                    └─────┬─────┘
                          │
                          ▼
                    ┌───────────┐
              ┌────▶│  FETCHED  │◀────┐
              │     └─────┬─────┘     │
              │           │           │
              │           ▼           │
              │     ┌───────────┐     │
              │     │ EVALUATED │     │
              │     └─────┬─────┘     │
              │           │           │
              │     ┌─────▼─────┐     │
              │     │  NO-GO    │─────┘  (skip)
              │     └───────────┘
              │           │ GO
              │           ▼
              │     ┌───────────┐
              │     │ TAILORED  │
              │     └─────┬─────┘
              │           │
              │           ▼
              │     ┌───────────┐
              │     │  SIGNED   │
              │     └─────┬─────┘
              │           │
              │           ▼
              │     ┌────────────┐
              │     │ SUBMITTED  │
              │     └─────┬──────┘
              │           │
              │           ▼
              │     ┌──────────────┐
              │     │ ACKNOWLEDGED │
              │     └──────┬───────┘
              │            │
              │     ┌──────▼───────┐
              │     │ IN_REVIEW    │
              │     └──────┬───────┘
              │            │
              │     ┌──────▼───────┐
              │     │  RESOLVED    │ (offer / rejection)
              │     └──────────────┘
              │
              └──── (re-apply / retry)
```
