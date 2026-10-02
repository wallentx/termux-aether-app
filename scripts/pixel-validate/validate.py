#!/usr/bin/env python3
"""Read-only package checks, temporary-file smoke tests, and optional small benchmarks."""

import argparse
import ctypes
import datetime
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import platform
import shutil
import signal
import ssl
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import zlib


# Linux arm64 UAPI arch/arm64/include/uapi/asm/hwcap.h. Detection is not kernel usage.
HWCAP = {"asimd": 1, "aes": 3, "sha2": 6, "crc32": 7, "asimdhp": 10,
         "sha3": 17, "asimddp": 20, "sha512": 21, "sve": 22, "asimdfhm": 23}
HWCAP2 = {"sve2": 1, "svei8mm": 9, "svebf16": 12, "i8mm": 13,
          "bf16": 14, "sme": 23, "sme2": 37}


def read_text(path):
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return None


def run(command, cwd=None, timeout=10, output_limit=4096):
    """Bound execution time/output; terminate the process group on timeout."""
    started = time.perf_counter()
    with tempfile.TemporaryFile() as output:
        try:
            child = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                     stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as error:
            return {"status": "FAIL", "detail": str(error), "seconds": time.perf_counter() - started}
        try:
            code = child.wait(timeout=timeout)
            status = "PASS" if code == 0 else "FAIL"
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            status, code = "FAIL", None
        output.seek(0)
        detail = output.read(output_limit).decode("utf-8", "replace").strip()
        if code is None:
            detail = "Timed out after %ss; process group killed. " % timeout + detail
    return {"status": status, "exit_code": code, "detail": detail,
            "seconds": round(time.perf_counter() - started, 6)}


def cpu_capabilities():
    if platform.machine().lower() not in ("aarch64", "arm64"):
        return {"status": "SKIP", "detail": "The HWCAP decoder supports ARM64 only"}
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        libc.getauxval.argtypes = [ctypes.c_ulong]
        libc.getauxval.restype = ctypes.c_ulong
        values = []
        for key in (16, 26):  # AT_HWCAP, AT_HWCAP2
            ctypes.set_errno(0)
            value = libc.getauxval(key)
            error = ctypes.get_errno()
            if error:
                raise OSError(error, os.strerror(error))
            values.append(value)
        features = {name: bool(values[0] & (1 << bit)) for name, bit in HWCAP.items()}
        features.update({name: bool(values[1] & (1 << bit)) for name, bit in HWCAP2.items()})
        vectors = {}
        libc.prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
        libc.prctl.restype = ctypes.c_int
        for name, operation in (("sve", 51), ("sme", 64)):
            if not features[name]:
                vectors[name] = {"status": "SKIP", "detail": "Not advertised by HWCAP"}
                continue
            ctypes.set_errno(0)
            value = libc.prctl(operation, 0, 0, 0, 0)
            error = ctypes.get_errno()
            vectors[name] = {"status": "PASS" if value >= 0 else "UNKNOWN",
                             "vector_length_bytes": value & 0xffff if value >= 0 else None,
                             "errno": error if value < 0 else 0}
        return {"status": "PASS", "source": "getauxval in this Python process",
                "hwcap": hex(values[0]), "hwcap2": hex(values[1]), "features": features,
                "thread_vector_lengths": vectors,
                "note": "Availability only; no optional SIMD instructions are executed or benchmarked."}
    except (AttributeError, OSError) as error:
        return {"status": "UNKNOWN", "detail": str(error)}


def power_snapshot():
    base = Path("/sys/class/power_supply/battery")
    return {name: read_text(base / name) for name in ("capacity", "temp", "status")}


def reject_json_constant(value):
    raise ValueError("Non-finite JSON number: " + value)


def json_command(command, timeout=25):
    """CLI exit zero is transport success, not proof that an API operation passed."""
    executable = shutil.which(str(command[0]))
    if not executable:
        return {"status": "SKIP", "detail": str(command[0]) + " is not on PATH"}
    result = run([executable, *command[1:]], timeout=timeout, output_limit=262144)
    if result["status"] != "PASS":
        return result
    try:
        payload = json.loads(result["detail"], parse_constant=reject_json_constant)
        if not isinstance(payload, dict) or payload.get("schema_version") != 1:
            raise ValueError("Expected a schema_version 1 JSON object")
        return {"status": "PASS", "data": payload, "seconds": result["seconds"]}
    except (ValueError, TypeError) as error:
        return {"status": "FAIL", "detail": "Invalid API/benchmark response: " + str(error)}


