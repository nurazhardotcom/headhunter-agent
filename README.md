# Headhunter-Agent (Clojure/Babashka + Typst)

An AI-powered job search assistant and resume compiler running 100% locally in your terminal.

Built using **Clojure (running on Babashka)** and **Typst** for typesetting, it evaluates job descriptions using the Google Gemini API, tracks application history in a local markdown database, and compiles tailored ATS-optimized resumes.

---

## Features

* **Instant JD Evaluation**: Paste a job description (or pass a file) and get an A-F compatibility grade, gap analysis, and recommended STAR behavior interview story outline.
* **ATS Resume Tailoring**: Automatically injects relevant keywords from the JD into your professional summary and experience bullets (fact-based only, no hallucinations) and compiles a pixel-perfect PDF using Typst in milliseconds.
* **Auto-Tracking**: Automatically logs applications to `data/applications.md` and updates PDF compile status once generated.
* **Zero Bloat**: No Node.js, no Playwright, no heavy JVM footprint. Runs on interpreted Babashka (GraalVM) and a single Typst native binary.

---

## Quick Start

### 1. Run the Setup Script
Clone this repository and run the setup script for your operating system:

* **Linux / macOS**:
  ```bash
  chmod +x setup.sh
  ./setup.sh
  ```
* **Windows**:
  Double-click `setup.bat` (or run it via CMD/PowerShell).

This will automatically check for and install **Babashka** and **Typst** if they are missing, create a `.env` template, and copy example configurations into active files.

### 2. Configure Your Profile
1. **API Key**: Get a free Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) and add it to your `.env` file:
   ```env
   GEMINI_API_KEY=your_actual_key_here
   ```
2. **Resume & Profile**:
   * Edit `cv.md` with your master resume.
   * Edit `config/profile.yml` with your contact details.
   * Edit `modes/_profile.md` with your target role archetypes and narrative context.

---

## Usage Commands

* **Get Help**:
  ```bash
  bb
  ```
* **Evaluate a Job Description**:
  ```bash
  bb evaluate --file jds/sample-jd.txt
  ```
* **Compile a Tailored Resume PDF**:
  ```bash
  bb pdf "Company Name" --file jds/sample-jd.txt
  ```
* **List Application Pipeline Tracker**:
  ```bash
  bb tracker list
  ```
