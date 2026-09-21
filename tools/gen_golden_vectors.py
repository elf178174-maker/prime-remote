"""
Generates protocol golden vectors by running LEGO's own reference implementation
(https://github.com/LEGO/spike-prime-docs, examples/python) so that the Kotlin port
in :core can be asserted byte-for-byte against it.

Usage:  python3 tools/gen_golden_vectors.py /path/to/spike-prime-docs/examples/python
Writes: core/src/test/resources/golden_vectors.json
"""
import json
import os
import random
import struct
import sys

ref = sys.argv[1] if len(sys.argv) > 1 else "/home/user/lego/spike-prime-docs/examples/python"
sys.path.insert(0, ref)

import cobs  # noqa: E402
from crc import crc  # noqa: E402

random.seed(20260921)

def rnd(n, lo=0, hi=255):
    return bytes(random.randint(lo, hi) for _ in range(n))

cobs_inputs = [
    b"",
    b"\x00",
    b"\x01",
    b"\x02",
    b"\x03",
    b"\x00\x00\x00",
    b"\x01\x02\x03\x04",
    b"hello world",
    bytes(range(256)),
    b"\xff" * 83,
    b"\xff" * 84,
    b"\xff" * 85,
    b"\xff" * 200,
    b"\x00" * 90,
    b"\x02" * 90,
    bytes([0] * 40 + [255] * 100 + [2] * 40),
    rnd(300),
    rnd(512, 0, 3),
    b"\x00" + b"\xaa" * 83 + b"\x02",
    b'\x32\x10\x00mv A 500;mv B -500;',
]

crc_cases = []
for data, seed, align in [
    (b"", 0, 4),
    (b"a", 0, 4),
    (b"abc", 0, 4),
    (b"abcd", 0, 4),
    (b"abcde", 0, 4),
    (b"hello world", 0, 4),
    (b"hello world", 12345, 4),
    (rnd(100), 0, 4),
    (rnd(101), 0xDEADBEEF, 4),
    (rnd(255), 987654321, 4),
    (b"import runloop\nprint('hi')\n", 0, 4),
]:
    crc_cases.append({
        "data": data.hex(),
        "seed": seed,
        "align": align,
        "crc": crc(data, seed, align) & 0xFFFFFFFF,
    })

# --- message serialization (mirrors examples/python/messages.py) ---
messages = []

def add(name, payload):
    messages.append({"name": name, "payload": payload.hex(), "framed": cobs.pack(payload).hex()})

add("InfoRequest", b"\0")
add("ClearSlotRequest(slot=3)", struct.pack("<BB", 0x46, 3))
name = "program.py".encode("utf8")
add(
    "StartFileUploadRequest(program.py, slot=19, crc=0x12345678)",
    struct.pack(f"<B{len(name)+1}sBI", 0x0C, name, 19, 0x12345678),
)
chunk = b"import runloop\n"
add(
    "TransferChunkRequest(crc=0xAABBCCDD)",
    struct.pack(f"<BIH{len(chunk)}s", 0x10, 0xAABBCCDD, len(chunk), chunk),
)
add("ProgramFlowRequest(start, slot=19)", struct.pack("<BBB", 0x1E, 0, 19))
add("ProgramFlowRequest(stop, slot=19)", struct.pack("<BBB", 0x1E, 1, 19))
add("DeviceNotificationRequest(200ms)", struct.pack("<BH", 0x28, 200))
tunnel = b"mv A 500;mv B -500;"
add("TunnelMessage", struct.pack(f"<BH{len(tunnel)}s", 0x32, len(tunnel), tunnel))
add("SetHubNameRequest", struct.pack("<B31s", 0x16, b"Rover"))
add("GetHubNameRequest", struct.pack("<B", 0x18))
add("DeviceUuidRequest", struct.pack("<B", 0x1A))

# --- responses the app must be able to parse ---
responses = []

def add_response(name, payload):
    responses.append({"name": name, "payload": payload.hex(), "framed": cobs.pack(payload).hex()})

add_response(
    "InfoResponse",
    struct.pack("<BBBHBBHHHHH", 0x01, 1, 0, 0, 3, 4, 3, 20, 302, 288, 0),
)
add_response("ClearSlotResponse(ok)", struct.pack("<BB", 0x47, 0x00))
add_response("StartFileUploadResponse(ok)", struct.pack("<BB", 0x0D, 0x00))
add_response("TransferChunkResponse(fail)", struct.pack("<BB", 0x11, 0x01))
add_response("ProgramFlowResponse(ok)", struct.pack("<BB", 0x1F, 0x00))
add_response("ProgramFlowNotification(stop)", struct.pack("<BB", 0x20, 1))
console = b"!rdy 1 poll\x00\x00"
add_response("ConsoleNotification", struct.pack(f"<B{len(console)}s", 0x21, console))
add_response("DeviceNotificationResponse(ok)", struct.pack("<BB", 0x29, 0x00))

dev_payload = b"".join([
    struct.pack("<BB", 0x00, 87),                                   # battery 87%
    struct.pack("<BBBhhhhhhhhh", 0x01, 0, 1, 90, -3, 4, 10, 20, 980, 1, -2, 3),  # imu
    struct.pack("<BBBhhbi", 0x0A, 0, 0x31, 45, 500, 42, 1234),      # motor on port A
    struct.pack("<BBBhhbi", 0x0A, 1, 0x30, -45, -500, -42, -1234),  # motor on port B
    struct.pack("<BBBB", 0x0B, 2, 55, 1),                           # force sensor on C
    struct.pack("<BBbHHH", 0x0C, 3, 9, 300, 100, 120),              # color sensor on D
    struct.pack("<BBh", 0x0D, 4, 137),                              # distance sensor on E
])
add_response(
    "DeviceNotification",
    struct.pack(f"<BH{len(dev_payload)}s", 0x3C, len(dev_payload), dev_payload),
)

out = {
    "_generated_by": "tools/gen_golden_vectors.py against LEGO/spike-prime-docs",
    "cobs": [
        {
            "raw": d.hex(),
            "encoded": bytes(cobs.encode(d)).hex(),
            "packed": cobs.pack(d).hex(),
        }
        for d in cobs_inputs
    ],
    "crc": crc_cases,
    "messages": messages,
    "responses": responses,
}

dest = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "core", "src", "test", "resources", "golden_vectors.json")
os.makedirs(os.path.dirname(dest), exist_ok=True)
with open(dest, "w") as f:
    json.dump(out, f, indent=1)
print(f"wrote {dest}")
print(f"  cobs vectors:      {len(out['cobs'])}")
print(f"  crc vectors:       {len(out['crc'])}")
print(f"  message vectors:   {len(out['messages'])}")
print(f"  response vectors:  {len(out['responses'])}")

# sanity: round-trip every cobs vector through the reference implementation
for d in cobs_inputs:
    assert bytes(cobs.unpack(cobs.pack(d))) == d, d.hex()
print("reference round-trip OK")
