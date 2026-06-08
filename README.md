# Headhunter-Agent (Local Jack & Jill Command Center)

`headhunter-agent` has evolved from a generic ATS tailoring script into a **Local-First, Privacy-Preserving Multi-Agent System (MAS)** built for deep candidate profiling and strategic job evaluation. 

It is designed to run entirely locally in your browser as a stateless Web GUI, using your own API key to ensure zero data leakage.

## 🚀 Deployed Web GUI

For a frictionless experience without terminal setup, use the Web GUI. It is completely static, serverless, and runs directly in the browser:

👉 **Web URL**: [https://headhunter.nurazhar.com](https://headhunter.nurazhar.com)

### How it works:
1. **Stateless Runtime**: Built in ClojureScript Reagent and interpreted dynamically in-browser via **Scittle** (meaning zero Node compilation or build-tool overhead).
2. **Local Storage**: Your Gemini API Key, Master Profile, and STAR Stories are saved only inside your browser's private `localStorage`.
3. **In-Browser Typst Compilation**: Uses **Typst WebAssembly** (`typst.ts`) to render and compile ATS-friendly PDF resumes directly in the browser tab.

---

## 🧠 Core Features

### 1. The Data Vault (Deep Profiler & STAR Story Extraction)
Instead of manually typing out a resume, you can paste unstructured LinkedIn data or raw CV text into the **Data Vault**. 
- It uses Gemini to extract a highly structured `Master Profile`.
- It parses your experience to automatically build an **8-12 STAR Story Library**, mapping out your Situations, Tasks, Actions, and Results for behavioral interviews.

### 2. Multi-Agent Evaluator (Company Deep Dives)
Evaluating a Job Description triggers a 3-stage asynchronous agent pipeline:
- **Agent 1 (Matcher):** Parses the JD, identifies the archetype, and checks MyCareersFuture legitimacy.
- **Agent 2 (Benchmark):** Performs a brutal fit analysis, comparing your exact Master Profile gaps and strengths against the JD.
- **Agent 3 (Deep Dive Memo):** Generates a pre-interview cheat sheet covering the company's business model, suspected tech stack, and suggests specific titles for cold outreach.

### 3. Interview Prep Center
A dedicated engine that cross-references the current JD against your Data Vault's STAR Story Library.
- Generates the 5 most likely interview questions for the specific role.
- Maps the best STAR story for each question and provides actionable delivery advice.

---

## Philosophy

* **Factual & Evidence-Based (No Hallucinations)**: The system restricts all outputs and evaluations to facts present in your Data Vault.
* **Privacy by Design**: Your API keys and data are stored strictly on your machine.
* **Aggregator, Not a Writer**: Instead of just rewriting resumes with ATS keywords, this acts as a deep strategic aggregator to prepare you for the entire hiring funnel—from initial fit analysis to the final interview.

---

## 💻 Local Development

If you wish to modify the application, you can run a local server:

1. Clone the repository.
2. Ensure you have `python3` or `babashka` installed.
3. Start a local server in the `docs/` directory:
   ```bash
   cd docs
   python3 -m http.server 8000
   # or with babashka:
   # bb -Sdeps '{:deps {babashka/fs {:mvn/version "0.4.18"}}}' -e "(require '[babashka.process :refer [sh]]) (sh \"python3 -m http.server 8000\")"
   ```
4. Open `http://localhost:8000` in your browser.
