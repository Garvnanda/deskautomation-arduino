# DeskMatrix — Arduino UNO Q Desk Companion

DeskMatrix turns the 13×8 red LED matrix on an Arduino UNO Q into a desk companion that shows the time, phone calls, messages, reminders and timers. It starts by itself on every power-on with no laptop and no App Lab.

The idea: a glanceable device next to the laptop that pulls attention only when something matters. When idle it is a clock. When an event happens (a call, a WhatsApp message, a task due, a timer ending, the WiFi dropping) it flashes the whole matrix in a pattern specific to that event, scrolls a short line of text once, and goes back to the clock.

It has three parts:

- **Firmware** on the UNO Q's microcontroller (STM32U585). It only draws frames it is given.
- **A Python daemon** (`deskmatrixd`) on the UNO Q's Linux side (Debian 13 on the Qualcomm QRB2210). It holds all the logic, draws every frame, and serves a small REST API on the home WiFi.
- **An Android app** (DeskMatrix, Kotlin + Jetpack Compose). It forwards phone events to the board and is the remote control for everything else.

**Status (2 Nov 2026):**

- Working and confirmed: clock, autostart on power-on, WhatsApp notifications (including repeated identical messages), incoming calls with the caller's name.
- Built and self-tested on the board, not yet confirmed on the phone: Instagram, SMS, Gmail, now playing, battery alerts, network alerts, tasks, alarms, timer, stopwatch, pomodoro.

## Repository layout

```
firmware/   MCU sketch (firmware.ino) + build profile (sketch.yaml)
board/      deskmatrixd.py daemon, systemd unit, install script, self-test
android/    Android app (Gradle project)
idea_.md    original spec
```

## Features as built

Every feature below runs on the board; the phone only sends events and commands.

| Feature | What the matrix shows | Flash pattern | Controlled from |
| --- | --- | --- | --- |
| Idle clock | 12-hour time in 3×7 digits, e.g. ` 9:07`, `12:45`. Colon blinks every second (proves it is alive). Dot at bottom-right = PM. Dot at bottom-left = offline. | none | always on |
| WhatsApp | `WA <SENDER>: <MESSAGE>` scrolled once | 2 flashes, 250 ms on / 250 ms off | phone notification |
| Instagram | `IG <SENDER>: <MESSAGE>` | 3 flashes, 250 ms | phone notification |
| SMS | `SMS <SENDER>: <MESSAGE>` | 1 long flash, 1 s | phone notification |
| Gmail | `GM <SENDER>: <SUBJECT>` | wipe left to right | phone notification |
| Incoming call | `CALL <NAME>` scrolled, then 2 flashes of 300 ms, repeating until answered or ended (90 s safety stop) | continuous | phone state |
| Phone battery | `PHONE BATTERY 19%` once below 20%; `PHONE CHARGED 100%` once at 100% | slow brightness pulse | phone battery |
| Now playing | `NOW PLAYING <TITLE> - <ARTIST>` only when the track changes (Spotify, YouTube Music) | 1 flash, 400 ms | phone media session |
| Task | `IN 10 MIN: <TITLE>` at the reminder time, `NOW: <TITLE>` at the due time | border chase, 2 laps | app, Tasks tab |
| Alarm | `ALARM <LABEL>`, repeating for 60 s or until dismissed. One-shot. | strobe | app, Tools tab |
| Timer | Full-screen countdown; `TIME UP` at zero | strobe 3 s | app, Tools tab |
| Stopwatch | Full-screen elapsed time; start / pause / reset / stop | none | app, Tools tab |
| Pomodoro | Full-screen countdown, default 25 min work / 5 min break / 15 min long break every 4th. Dot at bottom-right during a break. `WORK`, `BREAK`, `LONG BREAK` at each switch. | strobe 3 s | app, Tools tab |
| Network | `NO WIFI`, `NO INTERNET`, `ONLINE`; offline dot on the clock until fixed | slow pulse | automatic, every 10 s |
| Brightness | 0–7 (0 = off) | — | app, Home tab |
| Message speed | Scroll speed, 40–200 ms per pixel (default 90) | — | app, Home tab |
| Private mode | Hides the message text: shows only `WA <SENDER>` | — | app, Home tab |

### Text rules

- All text is converted to upper-case ASCII, because the 3×5 font only has capitals, digits and common punctuation.
- Non-English text is replaced per word group, not per message: Devanagari becomes `[HINDI MSG]`, emoji become `[EMOJI]`, any other script becomes `[MSG]`. Repeated placeholders collapse into one. Example: `ok खाना तैयार bye` shows as `OK [HINDI MSG] BYE`.
- Hinglish (Hindi typed in English letters) is plain ASCII and shows as typed.
- Messages are cut at the maximum length (default 80 characters, adjustable 20–200 in the app) and end with `...`.
- Every scroll first holds the start of the text still for 1 s so it can be read.

