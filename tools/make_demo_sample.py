#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 samples/demo 示例素材（可复现）。

用法：
    python tools/make_demo_sample.py
"""

import json
import os

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "samples", "demo")

BASE_UTC_MS = 1763000000000
BASE_ELAPSED_NS = 5_000_000_000
LAT0, LON0 = 30.000000, 120.000000
LAT_STEP = 2.3e-5  # 约 2.56 m
SPEED = 2.56

PRE_ROLL_MS = 60_000
RUN_MS = 60_000
TAIL_MS = 15_000
END_MS = PRE_ROLL_MS + RUN_MS + TAIL_MS


def ts(rel_ms):
    return {
        "utc_ms": BASE_UTC_MS + rel_ms,
        "elapsed_ns": BASE_ELAPSED_NS + rel_ms * 1_000_000,
        "rel_ms": rel_ms,
    }


def location(rel_ms, lat, lon, speed, satellites=12, snr=35.0, hdop=0.9):
    return {
        "t": ts(rel_ms),
        "type": "location",
        "lat": round(lat, 7),
        "lon": round(lon, 7),
        "alt": 12.0,
        "acc_h": 4.0,
        "acc_v": 6.0,
        "speed": speed,
        "bearing": 0.0,
        "provider": "gps",
        "satellites": satellites,
        "snr_avg": snr,
        "hdop": hdop,
        "is_mock": False,
    }


def marker(rel_ms, name):
    return {"t": ts(rel_ms), "type": "marker", "name": name, "note": ""}


def step(rel_ms, counter, delta, detector=False):
    return {"t": ts(rel_ms), "type": "step", "counter": counter, "delta": delta, "detector": detector}


def imu(rel_ms, sensor, values):
    return {"t": ts(rel_ms), "type": "imu", "sensor": sensor, "values": values, "accuracy": 3}


def activity(rel_ms, name, confidence):
    return {"t": ts(rel_ms), "type": "activity", "activity": name, "confidence": confidence}


def baro(rel_ms, pressure, altitude):
    return {"t": ts(rel_ms), "type": "baro", "pressure_hpa": pressure, "altitude_m": altitude}


def device(rel_ms, battery, charging, screen, network):
    return {
        "t": ts(rel_ms),
        "type": "device",
        "battery_pct": battery,
        "charging": charging,
        "screen_on": screen,
        "network": network,
    }


def build_events():
    events = [marker(0, "record_start"), activity(0, "still", 80)]

    # 前奏：静止站立，定位逐步收敛
    for second in range(0, PRE_ROLL_MS // 1000):
        rel = second * 1000
        progress = second / (PRE_ROLL_MS / 1000)
        sats = int(4 + 8 * progress)
        snr = 18 + 17 * progress
        hdop = 1.6 - 0.7 * progress
        events.append(location(rel, LAT0, LON0, 0.0, sats, round(snr, 1), round(hdop, 2)))
        if second % 10 == 0:
            events.append(baro(rel, 1013.2, 12.0))
            events.append(device(rel, 88, False, True, "wifi"))

    events.append(marker(PRE_ROLL_MS, "run_start"))
    events.append(activity(PRE_ROLL_MS, "running", 90))

    counter = 12000
    events.append(step(PRE_ROLL_MS, counter, 0))
    for second in range(0, RUN_MS // 1000 + 1):
        rel = PRE_ROLL_MS + second * 1000
        lat = LAT0 + LAT_STEP * second
        events.append(location(rel, lat, LON0, SPEED))
        if second % 10 == 0 and second > 0:
            events.append(activity(rel, "running", 90))
            events.append(baro(rel, 1013.0 - second * 0.002, 12.0 + second * 0.01))
        if second < RUN_MS // 1000:
            for k in range(3):
                counter += 1
                events.append(step(rel + k * 300, counter, 1, detector=(k == 0)))

    # 跑步段 IMU：加速度 5Hz，陀螺 2.5Hz
    for tick in range(0, RUN_MS // 200 + 1):
        rel = PRE_ROLL_MS + tick * 200
        phase = (tick % 5) / 5.0
        events.append(imu(rel, "accelerometer", [round(0.6 + 0.4 * phase, 3), 9.5, round(0.3 - 0.2 * phase, 3)]))
        if tick % 2 == 0:
            events.append(imu(rel, "gyroscope", [0.02, 0.01, round(0.15 * phase, 3)]))

    events.append(marker(PRE_ROLL_MS + RUN_MS, "run_end"))

    # 结束段：原地静止
    last_lat = LAT0 + LAT_STEP * (RUN_MS // 1000)
    for second in range(1, TAIL_MS // 1000 + 1):
        rel = PRE_ROLL_MS + RUN_MS + second * 1000
        events.append(location(rel, last_lat, LON0, 0.0))

    events.append(device(END_MS, 86, False, True, "wifi"))
    events.append(marker(END_MS, "record_end"))

    events.sort(key=lambda e: e["t"]["rel_ms"])
    return events


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    events = build_events()

    counts = {}
    for event in events:
        counts[event["type"]] = counts.get(event["type"], 0) + 1

    distance = LAT_STEP * (RUN_MS // 1000) * 111_320.0
    manifest = {
        "schema_version": 1,
        "session_id": "20251113-005320-demo01",
        "created_at": "2025-11-13T00:53:20Z",
        "device": {
            "manufacturer": "Demo",
            "model": "Sample",
            "sdk": 33,
            "rom": "demo-build",
        },
        "app": {"version": "1.0.0-alpha", "build": "demo"},
        "recording": {
            "started_at": "2025-11-13T00:53:20Z",
            "ended_at": "2025-11-13T00:55:35Z",
            "duration_ms": END_MS,
            "distance_m": round(distance, 1),
        },
        "counts": counts,
        "environment": {
            "weather": "clear",
            "surface": "track",
            "wear_position": "hand",
            "satellite_note": "demo data",
        },
        "usage": {"max_uses": 3, "used_count": 0, "last_used_at": ""},
    }

    with open(os.path.join(OUT_DIR, "session.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    with open(os.path.join(OUT_DIR, "recording.jsonl"), "w", encoding="utf-8") as fh:
        for event in events:
            fh.write(json.dumps(event, ensure_ascii=False) + "\n")

    print("生成 %d 条事件 -> %s" % (len(events), OUT_DIR))


if __name__ == "__main__":
    main()
