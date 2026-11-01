#!/usr/bin/env python3
"""DeskMatrix daemon.

Renders every frame on Linux and sends it to the MCU through arduino-router.
The phone app talks to it over a small JSON REST API on port 8080.
Stdlib only, so nothing needs pip on the board.

    python3 deskmatrixd.py            run (systemd does this)
    python3 deskmatrixd.py --preview  print the current clock frame
"""
import heapq
import itertools
import json
import os
import re
import socket
import struct
import sys
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOCK = "/var/run/arduino-router.sock"
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "state.json")
PORT = 8080
W, H = 13, 8
TICK = 0.05  # 20 fps

# Priorities: lower number wins.
P_CALL, P_END, P_TASK, P_BATTERY, P_NETWORK, P_MESSAGE = range(6)

APP_CODES = {"whatsapp": "WA", "instagram": "IG", "sms": "SMS", "gmail": "GM"}

# ---------------------------------------------------------------- fonts

# 3x7 digits for the clock and full-screen tools.
BIG = {
    "0": ["###", "#.#", "#.#", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "..#", "###", "#..", "#..", "###"],
    "3": ["###", "..#", "..#", "###", "..#", "..#", "###"],
    "4": ["#.#", "#.#", "#.#", "###", "..#", "..#", "..#"],
    "5": ["###", "#..", "#..", "###", "..#", "..#", "###"],
    "6": ["###", "#..", "#..", "###", "#.#", "#.#", "###"],
    "7": ["###", "..#", "..#", ".#.", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "#.#", "###", "#.#", "#.#", "###"],
    "9": ["###", "#.#", "#.#", "###", "..#", "..#", "###"],
}

# 3x5 font for scrolling text. Each glyph is 5 rows of 3 columns.
SMALL = {k: v.split() for k, v in {
    "A": ".#. #.# ### #.# #.#", "B": "##. #.# ##. #.# ##.", "C": ".## #.. #.. #.. .##",
    "D": "##. #.# #.# #.# ##.", "E": "### #.. ##. #.. ###", "F": "### #.. ##. #.. #..",
    "G": ".## #.. #.# #.# .##", "H": "#.# #.# ### #.# #.#", "I": "### .#. .#. .#. ###",
    "J": "..# ..# ..# #.# .#.", "K": "#.# #.# ##. #.# #.#", "L": "#.. #.. #.. #.. ###",
    "M": "#.# ### ### #.# #.#", "N": "##. #.# #.# #.# #.#", "O": ".#. #.# #.# #.# .#.",
    "P": "##. #.# ##. #.. #..", "Q": ".#. #.# #.# ##. .##", "R": "##. #.# ##. #.# #.#",
    "S": ".## #.. .#. ..# ##.", "T": "### .#. .#. .#. .#.", "U": "#.# #.# #.# #.# ###",
    "V": "#.# #.# #.# #.# .#.", "W": "#.# #.# ### ### #.#", "X": "#.# #.# .#. #.# #.#",
    "Y": "#.# #.# .#. .#. .#.", "Z": "### ..# .#. #.. ###",
    "0": "### #.# #.# #.# ###", "1": ".#. ##. .#. .#. ###", "2": "##. ..# .#. #.. ###",
    "3": "##. ..# .#. ..# ##.", "4": "#.# #.# ### ..# ..#", "5": "### #.. ##. ..# ##.",
    "6": ".## #.. ### #.# ###", "7": "### ..# .#. .#. .#.", "8": "### #.# ### #.# ###",
    "9": "### #.# ### ..# ##.",
    " ": "... ... ... ... ...", ".": "... ... ... ... .#.", ",": "... ... ... .#. #..",
    ":": "... .#. ... .#. ...", "!": ".#. .#. .#. ... .#.", "?": "##. ..# .#. ... .#.",
    "-": "... ... ### ... ...", "+": "... .#. ### .#. ...", "/": "..# ..# .#. #.. #..",
    "'": ".#. .#. ... ... ...", '"': "#.# #.# ... ... ...", "[": "##. #.. #.. #.. ##.",
    "]": ".## ..# ..# ..# .##", "(": ".#. #.. #.. #.. .#.", ")": ".#. ..# ..# ..# .#.",
    "%": "#.. ..# .#. #.. ..#", "&": ".#. #.# .#. #.# .##", "@": "### #.# ### #.. ###",
    "#": "#.# ### #.# ### #.#", "=": "... ### ... ### ...", "_": "... ... ... ... ###",
    "*": "... #.# .#. #.# ...",
}.items()}


