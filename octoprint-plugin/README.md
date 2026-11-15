# OctoShow plugin for OctoPrint

A small OctoPrint plugin that lets the iPhone app ask the Echo Show display to switch
everything off: the same careful sequence the display runs from its own power button, started
from your phone, at home or away.

It only passes messages. The Echo Show still makes every decision: it refuses while a print is
running, waits for the nozzle to cool, has OctoPrint shut the Pi down, and cuts the power only
once the Pi has gone quiet. The plugin can't switch anything off by itself.

Both apps work without it. Without it, the iPhone app simply has no power button.

## Installing it

It is one file, `octoshow.py`, and goes in OctoPrint's own plugins folder. On OctoPi, sign in to
the Pi over SSH as the user OctoPrint runs as (`pi` unless you chose another name when you wrote
the SD card), and paste:

```sh
mkdir -p ~/.octoprint/plugins
curl -fsSL https://raw.githubusercontent.com/DrazanProjects/OctoShow/main/octoprint-plugin/octoshow.py \
  -o ~/.octoprint/plugins/octoshow.py
```

That address only works once the plugin is on the `main` branch. Until then, copy the file
across from a computer with this repository: `scp octoprint-plugin/octoshow.py pi@octopi.local:.octoprint/plugins/`.

Then restart OctoPrint so it picks the plugin up. **Not while a print is running**: restarting
OctoPrint stops the print.

```sh
sudo service octoprint restart
```

Once OctoPrint is back, Settings › Plugin Manager lists "OctoShow". If it doesn't, the reason is
in OctoPrint's log: `grep -i octoshow ~/.octoprint/logs/octoprint.log`.

To remove it, delete `~/.octoprint/plugins/octoshow.py` and restart OctoPrint the same way.

## How the messages go

```
iPhone ── POST power ──▶ plugin ── socket "request" ──▶ Echo Show
iPhone ◀── socket "display" ── plugin ◀── POST status ── Echo Show
```

The Echo Show is always connected to OctoPrint's push socket, and the phone reaches OctoPrint
at home and over a VPN, so OctoPrint is the one place both can always reach. Each side uses the
application key it already has; nothing new listens on the network.

A key whose user lacks the permission a call needs gets `403`, and so does a `GET` with no key
or a key OctoPrint doesn't know. A `POST` with no key or an unknown one never reaches the
plugin: OctoPrint itself answers `400`, "CSRF validation failed".

The socket passes plugin messages only to clients signed in with the Status permission, and a
client that has sent `subscribe` gets them only for the plugins it names there, so each app
names `octoshow` in its list.

### `GET /api/plugin/octoshow`

Who is listening. Needs the Status permission.

```json
{"display": {"name": "Echo Show", "plug": true, "plugOn": true, "canGoOff": true,
             "offWhenDone": false, "step": null, "age": 12}}
```

`display` is `null` when no display has reported in the last 90 seconds. `age` is how many
seconds ago it last did.

### `POST /api/plugin/octoshow`, `status`, from the display

The display reports itself every 30 seconds while it is connected, and straight away whenever
any of this changes. Needs the System permission (the one that may shut the Pi down).

```json
{"command": "status", "name": "Echo Show", "plug": true, "plugOn": true, "canGoOff": true,
 "offWhenDone": false, "step": {"kind": "cooling", "nozzle": 112}}
```

| Field | Meaning |
| --- | --- |
| `name` | what the display calls itself |
| `plug` | a smart plug is set up on the display |
| `plugOn` | what the plug last said: `true`, `false`, or `null` when it hasn't answered |
| `canGoOff` | the display switches itself off at the end too |
| `offWhenDone` | everything goes off once the running print finishes |
| `step` | how far a switch-off has got, or `null` when none is under way |

`step.kind` is one of:

| `kind` | Extra fields | Meaning |
| --- | --- | --- |
| `confirm` | | asked on the display, waiting for its hold |
| `afterPrint` | `seconds` | the print finished; the sequence starts in `seconds` |
| `cooling` | `nozzle` | heaters off, waiting for the nozzle (°C) to drop below 50° |
| `shuttingDown` | | OctoPrint has been told to shut the Pi down |
| `settling` | `seconds` | the Pi has gone quiet; the power goes in `seconds` |
| `off` | | the plug has cut the power |
| `failed` | `message` | stopped, with everything left on, and why |

`plugOn` and `step` may be left out, which counts as `null` (Android's `JSONObject` drops a key
put with `null`), and so may a step's extra field. Anything else not as above, a `name` over 64
characters or a `message` over 300, gets `400` with the reason, and the last good status stands.

The plugin answers `204` and passes the status on to every socket client as a plugin message:

```json
{"plugin": "octoshow", "data": {"type": "display", "display": {"name": "Echo Show", "...": "...", "age": 0}}}
```

### `POST /api/plugin/octoshow`, `power`, from the phone

Needs the System permission.

```json
{"command": "power", "action": "off"}
```

| `action` | What the display does |
| --- | --- |
| `off` | switches everything off now, as if its own power button had been held |
| `offWhenDone` | arms "switch off when done" for the running print |
| `stay` | leaves everything on: disarms "when done", and backs out of a switch-off that hasn't yet told the Pi to shut down |

The plugin answers `204` and passes the request on to every socket client:

```json
{"plugin": "octoshow", "data": {"type": "request", "action": "off"}}
```

It answers `400` for an action it doesn't know, and `409` when no display has reported in the
last 90 seconds, since nothing would hear the request. A display that hears one it can't act on (a print running, no plug, OctoPrint not
answering) says why through `step` with `kind: failed`, as it does on its own screen.