def bridge_snapshot():
    """Never prompts or starts Shizuku; unavailable/denied readings remain explicit."""
    capabilities = json_command(["termux-capabilities", "--json"])
    thermal = json_command(["termux-shizuku", "--thermal"])
    if capabilities["status"] == "PASS":
        data = capabilities["data"]
        if not all(isinstance(data.get(key), dict) for key in ("device", "cpu", "battery", "thermal", "shizuku")):
            capabilities.update(status="FAIL", detail="Capability response is missing required sections")
    if thermal["status"] == "PASS":
        data = thermal["data"]
        status = data.get("status")
        if data.get("operation") != "thermal":
            thermal.update(status="FAIL", detail="Response is not a thermal operation")
        elif status == "ok":
            if not isinstance(data.get("temperatures"), list) or not isinstance(data.get("other_readings"), list):
                thermal.update(status="FAIL", detail="Thermal response is missing sensor arrays")
        elif status in ("denied", "unavailable", "unsupported", "busy", "partial"):
            thermal.update(status="SKIP", detail="Thermal reading: " + status)
        else:
            thermal.update(status="FAIL", detail="Thermal reading: " + str(status))
    return {"timestamp_unix_ms": round(time.time() * 1000),
            "capabilities": capabilities, "thermal": thermal}


def bridge_checks(snapshot):
    return [dict(name=name, **snapshot[key]) for key, name in
            (("capabilities", "Android API capability bridge"), ("thermal", "Shizuku thermal snapshot"))]


def thermal_summary(snapshots):
    temperatures, throttling = {}, []
    for snapshot in snapshots:
        thermal = snapshot["thermal"]
        if thermal["status"] == "PASS":
            for sensor in thermal["data"].get("temperatures", []):
                if not isinstance(sensor, dict):
                    continue
                value = sensor.get("celsius")
                if type(value) in (int, float) and math.isfinite(value):
                    key = (str(sensor.get("name")), str(sensor.get("type")))
                    temperatures.setdefault(key, []).append(value)
        state = snapshot["capabilities"].get("data", {}).get("thermal", {}).get("throttling", {})
        if not isinstance(state, dict):
            continue
        value = state.get("value")
        if state.get("status") == "ok" and type(value) is int and 0 <= value <= 6:
            throttling.append(value)
    return {"snapshot_count": len(snapshots),
            "detailed_snapshot_statuses": {status: sum(s["thermal"]["status"] == status for s in snapshots)
                                           for status in ("PASS", "FAIL", "SKIP")},
            "temperatures": [{"name": name, "type": kind, "min_celsius": min(values),
                              "max_celsius": max(values), "samples": len(values)}
                             for (name, kind), values in sorted(temperatures.items())],
            "max_android_throttling_status": max(throttling) if throttling else None,
            "note": "Sampled observations only; brief temperature/throttling peaks can be missed."}


class ThermalSampler:
    """One bounded API request pair at a time; no overlapping thermal readers."""
    def __init__(self, enabled, interval=5):
        self.samples = []
        self.enabled = enabled
        self.interval = interval
        self.stop = threading.Event()
        self.worker = None

    def __enter__(self):
        if self.enabled:
            self.worker = threading.Thread(target=self.collect, daemon=True)
            self.worker.start()
        return self

    def collect(self):
        while not self.stop.wait(self.interval):
            self.samples.append(bridge_snapshot())

    def __exit__(self, *unused):
        self.stop.set()
        if self.worker:
            self.worker.join()  # Each of the two API calls has a 25-second deadline.