### Priority

Only one alert shows at a time; the rest wait in a queue. A higher-priority alert interrupts a lower one, and the interrupted alert replays from the start afterwards.

1. Incoming call
2. Timer end, alarm, pomodoro switch
3. Task reminder or due
4. Phone battery
5. Network
6. Message, now playing
7. Idle clock (or the running timer, stopwatch or pomodoro)

## Architecture

All intelligence sits on the board's Linux side; the phone is a sensor and remote, and the MCU is a display driver.

```
┌─────────────────────────────────────────────────────────┐
│ Phone app (Android, Kotlin)                             │
│  Reads notifications, phone state, battery and media    │
│  Sends events and commands; shows a live matrix preview │
└───────────────────────────┬─────────────────────────────┘
                            │ HTTP + JSON over home WiFi, port 8080
┌───────────────────────────▼─────────────────────────────┐
│ deskmatrixd (UNO Q Linux, Python)                       │
│  REST API; priority queue of alerts                     │
│  Schedules: tasks, alarms, timer, stopwatch, pomodoro   │
│  Network watchdog every 10 s; settings in state.json    │
│  Draws every 13×8 frame at 20 fps                       │
└───────────────────────────┬─────────────────────────────┘
                            │ frame() over the arduino-router socket
┌───────────────────────────▼─────────────────────────────┐
│ Firmware (UNO Q MCU, STM32U585)                         │
│  Draws each frame at brightness 0–7                     │
│  Blinks one dot if no frame arrives for 10 s            │
└───────────────────────────┬─────────────────────────────┘
                            ▼
                   LED matrix, 13×8 red LEDs
```

The firmware was flashed once and never needs to change: every feature is Python on Linux, and the same frames the LEDs show are served to the app's live preview. The board's own features (clock, tasks, alarms, timers, network alerts) keep working with the phone away, because they live on the board.

## MCU firmware

The microcontroller runs a "dumb framebuffer": it receives one frame at a time from Linux and draws it. File: `firmware/firmware.ino`, 55 lines, 88.8 KB of the MCU's 786 KB flash.

### Frame protocol