def blank(v=0):
    return [[v] * W for _ in range(H)]


def put(fb, glyph, x, y0=0):
    for y, row in enumerate(glyph):
        for dx, c in enumerate(row):
            if c == "#" and 0 <= x + dx < W:
                fb[y0 + y][x + dx] = 1


def draw_digits(fb, a, b, c, d, colon=True):
    """Clock-style layout: [narrow 1 or blank] gap [b] colon [c] gap [d]. a is 0 or 1."""
    if a:
        for y in range(7):
            fb[y][0] = 1
    put(fb, BIG[str(b)], 2)
    if colon:
        fb[2][5] = fb[4][5] = 1
    put(fb, BIG[str(c)], 6)
    put(fb, BIG[str(d)], 10)


def clock_frame(t, offline=False):
    fb = blank()
    h12 = t.tm_hour % 12 or 12
    draw_digits(fb, h12 // 10, h12 % 10, t.tm_min // 10, t.tm_min % 10, colon=t.tm_sec % 2 == 0)
    if t.tm_hour >= 12:
        fb[7][12] = 1  # PM dot
    if offline:
        fb[7][0] = 1  # offline marker
    return fb


def duration_frame(secs, blink=True):
    """M:SS under 20 min, MM under 1 h, then H:MM (up to 19:59)."""
    secs = max(0, int(secs))
    fb = blank()
    if secs < 1200:
        m, s = divmod(secs, 60)
        draw_digits(fb, m // 10, m % 10, s // 10, s % 10, colon=blink)
    elif secs < 3600:
        m = secs // 60
        put(fb, BIG[str(m // 10)], 3)
        put(fb, BIG[str(m % 10)], 7)
    else:
        h, m = divmod(min(secs, 19 * 3600 + 59 * 60) // 60, 60)
        draw_digits(fb, h // 10, h % 10, m // 10, m % 10, colon=blink)
    return fb


def text_columns(text):
    cols = []
    for ch in text:
        g = SMALL.get(ch, SMALL["?"])
        for x in range(3):
            cols.append([g[y][x] == "#" for y in range(5)])
        cols.append([False] * 5)
    return cols


# ---------------------------------------------------------------- text rules

DEVANAGARI = re.compile("[ऀ-ॿ]")


def sanitize(text, max_len=80):
    """Uppercase ASCII. Non-ASCII words become [HINDI MSG] / [EMOJI] / [MSG], per segment."""
    out = []
    for word in str(text).split():
        if word.isascii():
            tag = word.upper()
        elif DEVANAGARI.search(word):
            tag = "[HINDI MSG]"
        elif any(unicodedata.category(c) == "So" or 0x1F000 <= ord(c) <= 0x1FFFF for c in word):
            tag = "[EMOJI]"
        else:
            tag = "[MSG]"
        if not (tag.startswith("[") and out and out[-1] == tag):  # collapse repeats
            out.append(tag)
    s = " ".join(out)
    return s if len(s) <= max_len else s[:max_len].rstrip() + "..."


# ---------------------------------------------------------------- animations
# Each animation is a generator yielding (frame, brightness_scale 0..1) once per tick.

def hold(fb, ms, scale=1.0):
    for _ in range(max(1, int(ms / 1000 / TICK))):
        yield fb, scale


def flashes(n, on_ms, off_ms):
    for _ in range(n):
        yield from hold(blank(1), on_ms)
        yield from hold(blank(), off_ms)


def wipe():
    for x in range(W):
        fb = blank()
        for y in range(H):
            for c in range(x + 1):
                fb[y][c] = 1
        yield from hold(fb, 70)
    yield from hold(blank(), 150)


def strobe(ms):
    for _ in range(int(ms / 100)):
        yield from hold(blank(1), 50)
        yield from hold(blank(), 50)


def border_chase(laps=2):
    path = [(0, x) for x in range(W)] + [(y, W - 1) for y in range(1, H)] + \
           [(H - 1, x) for x in range(W - 2, -1, -1)] + [(y, 0) for y in range(H - 2, 0, -1)]
    for _ in range(laps):
        for i in range(len(path)):
            fb = blank()
            for k in range(4):
                y, x = path[(i - k) % len(path)]
                fb[y][x] = 1
            yield fb, 1.0


def pulse():
    steps = 12
    for i in list(range(1, steps + 1)) + list(range(steps, 0, -1)):
        yield blank(1), i / steps


def scroll(text, ms_per_px=50):
    cols = text_columns(text)
    for off in range(0, len(cols) + 1):  # start with text already on screen
        fb = blank()
        for x in range(W):
            i = off + x
            if 0 <= i < len(cols):
                for y in range(5):
                    if cols[i][y]:
                        fb[y + 1][x] = 1
        yield from hold(fb, 1000 if off == 0 else ms_per_px)  # pause so the start can be read


PATTERNS = {
    "whatsapp": lambda: flashes(2, 250, 250),
    "instagram": lambda: flashes(3, 250, 250),
    "sms": lambda: flashes(1, 1000, 300),
    "gmail": wipe,
    "end": lambda: strobe(3000),
    "task": border_chase,
    "battery": pulse,
    "network": pulse,
    "media": lambda: flashes(1, 400, 300),
}


# ---------------------------------------------------------------- state

class Board:
    def __init__(self):
        self.lock = threading.RLock()
        self.settings = {"brightness": 5, "privacy": False, "max_len": 80, "scroll_ms": 90,
                         "apps": ["whatsapp", "instagram", "sms", "gmail"], "media": True}
        self.tasks = []    # {id, title, due_at, remind_before_min, reminded, fired}
        self.alarms = []   # {id, time "HH:MM", label, enabled}
        self.next_id = 1
        self.load()
        self.queue = []    # heap of (prio, seq, factory)
        self.seq = itertools.count()
        self.current = None  # (prio, factory, generator)
        self.call = None     # {"name", "number"} while ringing
        self.alarm_ringing = False
        self.mode = None     # None | timer | stopwatch | pomodoro
        self.timer_end = 0
        self.sw = {"running": False, "start": 0.0, "acc": 0.0}
        self.pomo = {}
        self.battery_low_sent = False
        self.battery_full_sent = False
        self.last_track = None
        self.offline = False
        self.frame = blank()

    # persistence
    def load(self):
        try:
            with open(STATE_FILE) as f:
                d = json.load(f)
            self.settings.update(d.get("settings", {}))
            self.tasks, self.alarms = d.get("tasks", []), d.get("alarms", [])
            self.next_id = d.get("next_id", 1)
        except (OSError, ValueError):
            pass

    def save(self):
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"settings": self.settings, "tasks": self.tasks, "alarms": self.alarms,
                       "next_id": self.next_id}, f)
        os.replace(tmp, STATE_FILE)

    def new_id(self):
        self.next_id += 1
        return self.next_id - 1

    # event queue
    def push(self, prio, factory):
        with self.lock:
            heapq.heappush(self.queue, (prio, next(self.seq), factory))

    def push_alert(self, prio, pattern, text):
        ms = self.settings["scroll_ms"]
        self.push(prio, lambda: itertools.chain(PATTERNS[pattern](), scroll(text, ms)))

    # phone events
    def handle_event(self, e):
        kind = e.get("type")
        maxlen = int(self.settings["max_len"])
        if kind == "notification":
            app = e.get("app")
            if app not in APP_CODES or app not in self.settings["apps"]:
                return
            parts = [APP_CODES[app], sanitize(e.get("sender", ""), 30) + ":"]
            if not self.settings["privacy"]:
                parts.append(sanitize(e.get("text", ""), maxlen))
            self.push_alert(P_MESSAGE, app, " ".join(parts).rstrip(":"))
        elif kind == "call":
            state = e.get("state")
            with self.lock:
                if state == "ringing":
                    self.call = {"name": sanitize(e.get("name", ""), 30),
                                 "number": sanitize(e.get("number", ""), 20)}
                    self.push(P_CALL, self.call_anim)
                else:
                    self.call = None
        elif kind == "battery":
            level = int(e.get("level", 50))
            with self.lock:
                if level >= 25:
                    self.battery_low_sent = False
                if level < 95:
                    self.battery_full_sent = False
                if level < 20 and not self.battery_low_sent:
                    self.battery_low_sent = True
                    self.push_alert(P_BATTERY, "battery", f"PHONE BATTERY {level}%")
                if level >= 100 and not self.battery_full_sent:
                    self.battery_full_sent = True
                    self.push_alert(P_BATTERY, "battery", "PHONE CHARGED 100%")
        elif kind == "media":
            title = sanitize(e.get("title", ""), 60)
            artist = sanitize(e.get("artist", ""), 30)
            key = (title, artist)
            if self.settings["media"] and e.get("playing", True) and title and key != self.last_track:
                self.last_track = key
                self.push_alert(P_MESSAGE, "media", f"NOW PLAYING {title} - {artist}".rstrip(" -"))

    def call_anim(self):
        while self.call:
            c = self.call
            yield from flashes(2, 300, 300)
            if not self.call:
                return
            yield from scroll(f"CALL {c['name']} {c['number']}".strip(), self.settings["scroll_ms"])

    def alarm_anim(self, label):
        self.alarm_ringing = True
        end = time.time() + 60
        while self.alarm_ringing and time.time() < end:
            yield from strobe(1000)
            if label:
                yield from scroll(f"ALARM {label}", self.settings["scroll_ms"])
        self.alarm_ringing = False

    # once-a-second checks
    def tick_schedules(self, now):
        lt = time.localtime(now)
        changed = False
        for t in self.tasks:
            remind_at = t["due_at"] - 60 * t.get("remind_before_min", 0)
            if t.get("remind_before_min") and not t.get("reminded") and remind_at <= now < t["due_at"]:
                t["reminded"] = changed = True
                self.push_alert(P_TASK, "task", f"IN {t['remind_before_min']} MIN: {sanitize(t['title'])}")
            if not t.get("fired") and now >= t["due_at"]:
                t["fired"] = t["reminded"] = changed = True
                if now - t["due_at"] < 300:  # don't replay old tasks after a long power-off
                    self.push_alert(P_TASK, "task", f"NOW: {sanitize(t['title'])}")
        hm = time.strftime("%H:%M", lt)
        for a in self.alarms:
            if a["enabled"] and a["time"] == hm:
                a["enabled"] = False  # one-shot
                changed = True
                label = sanitize(a.get("label", ""))
                self.push(P_END, lambda label=label: self.alarm_anim(label))
        if changed:
            self.save()
        if self.mode == "timer" and now >= self.timer_end:
            self.mode = None
            self.push_alert(P_END, "end", "TIME UP")
        if self.mode == "pomodoro" and now >= self.pomo["end"]:
            p = self.pomo
            if p["phase"] == "work":
                p["count"] += 1
                long_break = p["count"] % 4 == 0
                p["phase"] = "break"
                p["end"] = now + 60 * (p["long"] if long_break else p["brk"])
                self.push_alert(P_END, "end", "LONG BREAK" if long_break else "BREAK")
            else:
                p["phase"] = "work"
                p["end"] = now + 60 * p["work"]
                self.push_alert(P_END, "end", "WORK")

    def base_frame(self, now):
        blink = int(now * 2) % 2 == 0
        if self.mode == "timer":
            return duration_frame(self.timer_end - now + 0.999, blink)
        if self.mode == "stopwatch":
            sw = self.sw
            el = sw["acc"] + (now - sw["start"] if sw["running"] else 0)
            return duration_frame(el, blink if sw["running"] else True)
        if self.mode == "pomodoro":
            fb = duration_frame(self.pomo["end"] - now + 0.999, blink)
            if self.pomo["phase"] == "break":
                fb[7][12] = 1
            return fb
        return clock_frame(time.localtime(now), self.offline)

    def next_frame(self, now):
        """Returns (frame, brightness 0..7)."""
        with self.lock:
            if self.queue and (self.current is None or self.queue[0][0] < self.current[0]):
                if self.current:  # preempted: replay it from the start later
                    self.push(self.current[0], self.current[1])
                prio, _, factory = heapq.heappop(self.queue)
                self.current = (prio, factory, iter(factory()))
            bright = int(self.settings["brightness"])
            if self.current:
                try:
                    fb, scale = next(self.current[2])
                    return fb, max(1, round(bright * scale)) if bright else 0
                except StopIteration:
                    self.current = None
            return self.base_frame(now), bright

    # API commands
    def command(self, path, body):
        with self.lock:
            now = time.time()
            if path == "/event":
                self.handle_event(body)
            elif path == "/settings":
                for k, v in body.items():
                    if k in self.settings and type(v) is type(self.settings[k]):
                        self.settings[k] = v
                self.settings["brightness"] = max(0, min(7, self.settings["brightness"]))
                self.save()
            elif path == "/tasks":
                t = {"id": self.new_id(), "title": str(body["title"])[:100], "due_at": int(body["due_at"]),
                     "remind_before_min": max(0, int(body.get("remind_before_min", 0)))}
                self.tasks.append(t)
                self.save()
                return t
            elif path == "/alarms":
                if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", str(body["time"])):
                    raise ValueError("time must be HH:MM")
                a = {"id": self.new_id(), "time": body["time"], "label": str(body.get("label", ""))[:40],
                     "enabled": True}
                self.alarms.append(a)
                self.save()
                return a
            elif path == "/alarm/dismiss":
                self.alarm_ringing = False
            elif path == "/timer":
                if body.get("action") == "stop":
                    self.mode = None if self.mode == "timer" else self.mode
                else:
                    self.mode, self.timer_end = "timer", now + max(1, int(body["seconds"]))
            elif path == "/stopwatch":
                a, sw = body.get("action"), self.sw
                if a == "start" and not sw["running"]:
                    self.mode, sw["running"], sw["start"] = "stopwatch", True, now
                elif a == "pause" and sw["running"]:
                    sw["running"], sw["acc"] = False, sw["acc"] + now - sw["start"]
                elif a == "reset":
                    self.sw = {"running": False, "start": 0.0, "acc": 0.0}
                elif a == "stop":
                    self.mode = None if self.mode == "stopwatch" else self.mode
            elif path == "/pomodoro":
                if body.get("action") == "stop":
                    self.mode = None if self.mode == "pomodoro" else self.mode
                else:
                    work, brk, lng = (int(body.get(k, d)) for k, d in (("work", 25), ("break", 5), ("long", 15)))
                    self.pomo = {"phase": "work", "end": now + 60 * work, "count": 0,
                                 "work": work, "brk": brk, "long": lng}
                    self.mode = "pomodoro"
            else:
                return None
            return {"ok": True}

    def delete(self, path):
        m = re.fullmatch(r"/(tasks|alarms)/(\d+)", path)
        if not m:
            return None
        with self.lock:
            lst = getattr(self, m.group(1))
            lst[:] = [x for x in lst if x["id"] != int(m.group(2))]
            self.save()
        return {"ok": True}

    def snapshot(self):
        with self.lock:
            now = time.time()
            return {"settings": self.settings, "tasks": self.tasks, "alarms": self.alarms,
                    "mode": self.mode, "offline": self.offline,
                    "timer_left": max(0, self.timer_end - now) if self.mode == "timer" else None,
                    "stopwatch": self.sw, "pomodoro": self.pomo or None,
                    "alarm_ringing": self.alarm_ringing, "frame": frame_str(self.frame)}


def frame_str(fb):
    return "".join(str(p) for row in fb for p in row)


# ---------------------------------------------------------------- HTTP API

def make_handler(board):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, obj):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/state":
                self.reply(200, board.snapshot())
            elif self.path == "/frame":
                with board.lock:
                    self.reply(200, {"frame": frame_str(board.frame), "w": W, "h": H})
            else:
                self.reply(404, {"error": "not found"})

        def do_POST(self):
            try:
                n = int(self.headers.get("Content-Length", 0))
                if n > 16384:
                    raise ValueError("body too large")
                body = json.loads(self.rfile.read(n) or b"{}")
                if not isinstance(body, dict):
                    raise ValueError("body must be an object")
                res = board.command(self.path, body)
                self.reply(200, res) if res is not None else self.reply(404, {"error": "not found"})
            except (ValueError, KeyError, TypeError) as e:
                self.reply(400, {"error": str(e)})

        def do_DELETE(self):
            res = board.delete(self.path)
            self.reply(200, res) if res is not None else self.reply(404, {"error": "not found"})

        def log_message(self, *a):
            pass

    return Handler


# ---------------------------------------------------------------- network watchdog

def internet_ok():
    for host in ("1.1.1.1", "8.8.8.8"):
        try:
            socket.create_connection((host, 53), timeout=3).close()
            return True
        except OSError:
            pass
    return False


def wifi_ok():
    try:
        with open("/sys/class/net/wlan0/operstate") as f:
            return f.read().strip() == "up"
    except OSError:
        return False


def network_watchdog(board):
    fails, state = 0, "ONLINE"
    while True:
        if not wifi_ok():
            new = "NO WIFI"
        elif internet_ok():
            new, fails = "ONLINE", 0
        else:
            fails += 1
            new = "NO INTERNET" if fails >= 2 else state
        if new != state:
            state = new
            with board.lock:
                board.offline = state != "ONLINE"
            board.push_alert(P_NETWORK, "network", state)
        time.sleep(10)


# ---------------------------------------------------------------- MCU link

def pack(fb):
    """104 pixels row-major, MSB-first, into four uint32 words."""
    bits = [p for row in fb for p in row] + [0] * 24
    return [int("".join(map(str, bits[i:i + 32])), 2) for i in range(0, 128, 32)]


def rpc_request(msgid, method, params):
    """Minimal msgpack encoding of [0, msgid, method, params] (uint32 params only)."""
    m = method.encode()
    out = bytearray([0x94, 0x00, 0xCE]) + struct.pack(">I", msgid)
    out += bytes([0xA0 | len(m)]) + m + bytes([0x90 | len(params)])
    for p in params:
        out += b"\xce" + struct.pack(">I", p)
    return bytes(out)


def render_loop(board):
    msgid, sock, last, last_sent, last_sched = 0, None, None, 0.0, 0
    while True:
        start = time.time()
        if int(start) != last_sched:
            last_sched = int(start)
            with board.lock:
                board.tick_schedules(start)
        fb, bright = board.next_frame(start)
        with board.lock:
            board.frame = fb
        try:
            if sock is None:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.settimeout(2)
                sock.connect(SOCK)
                last = None
            # Resend every 2 s anyway so the MCU's 10 s no-link watchdog stays quiet.
            if (fb, bright) != last or start - last_sent > 2:
                msgid = (msgid + 1) & 0xFFFFFFFF
                sock.sendall(rpc_request(msgid, "frame", pack(fb) + [bright]))
                sock.recv(256)  # discard the response
                last, last_sent = (fb, bright), start
        except OSError as e:
            print("router link error:", e, flush=True)
            sock = None
            time.sleep(2)
        time.sleep(max(0.0, TICK - (time.time() - start)))


def show(fb):
    print("\n".join("".join("#" if p else "." for p in row) for row in fb))


def main():
    if "--preview" in sys.argv:
        show(clock_frame(time.localtime()))
        return
    board = Board()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), make_handler(board))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=network_watchdog, args=(board,), daemon=True).start()
    render_loop(board)


if __name__ == "__main__":
    main()
