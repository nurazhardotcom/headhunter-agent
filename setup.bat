@echo off
echo ⚡ Setting up headhunter-agent...

:: 1. Check/Install Babashka
where bb >nul 2>nul
if %errorlevel% neq 0 (
    echo 📥 Installing Babashka...
    powershell -Command "iwr https://raw.githubusercontent.com/babashka/babashka/master/install.ps1 | iex"
)

:: 2. Check/Install Typst
where typst >nul 2>nul
if %errorlevel% neq 0 (
    echo 📥 Installing Typst via winget...
    winget install Typst.Typst
)

:: 3. Create .env file template
if not exist .env (
    echo 📝 Creating .env template...
    echo GEMINI_API_KEY=your_key_here > .env
)

:: 4. Copy templates to active files
if not exist cv.md (
    copy cv.example.md cv.md
    echo 📝 Created cv.md from example.
)

if not exist config\profile.yml (
    mkdir config 2>nul
    copy config\profile.example.yml config\profile.yml
    echo 📝 Created config/profile.yml from example.
)

if not exist modes\_profile.md (
    mkdir modes 2>nul
    copy modes\_profile.example.md modes\_profile.md
    echo 📝 Created modes/_profile.md from example.
)

echo 🎉 Setup complete!
echo 👉 Instructions:
echo 1. Get a free API key at https://aistudio.google.com/apikey and add it to the .env file.
echo 2. Edit cv.md, config/profile.yml, and modes/_profile.md with your own resume details.
echo 3. Run 'bb evaluate --file jds/sample-jd.txt' to run a test evaluation!
pause
