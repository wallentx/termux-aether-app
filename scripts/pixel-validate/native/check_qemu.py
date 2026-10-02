"""CI correctness/dispatch coverage only. Never interpret emulation timings as performance."""
import json
import subprocess
import sys

binary = sys.argv[1]
for cpu in ("max,sve-max-vq=1", "max,sve-max-vq=2", "max,sve-max-vq=4", "max,sve=off"):
    prefix = ["qemu-aarch64", "-L", "/usr/aarch64-linux-gnu", "-cpu", cpu, binary]
    result = subprocess.run([*prefix, "--self-test"], capture_output=True, text=True, check=True, timeout=30)
    print(cpu, result.stdout.strip())
    assert "scalar PASS" in result.stdout and "neon PASS" in result.stdout
    assert ("sve2 SKIP" if "sve=off" in cpu else "sve2 PASS") in result.stdout
    for variant in ("scalar", "neon", "sve2"):
        result = subprocess.run([*prefix, variant, "0.1"], capture_output=True, text=True, check=True, timeout=30)
        report = json.loads(result.stdout)
        if variant == "sve2" and "sve=off" in cpu:
            assert report["status"] == "SKIP", report
        else:
            assert report["status"] == report["correctness"] == "PASS", report
            assert report["checksum"] == 705953792, report
            if variant == "sve2":
                assert report["vector_length_bytes"] == int(cpu.rsplit("=", 1)[1]) * 16, report
    for invalid in ("nan", "inf", "0", "6", "junk"):
        result = subprocess.run([*prefix, "scalar", invalid], capture_output=True, timeout=5)
        assert result.returncode == 2
