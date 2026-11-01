# DeskMatrix â€” Arduino UNO Q Desk Companion

A desk companion built on the Arduino UNO Q's 13Ã—8 LED matrix. It shows a clock when idle and flashes full-screen for calls, messages, timers, tasks, battery alerts and network loss. Everything is controlled from one Android app, and it starts automatically when the board gets power. App Lab is not used anywhere.

> Spec written from the owner's answers (2 Nov 2026). Anything marked **âš  DECISION** still needs the owner's call before or while building. Anything marked **ðŸ§ª SPIKE** must be proven on the real board in Phase 0 before building on it.

---

## 1. Scope (exactly what was asked for, nothing more)

| # | Feature | Details |
|---|---------|---------|
| 1 | Idle clock | 12-hour clock, full time always visible (3Ã—6 digits), optional seconds bar on the bottom row. Only the time. |
| 2 | Notifications | Only WhatsApp, Instagram, SMS and Gmail. One scroll each, then back to the clock. Shown one at a time from a queue. |
| 3 | Privacy toggle | **Full mode:** app + sender + message. **Private mode:** app + sender only. |
| 4 | Non-English text | Hindi/Devanagari, emoji or other non-ASCII text is replaced with a placeholder (`[HINDI MSG]`, `[EMOJI]`). |
| 5 | Calls | Full-screen flashing while it rings, showing name and number. If not answered â†’ `MISSED CALL <name>`. |
| 6 | Flash | A full-screen flash on every event, with a different flash pattern for each app and for calls. |
| 7 | To-do | A task has only a **title + due time**. Every task with a due time shows on the matrix. The reminder lead time is set **per task** ("IN 10 MIN â€¦"). No recurring tasks, no Google sync. |
| 8 | Timer | Full screen. Flash when it ends. |
| 9 | Stopwatch | Full screen. |
| 10 | Pomodoro | Full screen, work/break cycles. Flash at each switch. |
| 11 | Alarms | Flash when it fires. No sound and no phone notification; the flash is the only alert. |
| 12 | Phone battery | Alert **once** when it drops below 20%, and **once** when it reaches 100%. |
| 13 | Now playing | Spotify / YouTube Music song name scrolls when the track changes. |
| 14 | Network alert | Alert when WiFi drops or the internet goes down, plus a "back online" alert. |
| 15 | Brightness | Controlled from the app (slider). |
| 16 | App | One Android app (own build) that controls everything **and** sends phone events. Dark + light themes, techy look. |
| 17 | Autostart | Everything starts on power-on with no laptop and no App Lab. |

**Explicitly out of scope:** DND/quiet hours, notification history, weather, date, timetable, recurring tasks, laptop features, sound/buzzer, extra hardware, paid/keyed APIs, use outside home WiFi, multi-user/login.

### Priority (highest wins, lower items wait in the queue)
`Incoming call > Timer/alarm/pomodoro end > Due task > Battery alert > Network alert > Message/now-playing > Idle clock`

âš  DECISION: network alert wasn't in the original ranking. It's placed after battery; confirm.

---

## 2. The big constraint: "no app permissions"

You asked to avoid a phone app and its permissions if possible. Here's what Android allows:

| Data | Possible with **no app** (Bluetooth only)? | How |
|------|------------------------------------|-----|
| Incoming call + caller name/number | âœ… Yes | The board pairs as a Bluetooth **hands-free device (HFP)** and receives ring + caller ID like a car kit. |
| SMS sender + text | âœ… Mostly | Bluetooth **MAP** (Message Access Profile). The phone asks "allow message access?" once when pairing. |
| Phone battery % | âœ… Usually | Exposed through HFP indicators. |
| Now playing song | âœ… Yes | Bluetooth **AVRCP** track metadata. |
| WhatsApp / Instagram / Gmail | âŒ **No** | Android only exposes these through `NotificationListenerService`, which requires an app with notification access. There is no Bluetooth or WiFi workaround. |

**Verdict:** you can't avoid an app completely, because 3 of your 4 notification sources need one. You've also chosen an app (Q2/Q33) for control anyway. So:

