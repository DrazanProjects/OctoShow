# OctoShow plugin for OctoPrint.
#
# Passes messages between the iPhone app and the Echo Show display over OctoPrint's push
# socket: the display says how it stands, and the phone asks it to switch everything off.
# The plugin never switches anything off itself; the display makes every decision.
# README.md beside this file describes the messages.
#
# Install it by copying this one file to ~/.octoprint/plugins/ and restarting OctoPrint.

import math
import time

import flask
import octoprint.plugin
from octoprint.access.permissions import Permissions

# A display that hasn't reported for this long is taken to be gone. It reports every 30 seconds.
FRESH_FOR = 90

# What the phone may ask the display to do.
ACTIONS = ("off", "offWhenDone", "stay")

# How far a switch-off has got, and the one extra field each kind of step carries, if any.
STEPS = {
    "confirm": None,
    "afterPrint": "seconds",
    "cooling": "nozzle",
    "shuttingDown": None,
    "settling": "seconds",
    "off": None,
    "failed": "message",
}


class OctoShowPlugin(octoprint.plugin.SimpleApiPlugin):
    def __init__(self):
        super().__init__()
        # The display's last status and when it came, on a clock that only runs forwards.
        # They are kept as one pair and replaced together, so a request never sees half of each.
        self._last = None

    # OctoPrint turns away anyone not signed in; each request then checks its own permission.
    def is_api_protected(self):
        return True

    def get_api_commands(self):
        # The fields each command must carry; OctoPrint answers 400 when one is missing.
        # plugOn and step may be left out when they are null: Android's JSONObject drops a key
        # that is put with null.
        return {
            "status": ["name", "plug", "canGoOff", "offWhenDone"],
            "power": ["action"],
        }

    def on_api_get(self, request):
        if not Permissions.STATUS.can():
            flask.abort(403)
        return flask.jsonify(display=self._display())

    def on_api_command(self, command, data):
        # Both commands can end with the Pi shut down and the power cut, so both need the
        # System permission: the one that may shut the Pi down.
        if not Permissions.SYSTEM.can():
            flask.abort(403)
        if command == "status":
            return self._status(data)
        return self._power(data["action"])

    def _status(self, data):
        """From the display: keep what it says, and pass it on to everyone listening."""
        try:
            status = {
                "name": _text(data["name"], "name", 64),
                "plug": _flag(data.get("plug"), "plug"),
                "plugOn": _flag(data.get("plugOn"), "plugOn", may_be_null=True),
                "canGoOff": _flag(data.get("canGoOff"), "canGoOff"),
                "offWhenDone": _flag(data.get("offWhenDone"), "offWhenDone"),
                "step": _step(data.get("step")),
            }
        except ValueError as e:
            return _error(str(e), 400)

        self._last = (status, time.monotonic())
        self._send({"type": "display", "display": dict(status, age=0)})
        # returning nothing has OctoPrint answer 204

    def _power(self, action):
        """From the phone: pass the request on to the display, if one is listening."""
        if action not in ACTIONS:
            return _error("action must be off, offWhenDone or stay", 400)
        if self._display() is None:
            return _error(
                "No display has reported in the last {} seconds".format(FRESH_FOR), 409
            )

        self._logger.info("Asking the display to: {}".format(action))
        self._send({"type": "request", "action": action})

    def _display(self):
        """The display's last status with its age in seconds, or None when it has gone quiet."""
        last = self._last
        if last is None:
            return None
        status, seen = last
        age = time.monotonic() - seen
        if age > FRESH_FOR:
            return None
        return dict(status, age=int(age))

    def _send(self, data):
        # Reaches every push socket client allowed to see status, as {"plugin": "octoshow", "data": ...}
        self._plugin_manager.send_plugin_message(self._identifier, data)


# ~~ checking what the display sends; each raises ValueError with the reason


def _flag(value, field, may_be_null=False):
    if isinstance(value, bool) or (value is None and may_be_null):
        return value
    raise ValueError(
        "{} must be true or false{}".format(field, " or null" if may_be_null else "")
    )


def _text(value, field, longest):
    if isinstance(value, str) and 0 < len(value.strip()) <= longest:
        return value.strip()
    raise ValueError("{} must be text of 1 to {} characters".format(field, longest))


def _number(value, field):
    # bool counts as a number in Python, and JSON as Python reads it allows NaN and Infinity
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
        return value
    raise ValueError("step.{} must be a number".format(field))


def _step(step):
    """How far a switch-off has got: null, or a known kind with its one extra field."""
    if step is None:
        return None
    kind = step.get("kind") if isinstance(step, dict) else None
    if not isinstance(kind, str) or kind not in STEPS:
        raise ValueError("step must be null or have a kind of " + ", ".join(STEPS))

    extra = STEPS[kind]
    if extra is None:
        return {"kind": kind}

    # The extra field may be null or left out; anything else must be the right type.
    value = step.get(extra)
    if value is not None:
        value = _text(value, "step.message", 300) if extra == "message" else _number(value, extra)
    return {"kind": kind, extra: value}


def _error(message, code):
    return flask.make_response(flask.jsonify(error=message), code)


__plugin_name__ = "OctoShow"
__plugin_version__ = "0.1.0"
__plugin_description__ = (
    "Lets the OctoShow iPhone app ask the Echo Show display to switch the printer and the Pi off."
)
__plugin_author__ = "Drazan Potter"
__plugin_url__ = "https://github.com/DrazanProjects/OctoShow"
__plugin_license__ = "MIT"
__plugin_pythoncompat__ = ">=3.7,<4"
__plugin_implementation__ = OctoShowPlugin()
