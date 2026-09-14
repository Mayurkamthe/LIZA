#!/usr/bin/env python3
import json
import re
import subprocess
import time

import requests

import config
import memory
import scheduler
from bridge_client import Bridge

CFG = config.load()
bridge = Bridge(CFG)

SYSTEM = """You are JARVIS, a female-voiced local Android assistant.
Respond with plain text, OR a single JSON action, OR a JSON array of actions
when the user requests one or more supported phone actions.

Supported actions:
{"action":"open_url","url":"https://..."}
{"action":"battery"}
{"action":"time"}
{"action":"remember","key":"...","value":"..."}
{"action":"forget","key":"..."}
{"action":"remind","message":"...","minutes":10}
{"action":"open_app","name":"App Name"}
{"action":"screen"}
{"action":"nodes"}
{"action":"tap_node","id":7}
{"action":"type_node","id":7,"text":"..."}
{"action":"notifications"}
{"action":"schedule_whatsapp","phone":"+15551234567","message":"...","minutes":30}
{"action":"note_search","query":"..."}
{"action":"tap","x":100,"y":200}
{"action":"swipe","x1":100,"y1":700,"x2":100,"y2":200}
{"action":"type","text":"..."}
{"action":"back"}
{"action":"home"}
{"action":"volume","level":50}
{"action":"brightness","level":50}
{"action":"torch","state":"on"}
{"action":"wifi","state":"on"}

Otherwise answer normally in plain text.
Never output shell commands or ask for passwords/secrets.
"""

# Actions that touch the screen, type on the user's behalf, or launch apps
# require an explicit "yes" before they run.
CONFIRM_ACTIONS = {"type", "open_app", "tap", "swipe", "tap_node", "type_node", "schedule_whatsapp"}
pending_action = None


def ask_llm(user_text):
    history = memory.recent_turns(CFG["context_turns"])
    convo = "\n".join(f"{role.upper()}: {content}" for role, content in history)
    mem = "\n".join(f"- {k}: {v}" for k, v in memory.recall())
    prompt = (
        f"{SYSTEM}\nLocal memory:\n{mem}\n\nRecent conversation:\n{convo}\n\nUSER:\n{user_text}"
    )
    r = requests.post(
        CFG["ollama_url"],
        json={
            "model": CFG["model"],
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "options": {"temperature": 0.2},
        },
        timeout=120,
    )
    r.raise_for_status()
    return r.json()["message"]["content"].strip()


def speak(text):
    try:
        subprocess.run(["termux-tts-speak", "-l", "en", "-r", "0.95", text])
    except Exception:
        pass


def listen():
    """Single-utterance voice capture via Termux:API. Not a true always-on
    wake word (that needs an offline hotword engine like openWakeWord/
    Porcupine plus continuous audio streaming) -- this blocks, listens once,
    and returns the transcript, so voice_mode behaves like push-to-talk in
    a loop."""
    try:
        out = subprocess.check_output(["termux-speech-to-text"], text=True, timeout=30)
        return out.strip()
    except Exception:
        return ""


def describe_action(data):
    action = data.get("action")
    if action == "type":
        return f"type '{data.get('text', '')}'"
    if action == "open_app":
        return f"open {data.get('name') or data.get('package')}"
    if action == "tap":
        return f"tap at ({data.get('x')}, {data.get('y')})"
    if action == "swipe":
        return "swipe on the screen"
    if action == "tap_node":
        return f"tap element {data.get('id')}"
    if action == "type_node":
        return f"type '{data.get('text', '')}' into element {data.get('id')}"
    if action == "schedule_whatsapp":
        return f"send a WhatsApp message to {data.get('phone')} in {data.get('minutes', 0)} minutes: '{data.get('message', '')}'"
    return str(action)


