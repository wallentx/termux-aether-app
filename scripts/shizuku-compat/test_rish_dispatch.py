"""Exercise the actual bundled launcher without contacting either manager."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHELL = "/system/bin/sh" if Path("/system/bin/sh").exists() else "/bin/sh"


class RishDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rish dispatch ", dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.prefix = Path(self.temp.name)
        self.directory = self.prefix / "libexec/termux-rish"
        self.directory.mkdir(parents=True)
        for name in ("rish_shizuku.dex", "rish_shizuku_plus.dex"):
            path = self.directory / name
            path.write_bytes(b"fixture")
            path.chmod(0o400)
        fake = self.prefix / "capture-app-process"
        fake.write_text("#!" + SHELL + "\nprintf '%s\\n' \"$RISH_APPLICATION_ID\" \"$RISH_PRESERVE_ENV\" \"${LD_LIBRARY_PATH+set}\"\nprintf '%s\\0' \"$@\"\n")
        fake.chmod(0o700)
        source = (ROOT / "app/src/main/assets/rish/termux-rish").read_text()
        source = source.replace("@PREFIX@", str(self.prefix)).replace("@PACKAGE@", "com.termux")
        source = source.replace("/system/bin/app_process", shlex.quote(str(fake)))
        self.launcher = self.prefix / "launcher"
        self.launcher.write_text(source)

    def run_launcher(self, *arguments, backend=None):
        env = dict(os.environ)
        env.pop("RISH_BACKEND", None)
        env.pop("RISH_PRESERVE_ENV", None)
        if backend is not None:
            env["RISH_BACKEND"] = backend
        return subprocess.run([SHELL, str(self.launcher), *arguments],
                              env=env, capture_output=True, timeout=5)

    def assert_loader(self, result, filename):
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        package, preserve, library_path, arguments = result.stdout.split(b"\n", 3)
        self.assertEqual((package, preserve, library_path), (b"com.termux", b"0", b""))
        arguments = arguments.rstrip(b"\0").decode().split("\0")
        self.assertEqual(arguments[0], "-Djava.class.path=" + str(self.directory / filename))
        return arguments

    def test_stock_is_default(self):
        self.assert_loader(self.run_launcher(), "rish_shizuku.dex")

    def test_plus_receipt_selects_plus(self):
        (self.directory / "backend").write_text("plus\n")
        self.assert_loader(self.run_launcher(), "rish_shizuku_plus.dex")

    def test_explicit_stock_override_preserves_both_manager_choice(self):
        (self.directory / "backend").write_text("plus\n")
        self.assert_loader(self.run_launcher(backend="stock"), "rish_shizuku.dex")

    def test_arguments_are_forwarded_without_shell_evaluation(self):
        arguments = ["-c", "printf '%s' '$(id) ; quoted text'"]
        actual = self.assert_loader(self.run_launcher(*arguments, backend="plus"), "rish_shizuku_plus.dex")
        self.assertEqual(actual[-2:], arguments)

    def test_writable_plus_dex_is_rejected(self):
        (self.directory / "rish_shizuku_plus.dex").chmod(0o600)
        result = self.run_launcher(backend="plus")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")

    def test_unknown_backend_is_rejected_without_running_a_command(self):
        result = self.run_launcher(backend="unexpected")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")

    def test_missing_selected_dex_does_not_retry_with_another_manager(self):
        (self.directory / "rish_shizuku_plus.dex").unlink()
        result = self.run_launcher(backend="plus")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")


if __name__ == "__main__":
    unittest.main()
