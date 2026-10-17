import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

import validate


def response(payload):
    return {"status": "PASS", "detail": json.dumps(payload), "seconds": 0.01}


def capabilities():
    return {"status": "PASS", "data": {"schema_version": 1, **{
        key: {"status": "ok"} for key in ("device", "cpu", "battery", "thermal", "shizuku")}}}


def batch(variant):
    return {"schema_version": 1, "workload": "byte_absdiff_sum", "variant": variant,
            "status": "PASS", "correctness": "PASS", "checksum": 705953792,
            "bytes_per_iteration": 16777216, "iterations": 10 if variant == "scalar" else 20,
            "elapsed_seconds": 0.5, "vector_length_bytes": 0 if variant == "scalar" else 16}


class BridgeTests(unittest.TestCase):
    @mock.patch.object(validate.shutil, "which", return_value=None)
    def test_missing_commands_skip_without_starting_a_process(self, _):
        with mock.patch.object(validate, "run") as run:
            snapshot = validate.bridge_snapshot()
        self.assertEqual("SKIP", snapshot["capabilities"]["status"])
        self.assertEqual("SKIP", snapshot["thermal"]["status"])
        run.assert_not_called()

    @mock.patch.object(validate.shutil, "which", side_effect=lambda path: path)
    def test_invalid_schema_or_json_never_passes(self, _):
        for detail in ("not json", "[]", '{"schema_version":2}', '{"schema_version":1,"x":NaN}'):
            with mock.patch.object(validate, "run", return_value={"status": "PASS", "detail": detail, "seconds": 0}):
                self.assertEqual("FAIL", validate.json_command(["tool"])["status"])

    @mock.patch.object(validate.shutil, "which", side_effect=lambda path: path)
    def test_transport_failures_remain_failures(self, _):
        with mock.patch.object(validate, "run", return_value={"status": "FAIL", "detail": "Timed out"}):
            self.assertEqual("FAIL", validate.json_command(["tool"])["status"])

    def test_permission_and_service_states_are_not_temperature_passes(self):
        for status in ("denied", "unavailable", "unsupported", "busy", "partial", "timeout"):
            thermal = {"status": "PASS", "data": {"schema_version": 1, "operation": "thermal", "status": status}}
            with mock.patch.object(validate, "json_command", side_effect=[capabilities(), thermal]) as command:
                snapshot = validate.bridge_snapshot()
            self.assertEqual("FAIL" if status == "timeout" else "SKIP", snapshot["thermal"]["status"])
            self.assertEqual(status, snapshot["thermal"]["data"]["status"])
            self.assertNotIn("request-permission", repr(command.call_args_list))

    def test_malformed_success_is_not_a_pass(self):
        for payload in ({"operation": "status", "status": "ok"}, {"operation": "thermal", "status": "ok"}):
            with mock.patch.object(validate, "json_command", side_effect=[capabilities(), {"status": "PASS", "data": payload}]):
                self.assertEqual("FAIL", validate.bridge_snapshot()["thermal"]["status"])

    def test_sampler_finishes_inflight_read_before_exit(self):
        called = threading.Event()
        def collect():
            called.set()
            return {"timestamp_unix_ms": 123}
        with mock.patch.object(validate, "bridge_snapshot", side_effect=collect):
            with validate.ThermalSampler(True, interval=0.001) as sampler:
                self.assertTrue(called.wait(timeout=1))
        self.assertFalse(sampler.worker.is_alive())
        self.assertGreaterEqual(len(sampler.samples), 1)

    def test_thermal_summary_ignores_missing_readings_and_preserves_no_data(self):
        missing = {"thermal": {"status": "SKIP"}, "capabilities": {"status": "SKIP"}}
        self.assertIsNone(validate.thermal_summary([missing])["max_android_throttling_status"])
        def snapshot(value, severity):
            return {"thermal": {"status": "PASS", "data": {"temperatures": [
                {"name": "GPU", "type": "gpu", "celsius": value},
                {"name": "CPU", "type": "cpu", "celsius": None}]}},
                "capabilities": {"data": {"thermal": {"throttling": {"status": "ok", "value": severity}}}}}
        summary = validate.thermal_summary([missing, snapshot(30, 0), snapshot(40, 2)])
        self.assertEqual(2, summary["max_android_throttling_status"])
        self.assertEqual([{"name": "GPU", "type": "gpu", "min_celsius": 30, "max_celsius": 40, "samples": 2}],
                         summary["temperatures"])


class SimdTests(unittest.TestCase):
    def test_fixture_checksum_is_independently_reproducible(self):
        period = sum(abs(((i * 17 + 13) % 256) - ((i * 29 + 7) % 256)) for i in range(256))
        self.assertEqual(705953792, period * (8 * 1024 * 1024 // 256))

    def test_rotated_samples_and_speedup(self):
        def command(args, **unused):
            return {"status": "PASS", "data": batch(args[1])}
        with mock.patch.object(validate, "json_command", side_effect=command) as run:
            results = validate.simd_benchmarks(Path("fixture"), 3, 0.5)
        self.assertEqual(["scalar", "neon", "sve2", "neon", "sve2", "scalar", "sve2", "scalar", "neon"],
                         [call.args[0][1] for call in run.call_args_list])
        self.assertEqual([1, 2, 2], [result["speedup_vs_scalar"] for result in results])
        self.assertEqual([320, 640, 640], [result["MiB_per_second"] for result in results])

    def test_bad_checksum_wrong_kernel_and_incomplete_timing_fail(self):
        for field, value in (("checksum", 0), ("variant", "wrong"), ("iterations", 0),
                             ("iterations", True), ("elapsed_seconds", 0.01),
                             ("elapsed_seconds", float("inf")), ("correctness", "FAIL")):
            def command(args, **unused):
                data = batch(args[1]); data[field] = value
                return {"status": "PASS", "data": data}
            with mock.patch.object(validate, "json_command", side_effect=command):
                results = validate.simd_benchmarks(Path("fixture"), 3, 0.5)
            self.assertTrue(all(result["status"] == "FAIL" for result in results), (field, results))
            self.assertTrue(all("speedup_vs_scalar" not in result for result in results))

    def test_unsupported_kernel_is_skipped_once(self):
        def command(args, **unused):
            data = batch(args[1])
            if args[1] == "sve2": data.update(status="SKIP", reason="unsupported")
            return {"status": "PASS", "data": data}
        with mock.patch.object(validate, "json_command", side_effect=command) as run:
            results = validate.simd_benchmarks(Path("fixture"), 3, 0.5)
        self.assertEqual("SKIP", results[2]["status"])
        self.assertEqual(7, run.call_count)
        self.assertNotIn("speedup_vs_scalar", results[2])

    def test_missing_explicit_binary_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            results = validate.simd_benchmarks(Path(directory) / "missing", 3, 0.5)
        self.assertTrue(all(result["status"] == "FAIL" for result in results))


if __name__ == "__main__":
    unittest.main()
