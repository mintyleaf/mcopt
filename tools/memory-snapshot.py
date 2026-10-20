#!/usr/bin/env python3
"""Capture macOS and JVM memory summaries without forcing GC or dumping the heap.

Run twice against the same Minecraft PID, several minutes apart:
  python3 tools/memory-snapshot.py PID --label early --baseline
  python3 tools/memory-snapshot.py PID --label grown

For NMT reports, launch Minecraft with -XX:NativeMemoryTracking=summary first.
Without that option, heap and macOS reports still work. Use --jcmd for the
executable from the game's JDK if the default JDK cannot attach.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def capture(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        return result.returncode, result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        return 1, "Command timed out after 30 seconds.\n"
    except OSError as error:
        return 1, str(error) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pid", type=int, help="Minecraft Java PID, shown by jcmd -l")
    parser.add_argument("--label", default="snapshot", help="For example: early, grown, title-screen")
    parser.add_argument("--baseline", action="store_true", help="Set an NMT baseline after capturing")
    parser.add_argument("--jcmd", default=shutil.which("jcmd") or "jcmd")
    parser.add_argument("--output", type=Path, help="New output directory; default: a temporary directory")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This helper uses macOS memory tools.")
    if args.pid <= 0:
        parser.error("PID must be positive.")

    pid = str(args.pid)
    status, process = capture(["/bin/ps", "-p", pid, "-o", "pid=,rss=,vsz=,etime=,comm="])
    if status or not process.strip():
        parser.error(f"Process {pid} is not running.")
    label = re.sub(r"[^A-Za-z0-9_-]", "_", args.label)
    if args.output:
        output = args.output.expanduser().resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        output = Path(tempfile.mkdtemp(prefix=f"mcopt-memory-{label}-"))

    commands = {
        "process.txt": ["/bin/ps", "-p", pid, "-o", "pid,rss,vsz,etime,comm"],
        "system.txt": ["/usr/sbin/sysctl", "hw.model", "hw.memsize", "kern.osproductversion", "vm.swapusage"],
        "vm-stat.txt": ["/usr/bin/vm_stat"],
        "vmmap.txt": ["/usr/bin/vmmap", "-summary", pid],
        "footprint.txt": ["/usr/bin/footprint", "--swapped", "-p", pid],
    }
    failures = []
    # Independent OS reads can overlap; JVM attach commands below run sequentially.
    with ThreadPoolExecutor(max_workers=len(commands)) as executor:
        jobs = {name: executor.submit(capture, command) for name, command in commands.items()}
        for name, job in jobs.items():
            code, report = job.result()
            (output / name).write_text(report)
            if code:
                failures.append(name)

    java_commands = {
        "heap.txt": ["GC.heap_info"],
        "vm-flags.txt": ["VM.flags"],
        "nmt.txt": ["VM.native_memory", "summary", "scale=MB"],
        "nmt-baseline.txt" if args.baseline else "nmt-diff.txt":
            ["VM.native_memory", "baseline"] if args.baseline else ["VM.native_memory", "summary.diff", "scale=MB"],
    }
    for name, command in java_commands.items():
        code, report = capture([args.jcmd, pid, *command])
        (output / name).write_text(report)
        if code or "Native memory tracking is not enabled" in report or "No baseline for comparison" in report:
            failures.append(name)

    (output / "capture.txt").write_text(
        f"Time: {datetime.now().astimezone().isoformat()}\nPID: {pid}\nLabel: {args.label}\n"
        "RSS and VSZ in process.txt are KiB. VSZ is reserved address space, not physical RAM.\n"
        "NMT covers JVM allocations, not all third-party native/Metal allocations.\n"
        "No GC requested; no heap dump created. Memory tools may briefly pause the process.\n"
        f"Unavailable reports: {', '.join(failures) or 'none'}\n"
    )
    print(output)
    if failures:
        print(f"Some reports unavailable; details saved in: {', '.join(failures)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