- Linux calls one remote function, `frame(w0, w1, w2, w3, brightness)`, through `arduino-router` (the UNO Q's built-in Linux-to-MCU message service).
- The 104 pixels (13 columns × 8 rows, row by row) are packed into four 32-bit numbers, most significant bit first. The last 24 bits are unused.
- `brightness` is 0–7. The firmware uses the matrix library's 3-bit grayscale mode (`setGrayscaleBits(3)`) and draws every lit pixel at that level, so brightness is real hardware brightness.
- The callback only stores the frame; the main loop draws it. This keeps the router's message handling short.

### No-link watchdog

If no frame arrives for 10 s, the firmware blinks the bottom-right pixel every 500 ms. This shows that the chip is running but the Linux program is not. The daemon resends the current frame at least every 2 s, so the watchdog stays quiet while it runs.

### Building and flashing without App Lab

Both steps run on the board itself with the pre-installed `arduino-cli`:

```
arduino-cli compile --profile default firmware
arduino-cli upload  --profile default firmware
```

- The board name (FQBN) is `arduino:zephyr:unoq`, core `arduino:zephyr` 1.1.0.
- `firmware/sketch.yaml` defines a build profile that pulls `Arduino_RouterBridge` 0.4.4. A plain `--library` flag does not work, because the core ships a stub of that library that stops the build with an error.
- Upload goes through OpenOCD over the board's internal debug link. No USB cable to a PC is involved.
- Delete the build cache (`~/.cache/arduino`, about 17 MB) afterwards (see Storage).

## Board daemon (deskmatrixd)

All logic lives in one Python file, `board/deskmatrixd.py` (662 lines, 25 KB), using only Python 3.13's standard library, so nothing is installed on the board. It runs three threads: the render loop, the HTTP API, and the network watchdog.

### Render loop

- Runs at 20 frames per second (one tick every 50 ms).
- Once per second it runs the schedule checks: task reminders, alarms, timer end, pomodoro phase switch, and storage pruning.
- Each tick picks what to show: the current alert if there is one, otherwise the running timer, stopwatch or pomodoro, otherwise the clock.
- It sends a frame only when the picture or brightness changed, plus once every 2 s to keep the firmware watchdog quiet.
- The link to the MCU is a Unix socket, `/var/run/arduino-router.sock`. Messages are MessagePack-RPC requests encoded by hand in about 10 lines, so no `msgpack` package is needed. If the socket breaks, the loop reconnects every 2 s.

### Fonts and layouts

- **Big digits, 3×7**, for the clock and all full-screen tools.
- **Small font, 3×5**, for scrolling text: A–Z, 0–9, space and `. , : ! ? - + / ' " [ ] ( ) % & @ # = _ *`. Unknown characters show as `?`.
- **Clock layout:** column 0 = the tens digit of the hour, drawn as a 1-pixel `1` (the only possible value in a 12-hour clock); gap; hour digit; colon; two minute digits with a gap. 1 + 1 + 3 + 1 + 3 + 1 + 3 = 13 pixels, exactly the matrix width. Rows 0–6 hold the digits; row 7 holds the PM dot (right) and the offline dot (left).
- **Duration layout** (timer, stopwatch, pomodoro): `M:SS` under 20 minutes, minutes only (`25`) from 20 to 59 minutes, then `H:MM` up to 19:59. MM:SS above 19 minutes does not fit 13 pixels.

### Alerts and the queue

- Each alert is a generator that yields one frame and a brightness factor per tick. Flash patterns, wipes, strobes, the border chase, the brightness pulse and scrolling text are all small generators that chain together.
- Alerts wait in a priority heap. If a higher-priority alert arrives, the current one is put back and replays from the start later.
- A call loops (name scroll, then 2 flashes) while the call state is "ringing". A second "ringing" event only updates the name. It stops itself after 90 s in case the "ended" event is lost.

### Schedules

- **Tasks** fire `IN n MIN: <TITLE>` at due time minus the reminder minutes, and `NOW: <TITLE>` at the due time. A task more than 5 minutes overdue after a power cut is not replayed.
- **Alarms** fire when the local time matches `HH:MM`, then disable themselves (one-shot).
- **Battery** alerts fire once below 20% and once at 100%. They re-arm at 25% and below 95%, so a level hovering around a threshold does not repeat the alert.
- **Now playing** fires only when the (title, artist) pair changes.
- Time comes from the board's own clock, set by NTP; the timezone is Asia/Kolkata.

### Network watchdog

Every 10 s it checks the WiFi link (`/sys/class/net/wlan0/operstate`) and the internet (a TCP connection to `1.1.1.1:53` or `8.8.8.8:53`). The internet must fail twice in a row before `NO INTERNET` shows. Each change of state gives one alert, and the offline dot stays on the clock until the connection is back.

### REST API

The API listens on port 8080 on all interfaces. Bodies are JSON, limited to 16 KB. Bad input gets a 400 reply with an error message.

| Method and path | Body | Does |
| --- | --- | --- |
| `GET /state` | — | Settings, tasks, alarms, running mode, timers, offline flag, current frame |
| `GET /frame` | — | Current frame as a 104-character string of `0`/`1` |
| `POST /event` | `{type: notification, app, sender, text}` | Message alert |
| `POST /event` | `{type: call, state: ringing \| answered \| ended, name, number}` | Call alert |
| `POST /event` | `{type: battery, level}` | Battery threshold check |
| `POST /event` | `{type: media, title, artist, playing}` | Now playing |
| `POST /settings` | any of `brightness, privacy, max_len, scroll_ms, apps, media` | Change and save settings |
| `POST /tasks` | `{title, due_at (Unix seconds), remind_before_min}` | Add a task (max 100) |
| `DELETE /tasks/<id>` | — | Remove a task |
| `POST /alarms` | `{time: "HH:MM", label}` | Add an alarm (max 50) |
| `DELETE /alarms/<id>` | — | Remove an alarm |
| `POST /alarm/dismiss` | — | Stop a ringing alarm |
| `POST /timer` | `{seconds}` or `{action: stop}` | Start or stop the timer |
| `POST /stopwatch` | `{action: start \| pause \| reset \| stop}` | Stopwatch control |
| `POST /pomodoro` | `{work, break, long}` in minutes, or `{action: stop}` | Start or stop pomodoro |

Example, start a 1-minute timer from PowerShell:

```
Invoke-RestMethod -Method Post http://<board-ip>:8080/timer -Body '{"seconds":60}'
```

### Self-test

`board/test_deskmatrixd.py` checks the text sanitiser, font sizes, clock frame and PM dot, call-over-message priority, privacy mode, battery alerts firing once, task reminder then due, and timer end. Run it with `python3 -B test_deskmatrixd.py`; it prints `ok`. Copy it to the board only for a test run and delete it afterwards.

## Android app

One app, package `dev.deskmatrix`, does two jobs: it forwards phone events to the board, and it is the remote control. Kotlin, Jetpack Compose with Material 3, `minSdk` 29 (Android 10), `targetSdk` 36. Only two libraries: the Compose BOM (2025.06.01) and `activity-compose`. HTTP uses Android's built-in `HttpURLConnection` and JSON uses `org.json`.

### Source files

| File | Role |
| --- | --- |
| `Board.kt` | Tiny HTTP client. Stores the board address. `call()` blocks and returns JSON or null; `post()` sends in the background. |
| `BridgeService.kt` | Notification listener that sends calls, messages, battery and media to the board. |
| `MainActivity.kt` | The four screens, the theme, and the live matrix preview. |

### Screens

- **HOME:** connection status, a live copy of the matrix (polled every 300 ms), brightness slider, message speed slider (right = faster), private mode switch, and a Dismiss button while an alarm rings.
- **TASKS:** add a task with a title, a due date and time (native date and time pickers), and minutes of reminder before. The list is sorted by due time; DONE removes a task.
- **TOOLS:** timer (minutes, start, stop); stopwatch (start, pause, reset, stop); pomodoro (work and break minutes, go, stop); alarms (label, time picker, list with delete).
- **SETUP:** a 3-step checklist (notification access, phone state, ignore battery optimisation), each with an OPEN button until done; board address; on/off switches for WhatsApp, Instagram, SMS, Gmail and now playing; maximum message length; dark theme switch.
- **Style:** monospace text, near-black background, one red accent matching the LEDs, round dots for the preview. A light theme uses the same layout.

### The bridge (BridgeService)

It is a `NotificationListenerService`. Android keeps it bound in the background and rebinds it after a reboot, so the app needs no foreground service, no ongoing notification and no boot receiver.

- **Messages:** posted notifications from `com.whatsapp`, `com.instagram.android`, `com.google.android.gm`, and the phone's default SMS app (looked up at runtime). Group summary notifications are ignored. Duplicates are filtered by the notification key + sender + text + the message's own timestamp (from `EXTRA_MESSAGES`), so WhatsApp re-posting old messages is filtered while a genuinely repeated message still shows. The last 200 hashes are kept in memory.
- **Calls:** ringing, answered and ended come from Android's phone-state broadcast (`ACTION_PHONE_STATE_CHANGED`). The caller's name comes from the phone app's live call notification: first the call's `Person`, else the title or text that does not contain the word "call". The app waits 0.8 s after the ring starts, also scans notifications already showing, and updates the board if the name arrives later. Missed-call notifications are skipped so an old caller's name is never shown. With no name found, the board shows `CALL INCOMING`.
- **Battery:** listens to the battery broadcast and sends a level only when it moves between the bands <20, 20–24, 25–94, 95–99 and 100. The board decides whether to alert.
- **Now playing:** watches active media sessions from Spotify and YouTube Music and sends the title and artist on every metadata change. The board filters out repeats.

### Permissions

| Permission | Why |
| --- | --- |
| Notification access | Messages, the caller's name, and media sessions |
| `READ_PHONE_STATE` | Reliable ringing / answered / ended for calls |
| `INTERNET` | Talking to the board |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Asks the phone's battery manager not to kill the listener |

Not used: contacts, call log, SMS, location. The app allows plain HTTP (`usesCleartextTraffic`) because the board serves HTTP on the home network.

### Building and installing

```
cd android
gradlew.bat assembleDebug
```

- Built with Gradle 8.14, Android Gradle Plugin 8.11.1, Kotlin 2.2.0, and Android Studio's bundled JDK.
- Output: `android/app/build/outputs/apk/debug/app-debug.apk` (about 24 MB, debug-signed).
- Play Protect blocks it as an unknown app. Install with "More details, then Install anyway", or over USB with `adb install`.

## Storage, autostart and operations

### Storage policy

DeskMatrix uses 36 KB on the board and is built so that this cannot grow by more than a few KB: nothing is stored beyond the program itself.

| File on the board | Size | Purpose |
| --- | --- | --- |
| `~/deskmatrix/deskmatrixd.py` | 25 KB | The daemon |
| `~/deskmatrix/state.json` | under 1 KB | Settings, tasks, alarms (survive a power cut) |
| `/etc/systemd/system/deskmatrixd.service` | under 1 KB | Autostart entry |

- **No logs:** the service sends its output to `null`, so nothing is written to the system journal.
- **Capped data:** tasks are deleted 1 day after they are due and alarms after they ring. There is a hard limit of 100 tasks and 50 alarms. `state.json` is written atomically (temporary file, then rename) so a power cut cannot corrupt it.
- **No leftovers:** after building or testing, delete everything except the program: build cache, firmware source, test file, Python cache, install script.
- The firmware is stored in the MCU's own flash, not on the Linux disk.

### Install

1. Copy `firmware/` to the board, compile and upload it (see MCU firmware), then delete the build cache.
2. Copy `board/deskmatrixd.py`, `board/deskmatrixd.service` and `board/install.sh` to `~/deskmatrix/` on the board.
3. Run `sudo sh install.sh` there. It sets the timezone to Asia/Kolkata and enables the service.
4. Delete `install.sh` and `deskmatrixd.service` from `~/deskmatrix/`.

### Autostart

`deskmatrixd.service` is a systemd unit enabled for `multi-user.target`. It starts after `arduino-router.service`, runs as the `arduino` user with the extra group `arduino-router` (needed to open the router socket), and restarts 2 s after any crash. The firmware starts on its own when the board gets power. Plug the board into any USB-C charger and the clock appears within about 30–60 s.

### Operations

Open a terminal on the board, over USB (`adb shell`) or over WiFi (`ssh arduino@<board-ip>`).

| To | Run on the board |
| --- | --- |
| See if it is running | `systemctl status deskmatrixd` |
| Stop it until the next power-on | `sudo systemctl stop deskmatrixd` |
| Start it again | `sudo systemctl start deskmatrixd` |
| Stop it starting at power-on | `sudo systemctl disable --now deskmatrixd` |
| Turn autostart back on | `sudo systemctl enable --now deskmatrixd` |

**Remove it completely:**

```
sudo systemctl disable --now deskmatrixd
sudo rm /etc/systemd/system/deskmatrixd.service
sudo systemctl daemon-reload
rm -rf ~/deskmatrix
```

After that the firmware still blinks one dot. To make the matrix fully dark, flash an empty sketch and clean up (not yet tested on this board):

```
mkdir -p /tmp/blank && printf 'void setup(){}\nvoid loop(){}\n' > /tmp/blank/blank.ino && arduino-cli compile -b arduino:zephyr:unoq --build-path /tmp/blankbuild /tmp/blank && arduino-cli upload -b arduino:zephyr:unoq --input-dir /tmp/blankbuild /tmp/blank; rm -rf /tmp/blank /tmp/blankbuild ~/.cache/arduino
```

## Decisions, changes from the spec, and limits

### Changes from the original spec (`idea_.md`)

| Spec said | Built | Why |
| --- | --- | --- |
| Missed-call alert | Removed; calls show only while ringing | Missed calls need `READ_CALL_LOG`, a restricted permission |
| Seconds bar on row 7, 3×6 digits | No seconds bar, taller 3×7 digits | Easier to read from across a desk |
| AM/PM: pixel for 1–9, blinking colon for 10–12 | One PM dot at bottom-right for every hour; colon always blinks | Same signal for every hour |
| FastAPI + WebSocket + SQLite | Python standard library: `http.server`, REST, a JSON file | Nothing to install on the board; the phone only needs request/response |
| Foreground service + boot receiver in the app | Notification listener only | Android keeps it alive and rebinds it after reboot |
| Calls from the dialer notification only | Phone-state broadcast for the state, notification for the name | The notification alone missed later calls on the Motorola dialer |
| mDNS name `deskmatrix.local` | Fixed IP address | Simpler; the address can be changed in the app |
| Fast flashes (100 ms) and fast scroll (50 ms/px) | 250 ms flashes, 90 ms/px scroll, 1 s pause at the start, speed slider in the app | The first version was too fast to read |

### Decisions taken during the build

- Network alert sits after battery in the priority order.
- Mixed English and Hindi text is replaced per word group, not as a whole message.
- Alarms are one-shot; no weekday repeats.
- Network down gives one alert plus a persistent offline dot on the clock.
- Timer, stopwatch and pomodoro share the screen; starting one hides the others.
- No Bluetooth "zero-app" mode; everything goes through the app over WiFi.

### Known limits

- **No access control:** anyone on the home WiFi can call the API.
- **Fixed IP:** if the router gives the board a new address, the app loses it. Reserve the board's address in the router's DHCP settings.
- **Caller number:** shown only when the phone app's notification includes it (usually for unsaved callers).
- **Caller name:** read from the Motorola phone app's notification. A different phone app may need the matching rule adjusted.
- **Now playing:** only Spotify and YouTube Music.
- **Phone off home WiFi:** phone events stop. The clock, tasks, alarms, timers and network alerts keep working on the board.
- **Timers:** at most 19 hours 59 minutes on the display.
