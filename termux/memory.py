import json as _json
import sqlite3
from datetime import datetime
from pathlib import Path

DB = Path.home() / "jarvis" / "memory.db"


def _conn():
    DB.parent.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(DB)
    c.execute(
        """CREATE TABLE IF NOT EXISTS memories (
        id INTEGER PRIMARY KEY, key TEXT UNIQUE, value TEXT, updated TEXT)"""
    )
    c.execute(
        """CREATE TABLE IF NOT EXISTS turns (
        id INTEGER PRIMARY KEY, role TEXT, content TEXT, ts TEXT)"""
    )
    c.execute(
        """CREATE TABLE IF NOT EXISTS reminders (
        id INTEGER PRIMARY KEY, message TEXT, due_ts REAL, done INTEGER DEFAULT 0)"""
    )
    c.execute(
        """CREATE TABLE IF NOT EXISTS notes (
        id INTEGER PRIMARY KEY, source TEXT, chunk TEXT, embedding TEXT)"""
    )
    c.execute(
        """CREATE TABLE IF NOT EXISTS scheduled_messages (
        id INTEGER PRIMARY KEY, channel TEXT, target TEXT, message TEXT,
        due_ts REAL, done INTEGER DEFAULT 0)"""
    )
    return c


# --- key/value facts ---

def remember(key, value):
    with _conn() as c:
        c.execute(
            """INSERT INTO memories(key,value,updated) VALUES(?,?,?)
               ON CONFLICT(key) DO UPDATE SET
               value=excluded.value, updated=excluded.updated""",
            (key, value, datetime.now().isoformat()),
        )


def forget(key):
    with _conn() as c:
        c.execute("DELETE FROM memories WHERE key=?", (key,))


def recall(limit=20):
    with _conn() as c:
        return c.execute(
            "SELECT key,value FROM memories ORDER BY updated DESC LIMIT ?", (limit,)
        ).fetchall()


# --- conversation history ---

def add_turn(role, content):
    with _conn() as c:
        c.execute(
            "INSERT INTO turns(role,content,ts) VALUES (?,?,?)",
            (role, content, datetime.now().isoformat()),
        )


def recent_turns(limit=6):
    with _conn() as c:
        rows = c.execute(
            "SELECT role,content FROM turns ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    return list(reversed(rows))


# --- reminders ---

def add_reminder(message, due_ts):
    with _conn() as c:
        c.execute(
            "INSERT INTO reminders(message,due_ts,done) VALUES (?,?,0)",
            (message, due_ts),
        )


def due_reminders(now_ts):
    with _conn() as c:
        rows = c.execute(
            "SELECT id,message FROM reminders WHERE done=0 AND due_ts<=?", (now_ts,)
        ).fetchall()
        if rows:
            c.executemany(
                "UPDATE reminders SET done=1 WHERE id=?", [(r[0],) for r in rows]
            )
    return rows


# --- scheduled outgoing messages (e.g. WhatsApp) ---

def add_scheduled_message(channel, target, message, due_ts):
    with _conn() as c:
        c.execute(
            "INSERT INTO scheduled_messages(channel,target,message,due_ts,done) VALUES (?,?,?,?,0)",
            (channel, target, message, due_ts),
        )


def due_scheduled_messages(now_ts):
    with _conn() as c:
        rows = c.execute(
            "SELECT id,channel,target,message FROM scheduled_messages WHERE done=0 AND due_ts<=?",
            (now_ts,),
        ).fetchall()
        if rows:
            c.executemany(
                "UPDATE scheduled_messages SET done=1 WHERE id=?", [(r[0],) for r in rows]
            )
    return rows


# --- notes / RAG ---

def add_note_chunk(source, chunk, embedding):
    with _conn() as c:
        c.execute(
            "INSERT INTO notes(source,chunk,embedding) VALUES (?,?,?)",
            (source, chunk, _json.dumps(embedding)),
        )


def all_note_chunks():
    with _conn() as c:
        rows = c.execute("SELECT source,chunk,embedding FROM notes").fetchall()
    return [(s, ch, _json.loads(e)) for s, ch, e in rows]


def clear_notes():
    with _conn() as c:
        c.execute("DELETE FROM notes")
