import threading
import time

import memory


def start(speak_fn, bridge=None, interval=30):
    """Background thread that, every `interval` seconds:
    - speaks any due reminders
    - sends any due scheduled messages (e.g. WhatsApp) via the bridge
    """

    def loop():
        while True:
            try:
                for _id, message in memory.due_reminders(time.time()):
                    speak_fn(f"Reminder: {message}")

                if bridge is not None:
                    for _id, channel, target, message in memory.due_scheduled_messages(time.time()):
                        if channel == "whatsapp":
                            res = bridge.whatsapp_send(target, message)
                            ok = res.get("ok")
                            speak_fn(
                                f"Sent your scheduled WhatsApp message to {target}."
                                if ok
                                else f"Couldn't send the scheduled WhatsApp message to {target}."
                            )
            except Exception:
                pass
            time.sleep(interval)

    t = threading.Thread(target=loop, daemon=True)
    t.start()
    return t
