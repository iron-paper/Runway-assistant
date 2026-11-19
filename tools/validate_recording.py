#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""素材校验：按 docs/DATA_SPEC.md 第 5 节检查一份录制素材。

用法：
    python tools/validate_recording.py samples/demo
    python tools/validate_recording.py samples/demo --require-loop --max-speed 6.0

退出码：0 通过；1 失败；2 参数或文件错误。
"""

import argparse
import json
import math
import os
import sys

SCHEMA_VERSION = 1
REQUIRED_LOCATION_FIELDS = ("lat", "lon", "alt", "acc_h", "speed", "bearing", "provider")


def load_events(path):
    events = []
    with open(path, "r", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError("第 %d 行不是合法 JSON：%s" % (lineno, exc))
    return events


def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def check(events, max_speed, require_loop):
    errors = []
    warnings = []
    locations = []
    steps = []
    last_rel = -1
    last_elapsed = -1
    run_start_ms = 0

    for index, event in enumerate(events):
        t = event.get("t")
        if not isinstance(t, dict):
            errors.append("事件 %d 缺少时间戳" % index)
            continue
        rel = t.get("rel_ms")
        elapsed = t.get("elapsed_ns")
        if not isinstance(rel, int) or not isinstance(elapsed, int):
            errors.append("事件 %d 时间戳字段类型错误" % index)
            continue
        if rel < last_rel:
            errors.append("事件 %d rel_ms 回退：%d < %d" % (index, rel, last_rel))
        if elapsed < last_elapsed:
            errors.append("事件 %d elapsed_ns 回退" % index)
        last_rel = max(last_rel, rel)
        last_elapsed = max(last_elapsed, elapsed)

        etype = event.get("type")
        if etype == "marker" and event.get("name") == "run_start":
            run_start_ms = rel
        if etype == "location":
            missing = [f for f in REQUIRED_LOCATION_FIELDS if f not in event]
            if missing:
                errors.append("事件 %d 定位字段缺失：%s" % (index, ",".join(missing)))
                continue
            if not (-90 <= event["lat"] <= 90 and -180 <= event["lon"] <= 180):
                errors.append("事件 %d 坐标越界" % index)
            locations.append(event)
        elif etype == "step":
            steps.append(event)

    if len(locations) < 2:
        errors.append("定位点不足：%d" % len(locations))
    else:
        # 速度检查覆盖全部定位点（前奏静止段速度接近 0，不影响）
        for prev, cur in zip(locations, locations[1:]):
            d = haversine(prev["lat"], prev["lon"], cur["lat"], cur["lon"])
            dt = (cur["t"]["rel_ms"] - prev["t"]["rel_ms"]) / 1000.0
            if dt <= 0:
                errors.append("定位点时间差非正：%d" % cur["t"]["rel_ms"])
            else:
                speed = d / dt
                if speed > max_speed:
                    errors.append(
                        "定位点 %d 速度 %.2f m/s 超过上限 %.1f" % (cur["t"]["rel_ms"], speed, max_speed)
                    )

        # 里程与配速从 run_start 起算，避免前奏静止段拉低平均配速
        run_locations = [loc for loc in locations if loc["t"]["rel_ms"] >= run_start_ms]
        if len(run_locations) < 2:
            errors.append("跑步段定位点不足：%d" % len(run_locations))
        else:
            total = 0.0
            for prev, cur in zip(run_locations, run_locations[1:]):
                total += haversine(prev["lat"], prev["lon"], cur["lat"], cur["lon"])
            duration_s = (run_locations[-1]["t"]["rel_ms"] - run_locations[0]["t"]["rel_ms"]) / 1000.0
            if duration_s > 10 and total > 50:
                pace_min_per_km = (duration_s / 60.0) / (total / 1000.0)
                if not (2.0 <= pace_min_per_km <= 10.0):
                    errors.append("平均配速 %.2f 分/公里 超出 2–10 区间" % pace_min_per_km)
            if require_loop:
                gap = haversine(
                    run_locations[0]["lat"], run_locations[0]["lon"],
                    run_locations[-1]["lat"], run_locations[-1]["lon"],
                )
                if gap > 50:
                    errors.append("闭环素材首尾距离 %.1f m 超过 50 m" % gap)

    if len(steps) >= 2:
        first, last = steps[0], steps[-1]
        if last["counter"] < first["counter"]:
            errors.append("计步累计值回退")
        delta_sum = sum(int(s.get("delta", 0)) for s in steps)
        expected = last["counter"] - first["counter"]
        if delta_sum != expected:
            errors.append("步数增量之和 %d 与累计差 %d 不一致" % (delta_sum, expected))
        duration_s = (last["t"]["rel_ms"] - first["t"]["rel_ms"]) / 1000.0
        if duration_s > 0:
            cadence = delta_sum / duration_s * 60.0
            if not (40 <= cadence <= 300):
                errors.append("步频 %.1f 步/分 超出 40–300" % cadence)
    else:
        warnings.append("步事件少于 2 条，跳过步频校验")

    return errors, warnings


def main():
    parser = argparse.ArgumentParser(description="校验录制素材")
    parser.add_argument("path", help="素材目录或 recording.jsonl")
    parser.add_argument("--max-speed", type=float, default=6.0, help="最大速度 m/s（默认 6.0）")
    parser.add_argument("--require-loop", action="store_true", help="要求首尾闭合（≤50m）")
    args = parser.parse_args()

    path = args.path
    if os.path.isdir(path):
        manifest_path = os.path.join(path, "session.json")
        events_path = os.path.join(path, "recording.jsonl")
        if not os.path.isfile(events_path):
            print("找不到 recording.jsonl：%s" % path)
            return 2
        manifest = None
        if os.path.isfile(manifest_path):
            with open(manifest_path, "r", encoding="utf-8") as fh:
                manifest = json.load(fh)
        session_name = os.path.basename(os.path.normpath(path))
    else:
        manifest = None
        events_path = path
        session_name = os.path.basename(path)

    try:
        events = load_events(events_path)
    except ValueError as exc:
        print("读取失败：%s" % exc)
        return 2

    if manifest is not None and manifest.get("schema_version") != SCHEMA_VERSION:
        print("警告：schema_version=%s，当前校验器为 %d" % (manifest.get("schema_version"), SCHEMA_VERSION))

    errors, warnings = check(events, args.max_speed, args.require_loop)

    print("素材：%s" % session_name)
    print("事件：%d 条" % len(events))
    if manifest:
        rec = manifest.get("recording", {})
        print("距离：%.1f m  时长：%.1f s" % (rec.get("distance_m", 0), rec.get("duration_ms", 0) / 1000.0))
    print("规则：max_speed=%.1f m/s, require_loop=%s" % (args.max_speed, args.require_loop))
    for warning in warnings:
        print("  [警告] %s" % warning)
    for error in errors:
        print("  [错误] %s" % error)

    if errors:
        print("结论：未通过（%d 项错误）" % len(errors))
        return 1
    print("结论：通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