def run_single_action(data):
    action = data.get("action")

    if action == "remember":
        memory.remember(data.get("key", "unknown"), data.get("value", ""))
        return "Saved to local memory."

    if action == "forget":
        memory.forget(data.get("key", ""))
        return "Forgot that."

    if action == "time":
        return time.strftime("The time is %I:%M %p.")

    if action == "battery":
        try:
            b = json.loads(subprocess.check_output(["termux-battery-status"], text=True))
            return f"Battery is {b.get('percentage')} percent."
        except Exception as e:
            return f"Battery unavailable: {e}"

    if action == "remind":
        minutes = float(data.get("minutes", 5))
        memory.add_reminder(data.get("message", ""), time.time() + minutes * 60)
        return f"I'll remind you in {minutes:.0f} minutes."

    if action == "screen":
        text = bridge.screen_text()
        return f"On screen: {text[:400]}" if text else "Couldn't read the screen (is the bridge + accessibility service on?)."

    if action == "nodes":
        nodes = bridge.nodes()
        if not nodes:
            return "No interactive elements found."
        lines = [
            f"[{n['id']}] {(n.get('text') or n.get('desc') or '').strip()[:40]}"
            for n in nodes
            if (n.get("text") or n.get("desc"))
        ]
        return "Tappable elements: " + " | ".join(lines[:25])

    if action == "schedule_whatsapp":
        minutes = float(data.get("minutes", 0))
        memory.add_scheduled_message(
            "whatsapp", data.get("phone", ""), data.get("message", ""), time.time() + minutes * 60
        )
        return f"Scheduled a WhatsApp message to {data.get('phone')} in {minutes:.0f} minutes."

    if action == "notifications":
        notifs = bridge.notifications()
        if not notifs:
            return "No recent notifications."
        lines = [f"{n['app']}: {n.get('title', '')} {n.get('text', '')}" for n in notifs[-5:]]
        return "Recent notifications: " + " | ".join(lines)

    if action == "note_search":
        import rag

        results = rag.search_notes(CFG, data.get("query", ""))
        if not results:
            return "No matching notes found. Run rag.ingest_notes() first."
        return "From your notes: " + " ".join(chunk[:200] for _, _, chunk in results)

    if action == "volume":
        subprocess.run(["termux-volume", "music", str(data.get("level", 50))])
        return "Volume set."

    if action == "brightness":
        subprocess.run(["termux-brightness", str(data.get("level", 50))])
        return "Brightness set."

    if action == "torch":
        subprocess.run(["termux-torch", data.get("state", "off")])
        return "Torch toggled."

    if action == "wifi":
        subprocess.run(["termux-wifi-enable", data.get("state", "on")])
        return "Wifi toggled."

    if action == "open_app":
        res = bridge.open_app(name=data.get("name"), package=data.get("package"))
        return "Opened app." if res.get("ok") else f"Couldn't open app: {res.get('error')}"

    if action in {"tap", "swipe", "type", "back", "home", "tap_node", "type_node"}:
        res = bridge.action(data)
        return "Done." if res.get("ok") else f"Action failed: {res.get('error')}"

    return None


def execute(text):
    """Parses either a single JSON action object or a JSON array of actions
    out of the model's reply and runs them in order. Sensitive actions pause
    for user confirmation instead of executing immediately."""
    global pending_action

    m = re.search(r"(\[.*\]|\{.*\})", text, re.S)
    if not m:
        return text

    try:
        parsed = json.loads(m.group())
    except Exception:
        return text

    actions = parsed if isinstance(parsed, list) else [parsed]
    results = []
    for data in actions:
        if not isinstance(data, dict):
            continue
        action = data.get("action")
        if action in CONFIRM_ACTIONS:
            pending_action = data
            return f"About to {describe_action(data)}. Say yes to confirm or no to cancel."
        result = run_single_action(data)
        results.append(result if result is not None else text)

    return " ".join(results) if results else text


def handle_confirmation(user_text):
    global pending_action
    if user_text.strip().lower() in {"yes", "yeah", "yep", "confirm", "do it"}:
        data = pending_action
        pending_action = None
        return run_single_action(data) or "Done."
    pending_action = None
    return "Cancelled."


def main():
    global pending_action

    print("JARVIS local agent ONLINE")
    print("Model:", CFG["model"])
    print("Voice mode:", CFG["voice_mode"])
    print("Type exit to quit.")

    scheduler.start(speak, bridge=bridge)

    while True:
        try:
            if CFG["voice_mode"]:
                user = listen()
                if user:
                    print("You (voice):", user)
            else:
                user = input("\nYou: ").strip()
        except KeyboardInterrupt:
            break

        if not user:
            continue
        if user.lower() in {"exit", "quit"}:
            break

        memory.add_turn("user", user)

        try:
            if pending_action is not None:
                answer = handle_confirmation(user)
            else:
                response = ask_llm(user)
                answer = execute(response)
        except Exception as e:
            answer = f"Local AI error: {e}"

        memory.add_turn("assistant", answer)
        print("JARVIS:", answer)
        speak(answer)


if __name__ == "__main__":
    main()
