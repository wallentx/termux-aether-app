import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install.sh')


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'pacman.log'
        sh = shutil.which('sh')
        mock = self.bin / 'pacman'
        mock.write_text(f'#!{sh}\nprintf "%s\\n" "$*" >> "$TEST_LOG"\ncase "$1" in -U) exit 99;; esac\n')
        mock.chmod(0o700)
        uname = self.bin / 'uname'
        uname.write_text(f'#!{sh}\necho aarch64\n')
        uname.chmod(0o700)
        for name in ['termux-aether-exec-1000.0.0-1-aarch64.pkg.tar.zst',
                     'termux-aether-api-1000.0.0-1-aarch64.pkg.tar.xz']:
            (self.root / name).write_bytes(b'fixture')
        self.env = dict(os.environ, PREFIX='/data/data/com.termux/files/usr',
                        PATH=str(self.bin) + ':' + os.environ['PATH'], TEST_LOG=str(self.log),
                        HOME=str(self.root / 'home'))
        shutil.copy2(SCRIPT, self.root / 'install.sh')
        self.checksums()

    def checksums(self):
        (self.root / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n'
            for p in self.root.iterdir() if p.is_file() and p.name != 'SHA256SUMS'))

    def run_script(self, *args):
        return subprocess.run(['sh', str(self.root / 'install.sh'), *args], env=self.env,
                              capture_output=True, text=True)

    def test_check_never_installs_or_creates_recovery_state(self):
        result = self.run_script('--check')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('-U', self.log.read_text())
        self.assertFalse((self.root / 'home').exists())

    def test_tamper_stops_before_pacman(self):
        next(self.root.glob('*.zst')).write_bytes(b'tampered')
        self.assertNotEqual(self.run_script('--check').returncode, 0)
        self.assertFalse(self.log.exists())

    def test_missing_package_stops_before_install(self):
        next(self.root.glob('*.zst')).unlink()
        self.checksums()
        self.assertNotEqual(self.run_script('--check').returncode, 0)
        self.assertFalse(self.log.exists())

    @unittest.skipUnless(Path('/data/data/com.termux/files/usr/lib/libtermux-exec-ld-preload.so').exists(), 'native preload test')
    def test_failed_transaction_keeps_external_preload_and_backup(self):
        import tarfile
        for package in list(self.root.glob('*.zst')) + list(self.root.glob('*.xz')):
            with tarfile.open(package, 'w:xz'):
                pass
        shutil.copy2('/data/data/com.termux/files/usr/lib/libtermux-exec-ld-preload.so',
                     self.root / 'recovery-preload.so')
        self.checksums()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('-U --needed', self.log.read_text(), result.stderr)
        backups = list((self.root / 'home/.local/state/termux-aether').glob('upgrade-*'))
        self.assertEqual(len(backups), 1)
        self.assertTrue((backups[0] / 'preload.so').is_file())
        self.assertTrue((backups[0] / 'files-before.tar').is_file())
        self.assertIn('Recovery files remain', result.stderr)

    def test_wrong_prefix_is_refused(self):
        self.env['PREFIX'] = '/different/prefix'
        self.assertNotEqual(self.run_script('--check').returncode, 0)
        self.assertFalse(self.log.exists())

    def test_unknown_argument_is_refused(self):
        self.assertEqual(self.run_script('--force').returncode, 2)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()