- **Plan: the companion app does everything** (notifications, calls, battery, media, plus the control UI). It's one reliable path, easy to debug, and uses one set of permissions.
- **Bluetooth zero-app mode (optional, Phase 6):** calls, SMS, battery and now-playing over HFP/MAP/AVRCP, so those keep working even if the app is killed. ðŸ§ª SPIKE, and risky: when the board pairs as a hands-free device, Android may try to **send call audio to the board** (which has no speaker or mic). This must be tested on the Edge 60 Pro before committing to it.

âš  DECISION: build zero-app mode at all, or app-only? (Recommendation: app-only first; try zero-app mode afterwards as an experiment.)

### Permissions the app needs (minimum)
- **Notification access** â€” WhatsApp/Instagram/Gmail/SMS content **and** now-playing (via `MediaSessionManager`; same permission, no extra one)
- `READ_PHONE_STATE` + `READ_CALL_LOG` â€” ringing/answered/missed + number
- `READ_CONTACTS` â€” caller name (optional; without it only the number shows)
- `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_CONNECTED_DEVICE` type), `POST_NOTIFICATIONS` (for the required ongoing service notification)
- `ACCESS_NETWORK_STATE`, `INTERNET`, `CHANGE_WIFI_MULTICAST_STATE` (for finding the board via mDNS)
- Battery level needs **no permission** (`ACTION_BATTERY_CHANGED` broadcast)
- Turn off battery optimisation for the app (Motorola kills background apps aggressively)

---

## 3. Idle clock layout (decided: full time always visible)

The full time is on screen the whole time. The seconds ring is dropped, because a full ring leaves only 11Ã—6 pixels inside it, which is too small for the whole time.

**Why it fits:** in a 12-hour clock the first digit is only ever blank or `1`, and `1` can be drawn **1 px wide**. The other digits use a 3-px-wide font:

```
1 + gap + 3 + colon + 3 + gap + 3  =  1+1+3+1+3+1+3  =  13 px  â†’ exactly the matrix width
```

**Layout (13Ã—8):**
- Rows 0â€“5: time in a **3Ã—6 digit font** (taller = easier to read), e.g. `12:45`, ` 9:07`
- Row 6: empty
- Row 7: **seconds bar** (optional, toggle in the app). It fills leftâ†’right over 60 s (13 LEDs, about 4.6 s each) and resets every minute. If you turn it off, the digits can use the 3Ã—7 font instead.
- **AM/PM:** for single-digit hours (1â€“9) the empty first column is free, so its top pixel marks PM. For 10, 11 and 12 there's no free pixel, so PM is shown by the colon blinking and AM by a steady colon (or set the colon to always blink, and PM to off).

Preview of `12:45` (`#` = LED on). Columns: `1` | gap | `2` | colon | `4` | gap | `5`:
```
#.###.#.#.###
#...###.#.#..
#.###.###.###
#.#.....#...#
#.#..#..#...#
#.###...#.###
.............
######.......   
```
Row 7 is the seconds bar, here at about 28 s. The colon touches the digits on both sides; tune the glyphs on the real board if it looks cramped.

âš  DECISION: keep the seconds bar or remove it? And what should mark AM/PM for 10â€“12?

---

## 4. Architecture

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Android phone (Edge 60 Pro, Android 17) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  DeskMatrix app (Kotlin + Jetpack Compose)                      â”‚
â”‚   â”œâ”€ Control UI (tasks, timers, alarms, settings, live preview) â”‚
â”‚   â””â”€ Bridge service (foreground)                                â”‚
â”‚        NotificationListener Â· call state Â· battery Â· media      â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                â”‚ WebSocket + REST over home WiFi (board found via mDNS: deskmatrix.local)
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€ UNO Q â€” Linux side (Debian, QRB2210) â”€â”€â”€â”
â”‚  deskmatrixd  (Python 3, FastAPI + asyncio, systemd service)    â”‚
â”‚   â”œâ”€ API: REST + WebSocket (app â†” board)                        â”‚
â”‚   â”œâ”€ Event bus + priority queue                                 â”‚
â”‚   â”œâ”€ Schedulers: tasks, alarms, timer, stopwatch, pomodoro      â”‚
â”‚   â”œâ”€ Network watchdog (WiFi link + internet ping)               â”‚
â”‚   â”œâ”€ Text sanitiser (non-ASCII â†’ placeholder)                   â”‚
â”‚   â”œâ”€ Renderer: scenes â†’ 13Ã—8 frames (font, scroll, flash, clock) â”‚
â”‚   â”œâ”€ Storage: SQLite (tasks, alarms, settings)                  â”‚
â”‚   â””â”€ Matrix link â†’ sends frames to MCU                          â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                â”‚ ðŸ§ª SPIKE: arduino-router RPC (msgpack) or raw internal UART
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€ UNO Q â€” MCU side (STM32U585) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  firmware.ino â€” "dumb framebuffer"                              â”‚
â”‚   receives frame (104 px, brightness) â†’ draws Â· nothing else    â”‚
â”‚   watchdog: if no frame for 10 s â†’ show "NO LINK" pattern       â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

