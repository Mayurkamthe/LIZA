#!/data/data/com.termux/files/usr/bin/bash
set -e
pkg update -y
pkg install -y python curl termux-api
python -m pip install --upgrade pip requests

mkdir -p "$HOME/jarvis"
mkdir -p "$HOME/jarvis/notes"
SRC="$(dirname "$0")"
for f in agent.py memory.py config.py rag.py scheduler.py bridge_client.py; do
    cp "$SRC/$f" "$HOME/jarvis/$f"
done

echo ""
echo "Installed."
echo "Next steps:"
echo "1. Install the 'Termux:API' companion app from F-Droid (required for"
echo "   battery/volume/brightness/torch/wifi/speech-to-text commands)."
echo "2. Run 'python ~/jarvis/agent.py' once to generate ~/.jarvis/config.json,"
echo "   then edit it and set bridge_token to the token shown in the JARVIS"
echo "   Android app (Main screen, after you start the bridge service)."
echo "3. Start Ollama on this device or your LAN, then run:"
echo "   python ~/jarvis/agent.py"
