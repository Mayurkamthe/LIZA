import json

import requests


class Bridge:
    """Client for the local HTTP bridge exposed by the Android app's
    JarvisBridgeService (127.0.0.1 only, token-authenticated)."""

    def __init__(self, cfg):
        self.base = f"http://{cfg['bridge_host']}:{cfg['bridge_port']}"
        self.headers = {
            "X-Jarvis-Token": cfg["bridge_token"],
            "Content-Type": "application/json",
        }

    def action(self, data):
        return self._post("/action", data)

    def open_app(self, name=None, package=None):
        payload = {}
        if package:
            payload["package"] = package
        if name:
            payload["name"] = name
        return self._post("/open_app", payload)

    def screen_text(self):
        return self._get("/screen").get("text", "")

    def nodes(self):
        """List of {id, text, desc, clickable, editable, bounds} for every
        interactive/text element currently on screen -- use this to find a
        node id, then tap_node/type_node it, instead of guessing coordinates.
        Works the same way in any app."""
        return self._get("/nodes").get("nodes", [])

    def tap_node(self, node_id):
        return self.action({"action": "tap_node", "id": node_id})

    def type_node(self, node_id, text):
        return self.action({"action": "type_node", "id": node_id, "text": text})

    def notifications(self):
        return self._get("/notifications").get("notifications", [])

    def whatsapp_send(self, phone, message, auto_send=True):
        return self._post(
            "/whatsapp_send",
            {"phone": phone, "message": message, "auto_send": auto_send},
        )

    def _post(self, path, data):
        try:
            r = requests.post(
                self.base + path, headers=self.headers, data=json.dumps(data), timeout=10
            )
            return r.json()
        except Exception as e:
            return {"error": str(e)}

    def _get(self, path):
        try:
            r = requests.get(self.base + path, headers=self.headers, timeout=10)
            return r.json()
        except Exception as e:
            return {"error": str(e)}
