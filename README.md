# Headhunter-Agent (Local Desktop MAS Console)

`headhunter-agent` is a **Local-First, Privacy-Preserving Multi-Agent System (MAS)** built for deep candidate profiling, strategic job evaluation, and interview preparation. 

It is designed to run entirely locally as a native **Clojure Desktop GUI** using **`cljfx` (JavaFX)**. No web browsers, no local web servers, and zero JavaScript bloat.

---

## 🧠 Core Features

### 1. The Data Vault (Deep Profiler & STAR Story Extraction)
Instead of manually typing out a resume, you paste unstructured LinkedIn data or raw CV text into the **Data Vault**. 
- Extracts a highly structured `Master Profile`.
- Automatically builds an **8-12 STAR Story Library**, mapping out your Situations, Tasks, Actions, and Results for behavioral interviews.
- Saves data locally as `.edn` files (`data/master-profile.edn` and `data/star-stories.edn`).

### 2. Multi-Agent Evaluator (3-Stage Pipeline)
Evaluating a Job Description triggers a sequential multi-agent pipeline:
- **Agent 1 (Legitimacy):** Parses the JD and checks Fair Consideration Framework (FCF) legitimacy.
- **Agent 2 (Fit Analysis):** Performs a brutal fit analysis, comparing your exact Master Profile gaps and strengths against the JD.
- **Agent 3 (Cheat Sheet):** Generates a pre-interview cheat sheet covering the company's business model, suspected tech stack, and suggests specific titles for cold outreach.
- Compiles the entire pipeline output into a Markdown report under `reports/`.

### 3. Interview Prep Center
A dedicated engine that cross-references the current JD against your Data Vault's STAR Story Library.
- Generates the 5 most likely interview questions for the specific role.
- Maps the best STAR story for each question and provides actionable delivery advice.

---

## 🛠️ Installation & Setup

### Prerequisites
1. **Java Development Kit (JDK 11 or higher)**.
2. **Clojure CLI** (to run the Desktop GUI).
3. **Babashka** (optional, for CLI fallbacks).

### Setup
1. Clone the repository:
   ```bash
   git clone https://gitlab.com/nurazhar/headhunter-agent.git
   cd headhunter-agent
   ```
2. Create a `.env` file in the root directory and add your Gemini API key:
   ```env
   GEMINI_API_KEY=your_actual_gemini_api_key_here
   ```

---

## 🚀 How to Run

### 1. Launch the Native Desktop GUI
Start the desktop application using Clojure:
```bash
clj -M:run
```
This opens a native desktop window with tabs for **Data Vault**, **Evaluate JD**, and **Interview Prep**. All data stays 100% local on your disk.

### 2. Run via Babashka CLI (Fallback)
If you prefer running headless or automating via terminal:

- **Extract Profile:**
  ```bash
  bb profile --extract /path/to/linkedin-dump.txt
  ```
- **Evaluate Job Description:**
  ```bash
  bb evaluate --file ./jds/defence-collective.txt
  ```
- **Generate Interview Strategy:**
  ```bash
  bb interview --file ./jds/defence-collective.txt
  ```

---

## Philosophy

* **Factual & Evidence-Based**: The system restricts evaluations and strategies strictly to the facts present in your local Data Vault.
* **Privacy by Design**: Your API keys, Master Profile, and STAR stories are stored strictly as local files. No analytics, no tracking, no cloud databases.
* **Zero Web Stack**: Desktop components are rendered natively, eliminating browser-based state management bugs and NPM dependency bloat.
