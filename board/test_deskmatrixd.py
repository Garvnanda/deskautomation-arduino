"""Self-check, runs anywhere: python3 test_deskmatrixd.py"""
import os
import tempfile
import time

import deskmatrixd as d

d.STATE_FILE = os.path.join(tempfile.mkdtemp(), "state.json")

# sanitiser: per-segment placeholders, uppercase, truncation
assert d.sanitize("Khana ready hai") == "KHANA READY HAI"
assert d.sanitize("ok खाना तैयार है bye") == "OK [HINDI MSG] BYE"
assert d.sanitize("lol 😂😂 nice") == "LOL [EMOJI] NICE"
assert d.sanitize("x" * 100, 10) == "x".upper() * 10 + "..."

# fonts fit the matrix
assert all(len(g) == 7 and all(len(r) == 3 for r in g) for g in d.BIG.values())
assert all(len(g) == 5 and all(len(r) == 3 for r in g) for g in d.SMALL.values())
fb = d.clock_frame(time.struct_time((2026, 11, 2, 12, 45, 0, 0, 306, 0)))
assert len(fb) == 8 and all(len(r) == 13 for r in fb) and fb[7][12] == 1  # PM dot
assert len(d.pack(fb)) == 4

# priority: a call preempts a running message, message replays afterwards
b = d.Board()
b.handle_event({"type": "notification", "app": "whatsapp", "sender": "Mom", "text": "hi"})
b.next_frame(0)
assert b.current[0] == d.P_MESSAGE
b.handle_event({"type": "call", "state": "ringing", "name": "Mom", "number": "123"})
b.next_frame(0)
assert b.current[0] == d.P_CALL and b.queue[0][0] == d.P_MESSAGE
b.handle_event({"type": "call", "state": "ended"})

# privacy mode hides message text
b2 = d.Board()
b2.settings["privacy"] = True
b2.handle_event({"type": "notification", "app": "sms", "sender": "Ravi", "text": "secret"})
assert len(b2.queue) == 1

# battery alerts once per crossing
b3 = d.Board()
for lvl in (19, 18, 17):
    b3.handle_event({"type": "battery", "level": lvl})
assert len(b3.queue) == 1
for lvl in (30, 19):
    b3.handle_event({"type": "battery", "level": lvl})
assert len(b3.queue) == 2

# task reminder then due
b4 = d.Board()
now = time.time()
b4.command("/tasks", {"title": "standup", "due_at": int(now) + 300, "remind_before_min": 10})
b4.tick_schedules(now)
assert len(b4.queue) == 1
b4.tick_schedules(now + 301)
assert len(b4.queue) == 2

# timer end fires once
b5 = d.Board()
b5.command("/timer", {"seconds": 5})
b5.tick_schedules(time.time() + 6)
assert b5.mode is None and len(b5.queue) == 1

print("ok")
