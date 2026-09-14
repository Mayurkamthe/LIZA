import math
from pathlib import Path

import requests

import memory


def _embed(cfg, text):
    r = requests.post(
        cfg["ollama_embed_url"],
        json={"model": cfg["embed_model"], "prompt": text},
        timeout=60,
    )
    r.raise_for_status()
    return r.json()["embedding"]


def _cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def ingest_notes(cfg):
    """Chunk and embed every .md file under cfg['notes_dir']. Requires an
    embedding model pulled in Ollama, e.g.: ollama pull nomic-embed-text"""
    notes_dir = Path(cfg["notes_dir"])
    if not notes_dir.exists():
        return 0
    memory.clear_notes()
    count = 0
    for path in notes_dir.rglob("*.md"):
        text = path.read_text(errors="ignore")
        chunks = [text[i : i + 800] for i in range(0, len(text), 800)]
        for chunk in chunks:
            if not chunk.strip():
                continue
            try:
                emb = _embed(cfg, chunk)
            except Exception:
                continue
            memory.add_note_chunk(str(path), chunk, emb)
            count += 1
    return count


def search_notes(cfg, query, top_k=3):
    try:
        q_emb = _embed(cfg, query)
    except Exception:
        return []
    scored = []
    for source, chunk, emb in memory.all_note_chunks():
        scored.append((_cosine(q_emb, emb), source, chunk))
    scored.sort(reverse=True, key=lambda x: x[0])
    return scored[:top_k]
