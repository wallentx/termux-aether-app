import contextlib
import io
import json
import os
import re
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

import validate


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        bridge = mock.patch.object(validate, "bridge_snapshot", return_value={
            "capabilities": {"status": "SKIP", "detail": "not installed"},
            "thermal": {"status": "SKIP", "detail": "not installed"}})
        bridge.start()
        self.addCleanup(bridge.stop)

    def test_sha256_known_answers_detect_a_consistently_wrong_implementation(self):
        self.assertEqual("PASS", validate.sha256_known_answers()["status"])
        with mock.patch.object(validate.hashlib, "sha256") as digest:
            digest.return_value.hexdigest.return_value = "0" * 64
            self.assertEqual("FAIL", validate.sha256_known_answers()["status"])

    def test_timed_batches_normalize_latency_and_throughput(self):
        clock = [0.0]

        def operation():
            clock[0] += 0.1

        with mock.patch.object(validate.time, "perf_counter", side_effect=lambda: clock[0]):
            result = validate.measure("timed", operation, 1024 * 1024, 3, 0.25)
        self.assertEqual([3, 3, 3], [x["iterations"] for x in result["batches"]])
        self.assertAlmostEqual(0.1, result["median_seconds"])
        self.assertAlmostEqual(10, result["MiB_per_second"])
        self.assertEqual(10 * 1024 * 1024, result["total_bytes_including_warmup"])

    def test_storage_cap_is_reported_when_duration_target_is_not_reached(self):
        with mock.patch.object(validate.time, "perf_counter", return_value=1):
            operation = mock.Mock()
            result = validate.measure("bounded", operation, samples=3, max_iterations=8)
        self.assertEqual(25, operation.call_count)  # 1 warmup + 3*8 measured writes.
        self.assertTrue(all(not x["target_reached"] for x in result["batches"]))

    def test_benchmark_correctness_failure_is_not_a_timing_pass(self):
        operation = mock.Mock(side_effect=[None, RuntimeError("wrong answer")])
        self.assertEqual("FAIL", validate.measure("broken", operation)["status"])

    def test_sixel_fixture_decodes_to_the_expected_pixels(self):
        # Independent, deliberately small decoder for the emitted protocol subset.
        payload = validate.sixel_pattern()
        self.assertTrue(payload.startswith('\x1bPq"1;1;240;144'))
        self.assertTrue(payload.endswith('\x1b\\'))
        body = payload[len('\x1bPq"1;1;240;144'):-2]
        tokens = re.findall(r'#[0-9]+(?:;2;[0-9]+;[0-9]+;[0-9]+)?|![0-9]+[?-~]|[$-]|[?-~]', body)
        self.assertEqual(body, ''.join(tokens))
        pixels, palette = {}, {}
        x = y = color = 0
        for token in tokens:
            if token.startswith('#'):
                values = [int(v) for v in token[1:].split(';')]
                color = values[0]
                if len(values) > 1:
                    palette[color] = tuple(values[2:])
            elif token == '$':
                x = 0
            elif token == '-':
                x, y = 0, y + 6
            else:
                count = int(token[1:-1]) if token.startswith('!') else 1
                mask = ord(token[-1]) - 63
                for _ in range(count):
                    for bit in range(6):
                        if mask & (1 << bit):
                            pixels[x, y + bit] = palette[color]
                    x += 1
        self.assertEqual(240 * 144, len(pixels))
        bars = ((100, 0, 0), (0, 100, 0), (0, 0, 100))
        for y in range(144):
            for x in range(240):
                expected = bars[x // 80] if y < 72 else ((100,) * 3 if (x // 8 + (y - 72) // 8) % 2 else (0,) * 3)
                self.assertEqual(expected, pixels[x, y], (x, y))

    def test_sixel_redirection_does_not_emit_escape_codes_or_claim_pass(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual("SKIP", validate.sixel_check()["status"])
        self.assertEqual("", output.getvalue())

    def test_sixel_requires_explicit_visual_confirmation(self):
        for answer, expected in (("pass", "PASS"), ("fail", "FAIL"), ("", "NOT_RUN"), ("yes", "NOT_RUN")):
            with contextlib.redirect_stdout(io.StringIO()) as output, \
                    mock.patch.object(output, "isatty", return_value=True), \
                    mock.patch.object(validate.sys.stdin, "isatty", return_value=True), \
                    mock.patch("builtins.input", return_value=answer):
                self.assertEqual(expected, validate.sixel_check()["status"])

    def test_invalid_sampling_arguments_do_not_create_a_report(self):
        for option in (("--samples", "0"), ("--samples", "16"), ("--sample-seconds", "nan"),
                       ("--sample-seconds", "inf"), ("--sample-seconds", "0")):
            with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stderr(io.StringIO()):
                report = Path(directory) / "report.json"
                with self.assertRaises(SystemExit) as error:
                    validate.main([*option, "--output", str(report)])
                self.assertEqual(2, error.exception.code)
                self.assertFalse(report.exists())

    def test_visual_failure_sets_exit_code_but_stays_out_of_automated_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with mock.patch.dict(os.environ, {"TMPDIR": directory}), \
                    mock.patch.object(validate, "metadata", return_value={}), \
                    mock.patch.object(validate, "cpu_capabilities", return_value={"status": "PASS"}), \
                    mock.patch.object(validate, "power_snapshot", return_value={}), \
                    mock.patch.object(validate, "smoke_tests", return_value=[]), \
                    mock.patch.object(validate, "benchmarks", return_value=[]) as bench, \
                    mock.patch.object(validate, "sixel_check", return_value={
                        "name": "sixel rendering", "status": "FAIL", "method": "visual_confirmation"}), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, validate.main(["--bench", "--samples", "7", "--sample-seconds", "1",
                                                  "--sixel", "--output", str(report)]))
            self.assertEqual((7, 1.0), bench.call_args.args[1:])
            result = json.loads(report.read_text())
            self.assertEqual(3, result["schema_version"])
            self.assertEqual(0, result["summary"]["FAIL"])
            self.assertEqual("FAIL", result["visual_checks"][0]["status"])

    def test_command_failure_preserves_status_and_output(self):
        result = validate.run([sys.executable, "-c", "print('failure detail'); raise SystemExit(7)"])
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(7, result["exit_code"])
        self.assertEqual("failure detail", result["detail"])

    def test_timeout_is_a_failure(self):
        result = validate.run([sys.executable, "-c", "import time; time.sleep(30)"], timeout=0.1)
        self.assertEqual("FAIL", result["status"])
        self.assertIsNone(result["exit_code"])
        self.assertIn("Timed out", result["detail"])

    def test_report_output_is_bounded(self):
        result = validate.run([sys.executable, "-c", "print('x' * 10000)"])
        self.assertEqual("PASS", result["status"])
        self.assertEqual(4096, len(result["detail"]))

    def test_missing_executable_is_reported_without_crashing(self):
        result = validate.run(["/nonexistent/termux-validation-test"])
        self.assertEqual("FAIL", result["status"])

    @mock.patch.object(validate.platform, "machine", return_value="x86_64")
    def test_arm64_decoder_is_not_used_on_other_architectures(self, _):
        self.assertEqual("SKIP", validate.cpu_capabilities()["status"])

    def test_report_refuses_to_overwrite_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text("keep this")
            with self.assertRaises(FileExistsError):
                validate.main(["--output", str(report)])
            self.assertEqual("keep this", report.read_text())

    def test_report_keeps_skipped_and_manual_checks_distinct_and_cleans_temp_files(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with mock.patch.dict(os.environ, {"TMPDIR": directory}), \
                    mock.patch.object(validate, "metadata", return_value={}), \
                    mock.patch.object(validate, "cpu_capabilities", return_value={"status": "PASS"}), \
                    mock.patch.object(validate, "power_snapshot", return_value={}), \
                    mock.patch.object(validate, "smoke_tests", return_value=[
                        {"name": "required", "status": "FAIL"},
                        {"name": "optional", "status": "SKIP"}]), \
                    mock.patch.object(validate, "benchmarks") as bench, \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, validate.main(["--output", str(report)]))
                bench.assert_not_called()
            result = json.loads(report.read_text())
            self.assertEqual({"PASS": 0, "FAIL": 1, "SKIP": 3}, result["summary"])
            self.assertTrue(all(x["status"] == "NOT_RUN" for x in result["manual_checks"]))
            self.assertEqual([report], list(Path(directory).iterdir()))
            self.assertEqual(0o600, report.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
