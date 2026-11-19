#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打印素材清单摘要。

用法：
    python tools/inspect_session.py samples/demo
"""

import json
import os
import sys


def main():
    if len(sys.argv) != 2:
        print("用法：python tools/inspect_session.py <素材目录>")
        return 2

    path = os.path.normpath(sys.argv[1])
    manifest_path = os.path.join(path, "session.json") if os.path.isdir(path) else path
    if not os.path.isfile(manifest_path):
        print("找不到 session.json：%s" % manifest_path)
        return 2

    with open(manifest_path, "r", encoding="utf-8") as fh:
        manifest = json.load(fh)

    recording = manifest.get("recording", {})
    usage = manifest.get("usage", {})
    counts = manifest.get("counts", {})
    device = manifest.get("device", {})

    print("会话：%s" % manifest.get("session_id"))
    print("创建：%s" % manifest.get("created_at"))
    print("设备：%s %s / SDK %s" % (device.get("manufacturer"), device.get("model"), device.get("sdk")))
    print("距离：%.1f m" % recording.get("distance_m", 0))
    print("时长：%.1f s" % (recording.get("duration_ms", 0) / 1000.0))
    print("事件：" + ", ".join("%s=%s" % (k, v) for k, v in sorted(counts.items())))
    print("使用：%s/%s 次%s" % (
        usage.get("used_count", 0),
        usage.get("max_uses", 3),
        ("，上次 " + usage["last_used_at"]) if usage.get("last_used_at") else "",
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
