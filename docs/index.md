# Headhunter-Agent Documentation

Welcome to the `headhunter-agent` documentation.

`headhunter-agent` is an open-source, lightweight, privacy-first job search assistant and resume compiler designed for the terminal. It helps you evaluate job descriptions, extract STAR stories from your experience, and compile tailored ATS-optimized resumes using **Babashka (Clojure)** and **Typst**.

## Architecture

This tool operates as a strict **Local CLI**.

Your data (API keys, Master Profile, STAR stories, tracker) never leaves your local machine.

### Key Features
1. **Data Vault Extraction**: Parses your raw LinkedIn dump into a highly structured `master-profile.edn` and generates an `8-12 STAR Story Library`.
2. **Multi-Agent Evaluator**: Evaluates a Job Description via a 3-stage agentic pipeline, producing Fit Analysis and Pre-Interview Cheat Sheets directly in your terminal.
3. **Interview Prep Generator**: Cross-references your STAR stories against a Job Description to predict interview questions and map your answers.
4. **Typst PDF Compiler**: Compiles an ATS-friendly, tailored PDF resume locally.

Please refer to the repository's `README.md` for installation and CLI usage instructions.