### Why it's built this way
- **All logic on Linux, MCU only draws.** The firmware gets flashed once and never changes. Every feature is Python, so it's fast to iterate, testable on a laptop, and the renderer can stream the same frames to the app's **live matrix preview**. That's good for development and for the GitHub demo GIF.
- **WiFi for app â†” board.** You asked for home WiFi. It's simpler and more reliable than BLE for a constant stream, and it also carries the control UI. Bluetooth is reserved for the optional zero-app mode.
- **Phone off home WiFi = the board shows nothing from the phone.** As asked. The board's own features (clock, tasks, timers, alarms, network alert) still run offline because they live on the board.

### Choices that must be proven on the board (Phase 0)
1. ðŸ§ª **Flashing the MCU without App Lab.** Use `arduino-cli` on the board (or from a PC once) with the UNO Q core. Confirm the exact FQBN and upload command.
2. ðŸ§ª **Linux â†’ MCU link without App Lab.** Check whether the `arduino-router` service and its RPC socket work outside App Lab. If not, fall back to the internal serial UART between the two chips. Measure the frame rate (target â‰¥ 20 fps for smooth scrolling).
3. ðŸ§ª **Matrix brightness/grayscale API.** Confirm what brightness levels the UNO Q matrix library actually supports. If there's no hardware brightness, simulate it with PWM-style frame dithering.
4. ðŸ§ª **Autostart.** Confirm that a systemd unit starts on boot and that App Lab's default-app mechanism doesn't fight it. Disable App Lab's autostart if it does.
5. ðŸ§ª **mDNS** `deskmatrix.local` is reachable from the phone on your home router. Fallback: fixed IP via DHCP reservation, typed into the app once.

---

## 5. Event flow

```
phone event â†’ app filters (allowed package? dedupe) â†’ WebSocket JSON â†’ deskmatrixd
â†’ sanitise text â†’ apply privacy mode â†’ assign priority â†’ queue
â†’ renderer plays: flash pattern â†’ scrolling text (1Ã—) â†’ back to idle clock
```

### Phone â†’ board message format (JSON over WebSocket)
```json
{ "type": "notification", "app": "whatsapp", "sender": "Mom", "text": "Khana ready hai", "ts": 1762022400 }
{ "type": "call", "state": "ringing|answered|ended", "name": "Mom", "number": "+91â€¦" }
{ "type": "battery", "level": 19, "charging": false }
{ "type": "media", "title": "Song name", "artist": "Artist", "playing": true }
```
- Package filter: `com.whatsapp`, `com.instagram.android`, `com.google.android.gm`, and the default SMS app (resolve it at runtime; usually `com.google.android.apps.messaging` on Motorola).
- Media allowed from `com.spotify.music`, `com.google.android.apps.youtube.music`. Show only on a track change, not continuously.
- **Dedupe:** WhatsApp re-posts and groups notifications. Use the notification key + text hash; ignore summary/group notifications.
- **Battery:** the app sends only on threshold crossings. The board keeps an "already alerted" flag and resets it once the level goes â‰¥ 25% (for low) or < 95% (for full).

### Display text rules
- Format: `WA MOM: KHANA READY HAI` (app code + sender + text). Private mode: `WA MOM`.
- App codes: `WA`, `IG`, `SMS`, `GM`, `CALL`.
- Uppercase, ASCII only. Any word or segment with Devanagari â†’ `[HINDI MSG]`. Emoji â†’ `[EMOJI]`. Other scripts â†’ `[MSG]`.
  - âš  DECISION: if a message mixes English and Hindi, should only the Hindi part be replaced, or the whole message? (Default: per segment.)
  - Hinglish (Hindi written in English letters) is plain ASCII and shows as-is.
