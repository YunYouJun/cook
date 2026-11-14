#!/usr/bin/env python3
"""Capture Xcode output and stop its owned process group before archiving."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys


class Interrupted(Exception):
    def __init__(self, signum):
        self.signum = signum


def interrupt(signum, _frame):
    raise Interrupted(signum)


def signal_command(process, sig):
    try:
        os.killpg(process.pid, sig)
    except ProcessLookupError:
        pass
    except PermissionError:
        # Some simctl children can make signaling the entire group fail.
        # Fall back to the direct child without assuming we own other PIDs.
        try:
            process.send_signal(sig)
        except ProcessLookupError:
            pass
        except PermissionError:
            print(f'Could not signal iOS command PID {process.pid} with {sig.name}', file=sys.stderr)


def stop_command(process, sig, grace):
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal_command(process, sig)
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        pass
    # The parent can exit while a descendant still holds a result bundle open.
    signal_command(process, signal.SIGKILL)
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        print(f'iOS command PID {process.pid} did not exit after cleanup', file=sys.stderr)


def main():
    log = Path(sys.argv[1])
    timeout_value = os.environ.get('COOK_IOS_COMMAND_TIMEOUT_SECONDS')
    timeout = float(timeout_value) if timeout_value else None
    if timeout is not None and timeout <= 0:
        print('COOK_IOS_COMMAND_TIMEOUT_SECONDS must be positive', file=sys.stderr)
        return 2
    result = 1
    try:
        with log.open('wb') as output:
            process = subprocess.Popen(sys.argv[2:], stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            signal.signal(signal.SIGINT, interrupt)
            signal.signal(signal.SIGTERM, interrupt)
            try:
                result = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                stop_command(process, signal.SIGTERM, grace=2)
                print(f"IOS command timed out after {timeout:g} seconds: {' '.join(sys.argv[2:])}", file=sys.stderr)
                result = 124
            except Interrupted as error:
                stop_command(process, signal.SIGINT, grace=10)
                result = 128 + error.signum
    finally:
        # Replay even a partial startup log, after its writer has stopped.
        if log.exists():
            with log.open('rb') as output:
                shutil.copyfileobj(output, sys.stdout.buffer)
            sys.stdout.buffer.flush()
    return result if result >= 0 else 128 - result


if __name__ == '__main__':
    sys.exit(main())
