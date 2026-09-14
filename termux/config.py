import json
from pathlib import Path

CONFIG_PATH = Path.home() / ".jarvis" / "config.json"

DEFAULTS = {
    "ollama_url": "http://127.0.0.1:11434/api/chat",
    "ollama_embed_url": "http://127.0.0.1:11434/api/embeddings",
    "model": "qwen3:4b",
    "embed_model": "nomic-embed-text",
    "bridge_host": "127.0.0.1",
    "bridge_port": 8734,
    "bridge_token": "PASTE_TOKEN_FROM_ANDROID_APP",
    "voice_mode": False,
    "context_turns": 6,
    "notes_dir": str(Path.home() / "jarvis" / "notes"),
}


def load():
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    if not CONFIG_PATH.exists():
        CONFIG_PATH.write_text(json.dumps(DEFAULTS, indent=2))
        return dict(DEFAULTS)
    try:
        data = json.loads(CONFIG_PATH.read_text())
    except Exception:
        data = {}
    merged = dict(DEFAULTS)
    merged.update(data)
    return merged


def save(cfg):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))