- Long messages: truncate to N characters (configurable, default 80) + `â€¦`. A full long message takes a long time to scroll.

### Flash patterns (configurable in the app)
| Event | Pattern |
|-------|---------|
| Call (ringing) | continuous: full on/off every 300 ms + name/number scroll, until answered or ended |
| WhatsApp | 2 quick flashes |
| Instagram | 3 quick flashes |
| SMS | 1 long flash |
| Gmail | flash wipe leftâ†’right |
| Timer/alarm/pomodoro end | rapid strobe, 3 s, then text |
| Task due | border chase + text |
| Battery / network | 1 slow pulse + text |

---

## 6. Board features in detail

- **Tasks:** `{id, title, due_at, remind_before_min}`. At `due_at âˆ’ remind_before`: show `IN 10 MIN: <TITLE>`. At `due_at`: show `NOW: <TITLE>`. Done/delete from the app. Stored in SQLite, so they survive a reboot.
- **Alarms:** `{id, time, label, enabled}`. They fire with a strobe + label and stay flashing until dismissed in the app, or for 60 s.
  - âš  DECISION: should alarms repeat on chosen weekdays? You said no recurring *tasks*; alarms weren't covered. Default: one-shot.
- **Timer:** set in the app â†’ full-screen countdown (MM:SS, or a progress-ring style) â†’ strobe at 0.
- **Stopwatch:** start/pause/reset from the app, shown full screen. Over 99:59 it switches to H:MM.
- **Pomodoro:** work/break minutes configurable (default 25/5, long break 15 every 4). Full screen with a phase indicator, flash at each switch.
- While a timer is running full screen, higher-priority events (a call) interrupt it and then the timer view returns.
- **Network watchdog:** every 10 s check the WiFi link (`nmcli`/`iw`) and internet (ping/TCP to `1.1.1.1:53` and `8.8.8.8:53`, both must fail twice in a row). Alert on going down (`NO WIFI` / `NO INTERNET`) and on coming back (`ONLINE`).
  - âš  DECISION: show the alert once, or keep a small "offline" icon in a corner of the clock until it's fixed? (Default: alert once + small corner icon.)
- **Time:** comes from NTP (no key needed). The board keeps time while offline. Timezone is fixed to Asia/Kolkata.
- **Brightness:** slider in the app, stored in settings, applied immediately.

---

## 7. Android app

- **Stack:** Kotlin, Jetpack Compose, Material 3. OkHttp for the WebSocket, Room or DataStore only for app-side settings (the board is the source of truth for tasks and alarms).
- **Screens:**
  1. **Home:** live matrix preview (mirrors the real LEDs), connection status, brightness slider, privacy-mode toggle.
  2. **Tasks:** list + add (title, due time, remind-before).
  3. **Time tools:** Timer Â· Stopwatch Â· Pomodoro Â· Alarms tabs.
  4. **Settings:** which apps are allowed, flash patterns, clock options (seconds bar on/off), max message length, board address, theme (dark/light).
  5. **Setup wizard (first launch):** grant notification access â†’ phone/contacts permissions â†’ disable battery optimisation â†’ find the board.
- **Style:** "techy": monospace numbers, dark terminal-ish palette with one red accent (matching the red LEDs), a pixel-grid motif, and a light theme with the same layout.
- **Bridge service:** foreground service, restarts on boot (`BOOT_COMPLETED`), and reconnects automatically when the phone joins home WiFi (match on SSID, which needs location permission on Android, or simply try to reach `deskmatrix.local`).
  - âš  DECISION: detect "home WiFi" by SSID (needs a location permission) or just by "board reachable"? (Recommendation: board reachable. No extra permission.)
- Android 17 specifics to check while building: foreground-service type rules, notification-listener restrictions, and Motorola's background killing. Test with the screen off for 1 hour+.

---

## 8. Repository layout