def simd_benchmarks(binary, samples, sample_seconds):
    """Rotate kernel order each round; native timings exclude launch/API sampling."""
    binary = str(binary.resolve())
    results = []
    for variant in ("scalar", "neon", "sve2"):
        results.append({"name": "SIMD byte absdiff: " + variant, "variant": variant,
                        "status": "PASS", "batches": []})
    for round_index in range(samples):
        for offset in range(3):
            result = results[(round_index + offset) % 3]
            if result["status"] != "PASS":
                continue
            response = json_command([binary, result["variant"], str(sample_seconds)], timeout=sample_seconds + 10)
            if response["status"] != "PASS":
                # An explicitly requested but missing binary is a failure.
                result.update(status="FAIL", detail=response.get("detail", "Native command failed"))
                continue
            batch = response["data"]
            try:
                if batch.get("variant") != result["variant"] or batch.get("workload") != "byte_absdiff_sum":
                    raise ValueError("Wrong variant or workload")
                if batch.get("status") == "SKIP":
                    result.update(status="SKIP", detail=batch.get("reason", "Unsupported ISA"))
                    continue
                if batch.get("status") != "PASS" or batch.get("correctness") != "PASS":
                    raise ValueError("Native correctness check failed")
                if batch.get("checksum") != 705953792 or batch.get("bytes_per_iteration") != 16777216:
                    raise ValueError("Unexpected fixture checksum or size")
                iterations, elapsed = batch["iterations"], batch["elapsed_seconds"]
                if type(iterations) is not int or iterations <= 0 or type(elapsed) not in (float, int) \
                        or not math.isfinite(elapsed) or elapsed < sample_seconds:
                    raise ValueError("Invalid or incomplete timing batch")
                batch["round"] = round_index
                result["batches"].append(batch)
            except (ValueError, KeyError, TypeError) as error:
                result.update(status="FAIL", detail=str(error))
    for result in results:
        if result["status"] == "PASS":
            seconds = [b["elapsed_seconds"] / b["iterations"] for b in result["batches"]]
            result.update(seconds=seconds, median_seconds=statistics.median(seconds),
                          MiB_per_second=16 / statistics.median(seconds), sample_target_seconds=sample_seconds)
    scalar = results[0]
    if scalar["status"] == "PASS":
        for result in results:
            if result["status"] == "PASS":
                result["speedup_vs_scalar"] = scalar["median_seconds"] / result["median_seconds"]
    return results


