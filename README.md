# JARVIS Android + Termux

Local Android assistant: Kotlin control app + Termux Python agent talking to
a local Ollama model, with real on-device control instead of just chat.

## Architecture

```
Termux (agent.py)  <--HTTP, token auth, 127.0.0.1:8734-->  Android app
   |                                                          |
   |-- Ollama (local LLM)                                     |-- JarvisAccessibilityService (tap/swipe/type/screen text)
   |-- memory.db (facts, conversation, reminders, notes)       |-- JarvisNotificationListenerService (recent notifications)
   |-- scheduler.py (reminders)                                 |-- JarvisBridgeService (foreground HTTP bridge + app launch)
   |-- rag.py (local notes search)                              |-- ActionLog (SQLite activity history) + ActivityLogActivity
```

## Building the APK without Android Studio

`.github/workflows/build.yml` builds a debug APK automatically via GitHub
Actions on every push. From a phone browser: create a GitHub repo, upload
this project's contents, commit -- the Actions tab will produce a
downloadable `app-debug.apk` a few minutes later (Actions run -> Artifacts).
No local Android SDK, Android Studio, or laptop required.

Alternative: build directly on-device in Termux with a command-line Android
SDK (`pkg install openjdk-17`, then `sdkmanager` + `gradle assembleDebug`).
It works, but downloading the SDK/build-tools on mobile data and ARM is slow
and occasionally flaky -- the GitHub Actions route above is more reliable.

## Setup

**Android app** (open `android/` in Android Studio, build & install):
1. Launch "JARVIS Control", tap **Open Accessibility Settings** and enable it.
2. Tap **Open Notification Access Settings** and enable it (optional, only
   needed for notification summaries).
3. Tap **Start Bridge Service** -- this starts a foreground service listening
   on `127.0.0.1:8734`.
4. Copy the token shown on screen.

**Termux:**
```bash
bash ~/JARVIS-Android-Termux/termux/install.sh
python ~/jarvis/agent.py   # first run creates ~/.jarvis/config.json
```
Edit `~/.jarvis/config.json`:
- `bridge_token`: paste the token from the Android app.
- `model`: any Ollama-compatible chat model (default `qwen3:4b`).
- `embed_model`: an Ollama embedding model, only needed for note search
  (e.g. `ollama pull nomic-embed-text`).
- `voice_mode`: `true` to use `termux-speech-to-text` instead of typed input.
- `notes_dir`: folder of `.md` notes to ingest for local RAG.

Start Ollama, then run `python ~/jarvis/agent.py`.

## Features

- Local LLM chat (Ollama) with rolling conversation context and persistent
  key/value memory (SQLite).
- Real device control: tap, swipe, type, back, home, open app by name --
  dispatched over the local HTTP bridge to the Accessibility Service.
- Screen reading ("what's on my screen") via Accessibility tree traversal.
- Notification summaries via `NotificationListenerService`.
- Reminders: `"remind me to X in N minutes"` -- a background thread checks
  and speaks them when due, even mid-conversation.
- Local notes search (RAG): embed a folder of markdown notes with Ollama and
  ask questions against them, fully offline.
- Voice input/output: `termux-tts-speak` for output, `termux-speech-to-text`
  for input when `voice_mode` is on. This is push-to-talk-per-turn, **not**
  a true always-on wake word -- that needs an offline hotword engine
  (openWakeWord/Porcupine) plus continuous audio streaming, which is a
  heavier addition left for later.
- Confirmation gate: `type`, `open_app`, `tap`, and `swipe` actions are
  described back to you and require a "yes" before they run, so the LLM
  can't silently type or tap on your behalf.
- Multi-action responses: the model can return a JSON array of actions to
  chain steps (still confirmed individually where required).
- Activity log: every bridge request is logged to SQLite and viewable from
  **View Activity Log** in the Android app.
- Foreground bridge service so it survives being backgrounded.
- Volume / brightness / flashlight / wifi toggles via `termux-api`.
- Generic app control via accessibility-tree node listing (`nodes`,
  `tap_node`, `type_node`) instead of fixed coordinates -- works across apps.
- WhatsApp message scheduling via deep link + auto-tap Send (see below).

## Generic app control (node-based)

Instead of guessing pixel coordinates, `screen`/`nodes` reads the actual
accessibility tree of whatever app is in front and returns every tappable or
editable element with an id and its visible text. The model can then say
"tap element 7" (`tap_node`) or "type into element 7" (`type_node`) --
this works the same way in any app, so it's the path to controlling apps
you haven't specifically coded for. Two limits worth knowing:
- The id snapshot is only valid until the screen next changes -- fetch
  `/nodes` again before acting if you're not sure the screen is the same.
- A small on-device LLM will be less reliable at multi-step UI navigation
  than a large cloud model. Works well for 1-2 step tasks ("open Settings
  and tap Wi-Fi"); longer chains need testing per app.

## WhatsApp message scheduling

`{"action":"schedule_whatsapp","phone":"+1...","message":"...","minutes":30}`
stores the request, then a background thread sends it when due via:
1. WhatsApp's `wa.me` deep link, which opens the chat with the message
   pre-filled (no need to search contacts or navigate WhatsApp's UI).
2. An automatic tap on the "Send" button (matched by its accessibility
   label), if the Accessibility Service is enabled.

**Read before relying on this:** WhatsApp's terms prohibit automated/bulk
messaging, and this is UI automation, not an official API -- it will break
if WhatsApp changes its layout, and repeated automated sends risk
WhatsApp's spam detection flagging your account. It's fine for occasional
personal reminders to yourself/contacts, the same category of thing tools
like Tasker do; it's not a substitute for WhatsApp's Business API if you
need reliable business-grade scheduled messaging.

## Fully offline

- **LLM**: already offline once a model is pulled -- Ollama runs on-device
  in Termux, no network calls. Pick a model that fits your phone's RAM
  (`qwen2.5:1.5b`, `gemma2:2b`, `phi3-mini` are reasonable starting points).
- **TTS**: offline already, via Android's built-in engine (download the
  voice pack once in Android Settings -> Text-to-speech).
- **STT**: `termux-speech-to-text` calls Android's `SpeechRecognizer`,
  which defaults to an *online* recognizer on most phones unless you've
  downloaded an offline language pack (Google app -> Settings -> Voice ->
  Offline speech recognition). For a hard offline guarantee, swap in
  **Vosk** (a local STT library with downloadable offline models) instead
  -- not included here, but a drop-in replacement for the `listen()`
  function in `agent.py` if you want it built out.

## Security notes

- The bridge only binds to `127.0.0.1` and requires a per-install random
  token in the `X-Jarvis-Token` header -- no other app or device can reach it.
- The starter deliberately does not expose arbitrary shell/root execution.
- Android 14+ (API 34) requires a declared foreground service type; this is
  set to `specialUse` with an explanatory subtype in the manifest.
