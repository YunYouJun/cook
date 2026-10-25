#!/usr/bin/env python3
"""Wait for one explicitly selected iOS simulator before handing it to Xcode."""

import argparse
import json
import re
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("udid", help="目标模拟器 UDID（xcrun simctl list devices available）")
    parser.add_argument("--timeout", type=int, default=360, help="全部准备步骤的总超时秒数，默认 360")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")

    started = time.monotonic()
    deadline = started + args.timeout
    stage = "读取设备列表"

    def run(*command):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(command, args.timeout)
        return subprocess.run(command, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, timeout=remaining).stdout

    try:
        devices = json.loads(run("xcrun", "simctl", "list", "devices", "available", "--json"))["devices"]
        matches = [(runtime, device) for runtime, entries in devices.items() for device in entries
                   if device["udid"] == args.udid]
        if len(matches) != 1 or not matches[0][0].startswith("com.apple.CoreSimulator.SimRuntime.iOS-"):
            raise RuntimeError("未找到该 UDID 对应的可用 iOS 模拟器")
        runtime, device = matches[0]
        if device["state"] not in ("Shutdown", "Booting", "Booted"):
            raise RuntimeError("设备正在切换状态，请等待其结束后再准备：" + device["state"])
        print(f"准备 {device['name']} ({args.udid})，当前状态 {device['state']}", flush=True)

        stage = "检查运行时共享缓存"
        print(stage, flush=True)
        # 已存在时立即返回；不强制删除/重建缓存，不操作其他运行时。
        print(run("xcrun", "simctl", "runtime", "dyld_shared_cache", "update", runtime).strip(), flush=True)

        stage = "等待设备与系统界面启动"
        print(stage, flush=True)
        # bootstatus -b 只在需要时启动，并等待数据迁移和系统界面就绪。
        output = run("xcrun", "simctl", "bootstatus", args.udid, "-b")
        print("\n".join(output.strip().splitlines()[-8:]), flush=True)

        stage = "确认 SpringBoard 可响应"
        output = run("xcrun", "simctl", "spawn", args.udid, "launchctl", "print",
                     "user/foreground/com.apple.SpringBoard")
        if not re.search(r"^\s*state = running\s*$", output, re.MULTILINE):
            raise RuntimeError("SpringBoard 尚未运行")
        print(f"模拟器已就绪，耗时 {time.monotonic() - started:.1f} 秒。可以开始 xcodebuild test。")
        return 0
    except subprocess.TimeoutExpired:
        print(f"{stage}超时（总预算 {args.timeout} 秒）；未清空或强制关闭设备。", file=sys.stderr)
    except subprocess.CalledProcessError as error:
        print(f"{stage}失败：\n" + "\n".join((error.stdout or "").splitlines()[-12:]), file=sys.stderr)
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        print(f"{stage}失败：{error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