def metadata():
    properties = {}
    for name in ("ro.product.model", "ro.soc.model", "ro.build.version.release",
                 "ro.build.version.sdk", "ro.build.version.sdk_full"):
        result = run(["/system/bin/getprop", name])
        properties[name] = result["detail"] if result["status"] == "PASS" else None
    env_names = ("TERMUX_VERSION", "TERMUX_APP__VERSION_NAME", "TERMUX_APP__TARGET_SDK",
                 "TERMUX_APP__PACKAGE_MANAGER", "TERMUX_APP_PACKAGE_MANAGER")
    return {"android": properties, "termux_environment": {k: os.environ.get(k) for k in env_names},
            "architecture": platform.machine(), "uid": os.getuid(), "python": platform.python_version(),
            "openssl": ssl.OPENSSL_VERSION, "page_size": os.sysconf("SC_PAGESIZE"),
            "selinux_context": read_text("/proc/self/attr/current"),
            "preload_configured": bool(os.environ.get("LD_PRELOAD")),
            "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}


def smoke_tests(directory):
    checks = [sha256_known_answers()]

    def check(name, command, expected=None):
        executable = shutil.which(command[0])
        if not executable:
            result = {"status": "SKIP", "detail": command[0] + " is not on PATH"}
        else:
            result = run([executable, *command[1:]], cwd=directory)
            if result["status"] == "PASS" and expected is not None and result["detail"] != expected:
                result.update(status="FAIL", detail="Unexpected output: " + result["detail"])
        checks.append(dict(name=name, **result))

    check("shell child process and pipe", ["sh", "-c", "printf termux-ok | cat"], "termux-ok")
    check("Python child process", [sys.executable, "-c",
          "import subprocess; print(subprocess.check_output(['sh','-c','printf python-child-ok']).decode())"],
          "python-child-ok")
    # Exercise executable scripts, including a shebang with an argument and a path with spaces.
    shell = shutil.which("sh")
    if shell:
        script = directory / "script with spaces"
        script.write_text("#!%s -e\nprintf '%%s' \"$1\"\n" % shell)
        script.chmod(0o700)
        check("private shebang execution", [str(script), "argument with spaces"], "argument with spaces")
    else:
        checks.append({"name": "private shebang execution", "status": "SKIP", "detail": "sh is not installed"})
    for runtime in ("node", "bun"):
        check(runtime + " child process", [runtime, "-e",
              "process.stdout.write(require('child_process').execFileSync('sh',['-c','printf js-child-ok']))"],
              "js-child-ok")
    for tool, flag in (("git", "--version"), ("ssh", "-V"), ("tmux", "-V"),
                       ("gh", "--version"), ("go", "version"), ("npm", "--version"),
                       ("codex", "--version"), ("claude", "--version"), ("opencode", "--version")):
        check(tool + " startup", [tool, flag])
    check("Pacman local database", ["pacman", "-Q", "bash", "termux-exec"])
    try:
        original = directory / "file"
        renamed = directory / "renamed"
        link = directory / "link"
        original.write_bytes(b"termux-storage-check\0\xff")
        original.rename(renamed)
        link.symlink_to(renamed.name)
        if link.read_bytes() != b"termux-storage-check\0\xff":
            raise OSError("Readback did not match")
        checks.append({"name": "private storage write/rename/symlink", "status": "PASS"})
    except OSError as error:
        checks.append({"name": "private storage write/rename/symlink", "status": "FAIL", "detail": str(error)})
    return checks


def sha256_known_answers():
    # Fixed expected outputs, not generated by the implementation under test.
    vectors = (
        (b"", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        (b"abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        (b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
         "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
    )
    passed = all(hashlib.sha256(data).hexdigest() == expected for data, expected in vectors)
    return {"name": "SHA-256 known answers", "status": "PASS" if passed else "FAIL",
            "detail": "Three fixed vectors: empty, abc, and a multi-block message"}


def measure(name, operation, byte_count=None, samples=5, sample_seconds=0.5, max_iterations=None):
    """Report per-operation latency from timed batches; bound storage write volume."""
    batches = []
    try:
        operation()  # Warm-up also checks correctness.
        for _ in range(samples):
            start = time.perf_counter()
            iterations = 0
            while True:
                operation()
                iterations += 1
                elapsed = time.perf_counter() - start
                if elapsed >= sample_seconds or (max_iterations and iterations >= max_iterations):
                    break
            batches.append({"iterations": iterations, "elapsed_seconds": elapsed,
                            "target_reached": elapsed >= sample_seconds})
        seconds = [batch["elapsed_seconds"] / batch["iterations"] for batch in batches]
        median = statistics.median(seconds)
        result = {"name": name, "status": "PASS", "seconds": seconds, "median_seconds": median,
                  "batches": batches, "sample_target_seconds": sample_seconds,
                  "max_iterations_per_sample": max_iterations, "warmup_iterations": 1}
        if byte_count:
            result.update(MiB_per_second=byte_count / (1024 * 1024) / median,
                          bytes_per_operation=byte_count,
                          total_bytes_including_warmup=byte_count * (1 + sum(b["iterations"] for b in batches)))
        return result
    except (OSError, RuntimeError) as error:
        return {"name": name, "status": "FAIL", "detail": str(error), "batches": batches}


def benchmarks(directory, samples=5, sample_seconds=0.5):
    data = bytes(range(256)) * 32768  # Fixed synthetic 8 MiB input; no external downloads.
    # Pinned fixture digest, cross-checked with sha256sum independently of this runner.
    expected = bytes.fromhex("7d212b9c884f5c77896de960ae17cc341cda43b14d6a971f34ca29ebd4badf7f")

    def digest():
        for _ in range(8):
            if hashlib.sha256(data).digest() != expected:
                raise RuntimeError("SHA-256 output mismatch")

    def compression():
        if zlib.decompress(zlib.compress(data, 6)) != data:
            raise RuntimeError("Compression round trip failed")

    def storage():
        path = directory / "benchmark.bin"
        with path.open("wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if path.read_bytes() != data:
            raise RuntimeError("Storage readback mismatch")

    def spawn():
        for _ in range(10):
            result = run(["sh", "-c", "printf ok"], cwd=directory)
            if result["status"] != "PASS" or result["detail"] != "ok":
                raise RuntimeError("Child-process benchmark failed: " + result["detail"])

    return [measure(name, operation, size, samples, sample_seconds, cap)
            for name, operation, size, cap in (
                ("sha256_64MiB", digest, len(data) * 8, None),
                ("zlib_level6_8MiB_roundtrip", compression, len(data), None),
                ("private_storage_8MiB_fsync_readback", storage, len(data), 8),
                ("ten_shell_children", spawn, None, None))]


def sixel_pattern():
    """240x144 RGB bars over a black/white checkerboard; no image library needed."""
    width, height = 240, 144
    palette = ((100, 0, 0), (0, 100, 0), (0, 0, 100), (0, 0, 0), (100, 100, 100))
    parts = ['\x1bPq"1;1;%d;%d' % (width, height)]
    parts.extend('#%d;2;%d;%d;%d' % (index, *rgb) for index, rgb in enumerate(palette))

    def pixel(x, y):
        return x // 80 if y < 72 else 3 + (x // 8 + (y - 72) // 8) % 2

    for y in range(0, height, 6):
        for color in range(len(palette)):
            columns = [sum(1 << bit for bit in range(6) if pixel(x, y + bit) == color)
                       for x in range(width)]
            if not any(columns):
                continue
            parts.append('#%d' % color)
            for mask, group in itertools.groupby(columns):
                count = sum(1 for _ in group)
                char = chr(63 + mask)
                parts.append('!%d%s' % (count, char) if count >= 4 else char * count)
            parts.append('$')
        if y + 6 < height:
            parts.append('-')
    parts.append('\x1b\\')
    return ''.join(parts)


def sixel_check():
    result = {"name": "sixel rendering", "status": "NOT_RUN", "method": "visual_confirmation",
              "width_pixels": 240, "height_pixels": 144}
    if not sys.stdout.isatty() or not sys.stdin.isatty():
        return dict(result, status="SKIP", detail="Requires interactive stdin/stdout; run --sixel without redirection")
    print("\nSIXEL BEGIN - RGB bars above a checkerboard", flush=True)
    print(sixel_pattern(), end="\r\n", flush=True)
    print("SIXEL END - this text should be below the image.")
    print("Check: red/green/blue left to right; black/white squares below;")
    print("no escape-code text, missing bands, or overlap with SIXEL END.")
    try:
        answer = input("Type pass, fail, or Enter to leave unverified: ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        answer = ""
        print()
    result.update(status={"pass": "PASS", "fail": "FAIL"}.get(answer, "NOT_RUN"),
                  detail="Visual result supplied by operator" if answer in ("pass", "fail") else "No visual confirmation")
    return result


MANUAL = [
    "Long-press a word: Copy/Paste/More appears without touching a selection handle; repeat after dragging handles.",
    "Copy, paste, and More work; cancel selection, rotate, reopen keyboard, and repeat near the bottom row.",
    "Open a second session; switch apps and return; verify both sessions and the notification remain usable.",
    "Check shared-storage access and LAN SSH using your chosen files and host; no network probes run automatically.",
    "Review package installation/update and your usual agent workflows separately; startup checks do not prove these.",
]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bench", action="store_true", help="also run four benchmarks with timed batches")
    parser.add_argument("--simd-binary", type=Path, help="run the CI-built ARM64 SIMD benchmark at this path")
    parser.add_argument("--samples", type=int, default=5, help="measured batches per benchmark (3-15, default 5)")
    parser.add_argument("--sample-seconds", type=float, default=0.5, help="batch duration target (0.1-5, default 0.5)")
    parser.add_argument("--sixel", action="store_true", help="display a sixel pattern and ask for a visual result")
    parser.add_argument("--output", type=Path, help="new JSON report path (existing files are never overwritten)")
    args = parser.parse_args(argv)
    if not 3 <= args.samples <= 15 or not math.isfinite(args.sample_seconds) or not 0.1 <= args.sample_seconds <= 5:
        parser.error("--samples must be 3-15 and --sample-seconds must be finite and 0.1-5")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = args.output or Path.home() / ("termux-validation-" + stamp + ".json")
    # Reserve the output before running tests; no overwrite, and mode 0600 for diagnostic metadata.
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        report = {"schema_version": 3, "timestamp_utc": stamp, "metadata": metadata(),
                  "capabilities": cpu_capabilities(), "power_before": power_snapshot(),
                  "manual_checks": [{"status": "NOT_RUN", "instruction": item} for item in MANUAL],
                  "limitations": ["Run inside the installed Termux app, not adb shell or a VM.",
                                  "No packages are installed/updated and no remote hosts are contacted by this runner.",
                                  "Python benchmarks do not identify library SIMD dispatch; native SIMD results cover only byte absdiff.",
                                  "Thermal sampling adds background work; results are not universal package speedups.",
                                  "Storage results include page cache and Python overhead; they are not raw flash bandwidth.",
                                  "UID/SELinux describe this process; target SDK is reported from Termux's environment."]}
        temp_base = os.environ.get("TMPDIR") or str(Path.home())
        with tempfile.TemporaryDirectory(prefix="termux-validation-", dir=temp_base) as temporary:
            directory = Path(temporary)
            report["checks"] = smoke_tests(directory)
            report["bridge_before"] = bridge_snapshot()
            report["checks"].extend(bridge_checks(report["bridge_before"]))
            monitor = (args.bench or args.simd_binary) and any(
                report["bridge_before"][key]["status"] == "PASS" for key in ("capabilities", "thermal"))
            with ThermalSampler(monitor) as sampler:
                report["benchmarks"] = benchmarks(directory, args.samples, args.sample_seconds) if args.bench else []
                if args.simd_binary:
                    report["simd_binary_sha256"] = (hashlib.sha256(args.simd_binary.read_bytes()).hexdigest()
                                                    if args.simd_binary.is_file() else None)
                    report["benchmarks"].extend(simd_benchmarks(args.simd_binary, args.samples, args.sample_seconds))
            report["thermal_samples"] = sampler.samples
            if sampler.samples:
                failed = any(sample[key]["status"] == "FAIL" for sample in sampler.samples
                             for key in ("capabilities", "thermal"))
                report["checks"].append({"name": "Background API sampling", "status": "FAIL" if failed else "PASS",
                                         "detail": "Completed %d snapshots; see per-reading availability" % len(sampler.samples)})
            report["thermal_sample_interval_seconds"] = 5
            report["bridge_after"] = bridge_snapshot() if args.bench or args.simd_binary else report["bridge_before"]
            if args.bench or args.simd_binary:
                report["checks"].extend(dict(item, name=item["name"] + " after benchmarks")
                                        for item in bridge_checks(report["bridge_after"]))
            snapshots = [report["bridge_before"], *sampler.samples]
            if args.bench or args.simd_binary:
                snapshots.append(report["bridge_after"])
            report["thermal_summary"] = thermal_summary(snapshots)
        report["visual_checks"] = [sixel_check() if args.sixel else
                                   {"name": "sixel rendering", "status": "NOT_RUN", "detail": "Use --sixel to test"}]
        report["power_after"] = power_snapshot()
        report["summary"] = {status: sum(item["status"] == status for item in report["checks"] + report["benchmarks"])
                             for status in ("PASS", "FAIL", "SKIP")}
        json.dump(report, stream, indent=2)
        stream.write("\n")
    for item in report["checks"] + report["benchmarks"]:
        print("%-4s %s" % (item["status"], item["name"]))
        if "speedup_vs_scalar" in item:
            print("     %.1f MiB/s, %.2fx scalar" % (item["MiB_per_second"], item["speedup_vs_scalar"]))
    for item in report["visual_checks"]:
        print("%-4s %s (visual check, separate from automated summary)" % (item["status"], item["name"]))
    print("\nCapabilities: " + report["capabilities"]["status"] + " (availability, not measured acceleration)")
    print("Report: " + str(output.resolve()))
    print("Manual check: " + MANUAL[0])
    return 1 if report["summary"]["FAIL"] or any(item["status"] == "FAIL" for item in report["visual_checks"]) else 0


if __name__ == "__main__":
    sys.exit(main())