```
deskmatrix/
â”œâ”€ README.md            # demo GIF, features, setup, architecture diagram
â”œâ”€ idea.md              # this file
â”œâ”€ firmware/            # MCU sketch (dumb framebuffer)
â”‚  â””â”€ firmware.ino
â”œâ”€ board/               # Linux daemon
â”‚  â”œâ”€ deskmatrixd/
â”‚  â”‚  â”œâ”€ main.py        # FastAPI app + startup
â”‚  â”‚  â”œâ”€ api.py         # REST + WebSocket
â”‚  â”‚  â”œâ”€ events.py      # event types, priority queue
â”‚  â”‚  â”œâ”€ scheduler.py   # tasks, alarms, timer, stopwatch, pomodoro
â”‚  â”‚  â”œâ”€ network.py     # watchdog
â”‚  â”‚  â”œâ”€ sanitize.py    # text â†’ ASCII + placeholders
â”‚  â”‚  â”œâ”€ render/        # fonts, scenes, scroll, flash, clock
â”‚  â”‚  â”œâ”€ matrix_link.py # frames â†’ MCU (router RPC or UART)
â”‚  â”‚  â””â”€ db.py          # SQLite
â”‚  â”œâ”€ tests/            # renderer + sanitiser + scheduler tests (run on laptop)
â”‚  â”œâ”€ simulator.py      # terminal/browser matrix simulator for dev without board
â”‚  â”œâ”€ deskmatrixd.service  # systemd unit
â”‚  â””â”€ install.sh        # one-shot setup on the board
â””â”€ android/             # Kotlin app (Android Studio project)
```

---

## 9. Build plan (everything, in order)

The full scope is planned at once (as asked). It's built in this order because each phase depends on the one before.

- **Phase 0 â€” Board spikes (do first, ~1 day):** prove every ðŸ§ª item in Â§4: flash firmware without App Lab, Linuxâ†’MCU frames at â‰¥ 20 fps, brightness, systemd autostart, mDNS. *If the Linuxâ†’MCU link fails, stop and redesign before anything else.*
- **Phase 1 â€” Display engine:** firmware framebuffer + `matrix_link` + renderer (fonts, scrolling, flash patterns, clock layout Â§3) + simulator. Tune the digit glyphs on the real board here.
- **Phase 2 â€” Board daemon:** FastAPI, SQLite, event queue + priority, tasks, alarms, timer, stopwatch, pomodoro, network watchdog, brightness, systemd install. Fully usable through REST with no app yet.
- **Phase 3 â€” Android control UI:** connect, live preview, tasks, time tools, settings, themes.
- **Phase 4 â€” Android bridge:** notification listener (4 apps, dedupe, privacy), call states (ringing/missed), battery thresholds, now-playing, foreground service + boot + reconnect.
- **Phase 5 â€” Hardening:** 24-hour soak test, reboot test (unplug â†’ everything returns), phone off WiFi â†’ back, burst of 20 notifications, Hindi/emoji messages, call during timer, app killed by Motorola.
- **Phase 6 â€” Optional:** Bluetooth zero-app mode (HFP/MAP/AVRCP) spike.
- **Phase 7 â€” GitHub polish:** README with a demo GIF from the simulator + a real video, architecture diagram, setup guide, MIT licence.

---

## 10. Risks (plainly)

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Linuxâ†’MCU link outside App Lab undocumented/unstable | Blocks the project | Phase 0 spike first; UART fallback |
| Motorola/Android 17 kills the bridge service | Missed notifications | Foreground service, battery-optimisation exemption, setup wizard, reconnect logic |
| Thin 3-px digits hard to read from a distance | Unreadable clock | Tune the glyphs on the real board; drop the seconds bar to use 3Ã—7 digits |
| WhatsApp duplicate/grouped notifications | Repeated scrolls | Key + hash dedupe, ignore group summaries |
| Long messages take ages to scroll | Annoying | Truncate (default 80 chars) |
| Bluetooth HFP routes call audio to board | Calls unusable | Zero-app mode is optional and tested last |
| Matrix brightness may be on/off only | Slider useless | Spike; software dithering fallback |

---

## 11. Open decisions (answer before/while building)

1. Seconds bar on/off, and the AM/PM marker for 10â€“12 (Â§3)
2. Build the Bluetooth zero-app mode at all? (Â§2)
3. Placement of the network alert in the priority list (Â§1)
4. Mixed English+Hindi message: replace only the Hindi part, or the whole message? (Â§5)
5. One-shot alarms only, or weekday repeats? (Â§6)
6. Network down: one alert, or alert + persistent corner icon? (Â§6)
7. Home-WiFi detection by "board reachable" or by SSID? (Â§7)
8. Max message length before truncation (default 80)
