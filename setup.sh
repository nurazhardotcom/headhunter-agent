#!/usr/bin/env bash
set -euo pipefail

echo "⚡ Setting up headhunter-agent..."

# 1. Install Babashka if missing
if ! command -v bb &> /dev/null; then
    echo "📥 Installing Babashka..."
    curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
    chmod +x install
    ./install --user
    rm install
    export PATH="$HOME/.local/bin:$PATH"
fi

# 2. Install Typst if missing
if ! command -v typst &> /dev/null; then
    echo "📥 Installing Typst..."
    mkdir -p "$HOME/.local/bin"
    curl -L -o /tmp/typst.tar.xz https://github.com/typst/typst/releases/download/v0.14.2/typst-x86_64-unknown-linux-musl.tar.xz
    tar -xf /tmp/typst.tar.xz -C /tmp/ --strip-components=1
    mv /tmp/typst "$HOME/.local/bin/typst"
    chmod +x "$HOME/.local/bin/typst"
    export PATH="$HOME/.local/bin:$PATH"
fi

# 3. Create .env file template
if [ ! -f .env ]; then
    echo "📝 Creating .env template..."
    echo "GEMINI_API_KEY=your_key_here" > .env
fi

# 4. Copy templates to active files
if [ ! -f cv.md ]; then
    cp cv.example.md cv.md
    echo "📝 Created cv.md from example."
fi

if [ ! -f config/profile.yml ]; then
    mkdir -p config
    cp config/profile.example.yml config/profile.yml
    echo "📝 Created config/profile.yml from example."
fi

if [ ! -f modes/_profile.md ]; then
    mkdir -p modes
    cp modes/_profile.example.md modes/_profile.md
    echo "📝 Created modes/_profile.md from example."
fi

echo "🎉 Setup complete!"
echo "👉 Instructions:"
echo "1. Get a free API key at https://aistudio.google.com/apikey and add it to the .env file."
echo "2. Edit cv.md, config/profile.yml, and modes/_profile.md with your own resume details."
echo "3. Run 'bb evaluate --file jds/sample-jd.txt' to run a test evaluation!"
