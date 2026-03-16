"""
IntegrityPolygon Bot Attack Simulator
=====================================
Simulates bot attacks against a Velocity proxy to test the Anti-Bot module.

Usage:
    python bot_simulator.py                  # Run all tests
    python bot_simulator.py --test flood     # Run specific test
    python bot_simulator.py --host 1.2.3.4   # Custom target
"""

import socket
import struct
import json
import time
import threading
import sys
import os
import argparse
from datetime import datetime


def write_varint(value):
    result = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        if value != 0:
            byte |= 0x80
        result += struct.pack("B", byte)
        if value == 0:
            break
    return result


def write_string(text):
    encoded = text.encode("utf-8")
    return write_varint(len(encoded)) + encoded


def write_unsigned_short(value):
    return struct.pack(">H", value)


def make_handshake_packet(protocol_version, server_address, server_port, next_state):
    data = write_varint(protocol_version)
    data += write_string(server_address)
    data += write_unsigned_short(server_port)
    data += write_varint(next_state)
    packet_id = write_varint(0x00)
    packet_data = packet_id + data
    return write_varint(len(packet_data)) + packet_data


def make_login_start_packet(username):
    data = write_varint(0x00)
    data += write_string(username)
    data += os.urandom(16)  # Random UUID
    return write_varint(len(data)) + data


def make_status_request_packet():
    packet_id = write_varint(0x00)
    return write_varint(len(packet_id)) + packet_id


def read_packet(sock, timeout=3):
    sock.settimeout(timeout)
    try:
        length = 0
        num_read = 0
        while True:
            byte = sock.recv(1)
            if not byte:
                return None
            b = byte[0]
            length |= (b & 0x7F) << (7 * num_read)
            num_read += 1
            if (b & 0x80) == 0:
                break
            if num_read > 5:
                return None
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                return None
            data += chunk
        return data
    except Exception:
        return None


class BotSimulator:
    def __init__(self, host="localhost", port=25577):
        self.host = host
        self.port = port
        self.results = {"attempted": 0, "accepted": 0, "rejected": 0, "failed": 0, "disconnects": []}
        self.lock = threading.Lock()

    def log(self, msg, level="INFO"):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print("  [%s] [%s] %s" % (ts, level, msg))

    def attempt_connection(self, username, log_result=True):
        with self.lock:
            self.results["attempted"] += 1
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5)
            sock.connect((self.host, self.port))
            handshake = make_handshake_packet(769, self.host, self.port, 2)
            sock.sendall(handshake)
            login = make_login_start_packet(username)
            sock.sendall(login)
            response = read_packet(sock, timeout=5)
            sock.close()

            if response is None:
                with self.lock:
                    self.results["failed"] += 1
                if log_result:
                    self.log("  %s: no response" % username, "WARN")
                return False, "no_response"

            packet_id = response[0]
            if packet_id == 0x00:
                try:
                    idx = 1
                    str_len = 0
                    shift = 0
                    while idx < len(response):
                        b = response[idx]
                        str_len |= (b & 0x7F) << shift
                        idx += 1
                        shift += 7
                        if (b & 0x80) == 0:
                            break
                    reason = response[idx:idx + str_len].decode("utf-8", errors="replace")
                except Exception:
                    reason = "unknown"
                with self.lock:
                    self.results["rejected"] += 1
                    self.results["disconnects"].append({"user": username, "reason": reason})
                if log_result:
                    self.log("  %s: REJECTED - %s" % (username, reason[:80]), "FAIL")
                return False, reason
            elif packet_id == 0x02:
                with self.lock:
                    self.results["accepted"] += 1
                if log_result:
                    self.log("  %s: ACCEPTED" % username, "OK")
                return True, "accepted"
            else:
                with self.lock:
                    self.results["failed"] += 1
                if log_result:
                    self.log("  %s: Unknown packet 0x%02x" % (username, packet_id), "WARN")
                return False, "unknown"
        except socket.timeout:
            with self.lock:
                self.results["failed"] += 1
            if log_result:
                self.log("  %s: TIMEOUT" % username, "WARN")
            return False, "timeout"
        except ConnectionRefusedError:
            with self.lock:
                self.results["failed"] += 1
            if log_result:
                self.log("  %s: REFUSED" % username, "FAIL")
            return False, "refused"
        except Exception as e:
            with self.lock:
                self.results["failed"] += 1
            if log_result:
                self.log("  %s: ERROR - %s" % (username, e), "FAIL")
            return False, str(e)

    def attempt_ping(self):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5)
            sock.connect((self.host, self.port))
            handshake = make_handshake_packet(769, self.host, self.port, 1)
            sock.sendall(handshake)
            status_req = make_status_request_packet()
            sock.sendall(status_req)
            response = read_packet(sock, timeout=5)
            sock.close()
            if response and len(response) > 1:
                idx = 1
                str_len = 0
                shift = 0
                while idx < len(response):
                    b = response[idx]
                    str_len |= (b & 0x7F) << shift
                    idx += 1
                    shift += 7
                    if (b & 0x80) == 0:
                        break
                json_str = response[idx:idx + str_len].decode("utf-8", errors="replace")
                return json.loads(json_str)
            return None
        except Exception as e:
            self.log("  Ping failed: %s" % e, "WARN")
            return None

    def reset(self):
        self.results = {"attempted": 0, "accepted": 0, "rejected": 0, "failed": 0, "disconnects": []}

    def summary(self, name):
        r = self.results
        print()
        print("  +--- %s Results ---" % name)
        print("  | Attempted:  %d" % r["attempted"])
        print("  | Accepted:   %d" % r["accepted"])
        print("  | Rejected:   %d" % r["rejected"])
        print("  | Failed:     %d" % r["failed"])
        reasons = {}
        for d in r["disconnects"]:
            short = d["reason"][:60]
            reasons[short] = reasons.get(short, 0) + 1
        if reasons:
            print("  |")
            print("  | Rejection reasons:")
            for reason, count in sorted(reasons.items(), key=lambda x: -x[1]):
                print("  |   %dx -- %s" % (count, reason))
        print("  +========================================")
        print()


