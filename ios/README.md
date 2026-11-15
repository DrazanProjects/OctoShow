# OctoShow for iPhone

Your 3D printer's OctoPrint, from your phone, at home or away. It shows how the print is going,
draws the part layer by layer as it is printed, shows the camera, starts, pauses and cancels
jobs, and covers heat, moving the head, feeding filament, the part fan and the lights. It
works on its own: all it needs is OctoPrint. The [Echo Show display](../android/README.md) is
a separate app, and the two get along when you have both.

It has an application key of its own, one push connection to OctoPrint, and the Pi's
self-signed certificate pinned at setup. What OctoPrint needs, and what the optional plugins
add, is on the repository's [front page](../README.md#what-you-need).

Switching the printer and the Pi off through a smart plug is the Echo Show display's job: the
sequence can take twenty minutes of waiting for the nozzle to cool, and iOS stops an app long
before that once it leaves the screen. With the display and the small
[OctoShow plugin](../octoprint-plugin/README.md) in OctoPrint, the phone can ask the display to do
it, now or when the print is done, and follow along. The power button appears beside the
connection on the status screen only while a display with a plug is listening.

## Putting it on your iPhone

You need a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). The Xcode project is made from `project.yml`:

```
cd ios
xcodegen
open OctoShow.xcodeproj
```

Then, once:

1. In Xcode's settings, under Accounts, sign in with your Apple ID. A free account is enough.
2. Select the OctoShow target, then Signing & Capabilities, and pick your Personal Team. If
   Xcode says the bundle identifier is taken, change `com.drazan.octoshow.ios` to something of
   your own.
3. Plug the iPhone in (or pair it over Wi‑Fi), choose it as the run destination and press Run.
   The first time, iOS asks you to trust the developer: Settings › General › VPN & Device
   Management.
4. On the iPhone, switch on Developer Mode when asked (Settings › Privacy & Security), which
   restarts it.

**With a free account the app stops opening after seven days.** Press Run in Xcode again to
renew it; what the app has stored is kept. A paid developer account ($99 a year) makes that a
year.

## First run

Be on the same Wi‑Fi as the Pi. iOS asks whether OctoShow may find devices on your network:
allow it. Pick your OctoPrint from the list or type its address, then approve the request from
"OctoShow iPhone" in OctoPrint's web page, signed in as an admin. "I have a key" takes an
application key from OctoPrint's settings instead.

## Away from home

OctoPrint only answers on your home network. Settings › Away from home takes a second address
that the phone uses whenever the home one doesn't answer, and the badge on the status screen
says which it is using. Away from home the camera sends a still every two seconds instead of
live video, to spare mobile data and relay allowances.

| | What to enter | Notes |
| --- | --- | --- |
| **Tailscale** (recommended) | VPN: the Pi's tailnet name or 100.x address | Free. Install it on the Pi and the phone. The phone's VPN slot is the only cost. |
| ZeroTier, your own WireGuard | VPN: the Pi's address on that network | WireGuard on a router needs a port forward and a public IP (not CGNAT). |
| **Cloudflare Tunnel** | Cloudflare: your hostname, plus an Access service token | No VPN on the phone. Needs a domain on Cloudflare and `cloudflared` on the Pi, with an Access policy that has a Service Auth rule for the token. |
| OctoEverywhere, Obico | Relay: the address the service gives apps, and its user name and password if it has them | Traffic passes through their servers. Free tiers have data caps, and drawing the part downloads the whole G-code file. |

Never forward a port straight to OctoPrint: it isn't made to face the internet.

## Alerts when the app is closed

iOS cuts an app's connection soon after it leaves the screen, and push notifications of the
app's own need a paid developer account. So OctoPrint sends the alerts itself, through
[ntfy](https://ntfy.sh)'s free app: Settings › Alerts makes up a private topic and gives you
the lines to add to OctoPrint's `config.yaml`. You'll hear when a print finishes, fails or pauses
(which is what a filament runout does), or the printer reports an error, and tapping the alert
opens OctoShow.

## Working on it

Everything can be tried without a phone or a printer, against the stand-in OctoPrint in
[`tools/mock-octoprint`](../tools/mock-octoprint). From the top of the repository, start one
copy of it on 5001, since macOS usually has port 5000 for AirPlay:

```
cd tools/mock-octoprint
npm install
node server.js 5001
```

In the Simulator, set up with the address `127.0.0.1:5444` (its https port, with a self-signed
certificate like a Pi's). `http://127.0.0.1:5001/mock/set?scene=printing` and the rest of the
scenes listed at the top of `server.js` put the printer into any state.

Add `&echoshow=1` to a scene for an Echo Show listening through the plugin: the mock plays the
whole switch-off when the phone asks, ending with OctoPrint going quiet as a real Pi does.

The tests run on the Simulator; those that need the mock are skipped when it isn't running:

```
xcodebuild -project OctoShow.xcodeproj -scheme OctoShow -destination 'platform=iOS Simulator,name=iPhone 17' test
```

The part drawing is its own Swift package, `Packages/PartKit`, a port of the display's
`GcodePreview`, `Nozzle` and `Previews`. Its tests run on the Mac with `swift test` in that
folder.

```
ios/
  project.yml          the Xcode project, for XcodeGen
  OctoShow/
    Octo/              OctoPrint: REST, push socket, certificate pinning, discovery, camera
    App/               the app, one session per OctoPrint, preferences
    Screens/           status, files, controls, settings, alerts, setup
    UI/                theme, type, components
  OctoShowTests/       unit tests, and tests against the mock
  Packages/PartKit/    drawing the part from its G-code
```
