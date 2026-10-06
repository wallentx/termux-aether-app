import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('aether_install', Path(__file__).with_name('aether-install.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class AdoptionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.prefix = self.root / 'usr'
        (self.prefix / 'bin').mkdir(parents=True)
        self.legacy = self.prefix / 'bin/termux-arch'
        self.legacy.write_text('old user wrapper')
        self.names = [str(self.legacy).lstrip('/')]
        self.recovery = self.root / 'backup'
        self.recovery.mkdir()

    def test_only_allowlisted_unowned_commands_are_adopted(self):
        existing, legacy, blocked = installer.conflicts(self.names, {}, self.prefix)
        self.assertEqual(legacy, [self.legacy])
        self.assertEqual(blocked, [])
        self.assertEqual(existing, [self.legacy])

    def test_known_name_owned_by_unrelated_package_is_not_adopted(self):
        _, legacy, blocked = installer.conflicts(self.names, {self.names[0]: 'user-tools'}, self.prefix)
        self.assertEqual(legacy, [])
        self.assertEqual(len(blocked), 1)

    def test_unknown_unowned_command_is_not_adopted(self):
        unknown = self.prefix / 'bin/custom'
        unknown.write_text('keep')
        _, legacy, blocked = installer.conflicts([str(unknown).lstrip('/')], {}, self.prefix)
        self.assertEqual(legacy, [])
        self.assertEqual(len(blocked), 1)

    def test_package_owned_files_need_no_adoption(self):
        for package in installer.REPLACED:
            _, legacy, blocked = installer.conflicts(self.names, {self.names[0]: package}, self.prefix)
            self.assertEqual((legacy, blocked), ([], []))

    def test_failed_transaction_restores_unowned_command(self):
        with patch.object(installer.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'pacman')):
            with self.assertRaises(subprocess.CalledProcessError):
                installer.install_transaction([self.legacy], self.recovery, Path('suite'), {})
        self.assertEqual(self.legacy.read_text(), 'old user wrapper')

    def test_interrupted_transaction_preserves_newly_installed_file(self):
        def interrupted(*args, **kwargs):
            self.legacy.write_text('new package file')
            raise KeyboardInterrupt()
        with patch.object(installer.subprocess, 'run', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                installer.install_transaction([self.legacy], self.recovery, Path('suite'), {})
        self.assertEqual(self.legacy.read_text(), 'new package file')
        self.assertEqual((self.recovery / 'legacy-termux-arch').read_text(), 'old user wrapper')

    def test_success_keeps_backup_and_does_not_pass_overwrite(self):
        with patch.object(installer.subprocess, 'run') as run:
            installer.install_transaction([self.legacy], self.recovery, Path('suite'), {'LD_PRELOAD': 'recovery'})
        self.assertFalse(self.legacy.exists())
        self.assertTrue((self.recovery / 'legacy-termux-arch').exists())
        run.assert_called_once_with(['pacman', '-U', '--needed', 'suite'], env={'LD_PRELOAD': 'recovery'}, check=True)

    def test_corrupt_checksum_is_rejected(self):
        package = self.root / 'suite.pkg.tar.xz'
        package.write_bytes(b'broken')
        Path(str(package) + '.sha256').write_text('0' * 64 + '  suite.pkg.tar.xz\n')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            installer.checksum(package)

    def test_path_traversal_is_rejected(self):
        package = self.root / 'suite.pkg.tar.xz'
        with tarfile.open(package, 'w:xz') as archive:
            for name, data in [('.PKGINFO', b'pkgname = termux-aether-suite\narch = aarch64\n'),
                               ('data/data/com.termux/files/usr/../../escape', b'no')]:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        with self.assertRaisesRegex(ValueError, 'Unexpected package path'):
            installer.inspect(package)


if __name__ == '__main__':
    unittest.main()