def test_ping(sim):
    print()
    sim.log("=== TEST: Server Ping ===", "TEST")
    result = sim.attempt_ping()
    if result:
        sim.log("Server responded!", "OK")
        players = result.get("players", {})
        sim.log("Players: %d/%d" % (players.get("online", 0), players.get("max", 0)), "OK")
        version = result.get("version", {})
        sim.log("Version: %s (protocol %s)" % (version.get("name", "?"), version.get("protocol", "?")), "OK")
    else:
        sim.log("Server did not respond to ping", "FAIL")


def test_single(sim):
    print()
    sim.log("=== TEST: Single Login ===", "TEST")
    sim.reset()
    sim.attempt_connection("TestPlayer_1")
    sim.summary("Single Login")


def test_rapid(sim, count=10, delay=0.1):
    print()
    sim.log("=== TEST: Rapid Joins (%d joins, %.1fs delay) ===" % (count, delay), "TEST")
    sim.reset()
    for i in range(count):
        sim.attempt_connection("Bot_%03d" % i)
        time.sleep(delay)
    sim.summary("Rapid Joins")


def test_flood(sim, count=20, threads=10):
    print()
    sim.log("=== TEST: Concurrent Flood (%d connections, %d threads) ===" % (count, threads), "TEST")
    sim.reset()

    def worker(start, size):
        for i in range(size):
            sim.attempt_connection("Flood_%03d" % (start + i), log_result=False)

    batch = count // threads
    thrs = []
    t0 = time.time()
    for t in range(threads):
        th = threading.Thread(target=worker, args=(t * batch, batch))
        thrs.append(th)
        th.start()
    for th in thrs:
        th.join()
    elapsed = time.time() - t0
    sim.log("Completed in %.2fs (%.1f conn/sec)" % (elapsed, count / elapsed), "INFO")
    sim.summary("Concurrent Flood")


def test_username_spam(sim, count=5):
    print()
    sim.log("=== TEST: Same Username x%d ===" % count, "TEST")
    sim.reset()
    for i in range(count):
        sim.attempt_connection("StaffMember")
        time.sleep(0.2)
    sim.summary("Username Spam")


def test_invalid(sim):
    print()
    sim.log("=== TEST: Invalid Protocol Data ===", "TEST")
    tests = [
        ("Random bytes", os.urandom(64)),
        ("Empty", b""),
        ("Null flood", b"\x00" * 100),
        ("HTTP GET", b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"),
    ]
    for name, data in tests:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(3)
            sock.connect((sim.host, sim.port))
            sock.sendall(data)
            time.sleep(0.5)
            try:
                resp = sock.recv(1024)
                sim.log("  %s: got %d bytes response" % (name, len(resp)), "WARN")
            except Exception:
                sim.log("  %s: connection dropped" % name, "OK")
            sock.close()
        except Exception as e:
            sim.log("  %s: %s" % (name, e), "WARN")
        time.sleep(0.2)


def main():
    parser = argparse.ArgumentParser(description="Bot Attack Simulator")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=25577)
    parser.add_argument("--test",
                        choices=["ping", "single", "rapid", "flood", "username", "invalid", "all"],
                        default="all")
    args = parser.parse_args()

    print()
    print("  +===================================================+")
    print("  |   IntegrityPolygon Bot Attack Simulator            |")
    print("  +===================================================+")
    print("  Target: %s:%d" % (args.host, args.port))
    print()

    sim = BotSimulator(args.host, args.port)

    tests = {
        "ping": test_ping,
        "single": test_single,
        "rapid": lambda s: test_rapid(s, 10, 0.1),
        "flood": lambda s: test_flood(s, 20, 10),
        "username": test_username_spam,
        "invalid": test_invalid,
    }

    if args.test == "all":
        for name, fn in tests.items():
            try:
                fn(sim)
            except Exception as e:
                sim.log("Test '%s' crashed: %s" % (name, e), "FAIL")
            time.sleep(1)
    else:
        tests[args.test](sim)

    print()
    print("  All tests complete!")
    print("  Check the IntegrityPolygon dashboard for alerts and logs.")
    print()


if __name__ == "__main__":
    main()

