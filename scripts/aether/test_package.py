"""Packaging regressions: no compilation, downloads, or release publication."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('verify_package', Path(__file__).with_name('verify-package.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.apk = self.root / 'app.apk'
        self.archive = self.root / 'source.tar.gz'
        self.sources = {}
        for name in ('launcher.c', 'compat.c', 'exec.c', 'system.c', 'probe.c', 'build.sh', 'source-bundle.sh', 'README.md'):
            data = ('fixture ' + name).encode()
            self.sources[name] = data
            self.write('scripts/aether/' + name, data)
        for name in ('termux-app-source.tar.gz', 'glibc-2.44.tar.xz', 'termux-glibc-recipes-c2b00b9e.tar.gz', 'COPYING.LIB', 'LICENSES'):
            self.sources[name] = ('fixture ' + name).encode()
        provenance = {'libc.so.6': package.digest(b'libc'),
                      'ld-linux-aarch64.so.1': package.digest(b'loader'),
                      'source_sha256': package.digest(self.sources['glibc-2.44.tar.xz'])}
        self.sources['provenance.json'] = json.dumps(provenance).encode()
        self.entries = {'assets/aether/libc.so.6': b'libc',
                        'lib/arm64-v8a/libaether-loader.so': b'loader'}
        for name in ('provenance.json', 'COPYING.LIB', 'LICENSES'):
            self.write('app/src/aether/assets/aether/' + name, self.sources[name])
            self.entries['assets/aether/' + name] = self.sources[name]
        for name in ('libaether-run.so', 'libaether-exec.so'):
            self.entries['lib/arm64-v8a/' + name] = b'\x7fELF' + name.encode()
        for entry, generated in [('libaether-compat.so', 'assets/aether/libaether-compat.so'),
                                 ('aether-probe', 'probes/aether-probe')]:
            data = b'\x7fELF' + entry.encode()
            self.entries['assets/aether/' + entry] = data
            self.write('app/build/generated/aether/' + generated, data)
        self.pack_apk()
        self.pack_sources()

    def write(self, name, data):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def pack_apk(self):
        with zipfile.ZipFile(self.apk, 'w') as z:
            for name, data in self.entries.items():
                z.writestr(name, data)

    def pack_sources(self, commit='fixture-commit'):
        members = dict(self.sources)
        members['manifest.json'] = json.dumps({'source_commit': commit,
            'sha256': {k: hashlib.sha256(v).hexdigest() for k, v in members.items()}}).encode()
        with tarfile.open(self.archive, 'w:gz') as t:
            for name, data in members.items():
                info = tarfile.TarInfo('./' + name)
                info.size = len(data)
                t.addfile(info, io.BytesIO(data))

    def verify(self, enabled=True):
        with patch.object(package.subprocess, 'check_output', return_value='fixture-commit\n'):
            package.verify(self.apk, enabled, self.archive, self.root)

    def rewrite_sources(self, transform):
        with tarfile.open(self.archive, 'r:gz') as archive:
            members = [(member, archive.extractfile(member).read()) for member in archive]
        with tarfile.open(self.archive, 'w:gz') as archive:
            for member, data in transform(members):
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))

    def test_enabled_payload_and_matching_sources(self):
        self.verify()

    def test_streaming_does_not_seek_even_with_manifest_last(self):
        with patch.object(package.gzip.GzipFile, 'seek', side_effect=AssertionError('backward scan')):
            self.verify()

    def test_manifest_first_also_passes(self):
        self.rewrite_sources(lambda members: members[-1:] + members[:-1])
        self.verify()

    def test_large_sources_are_read_in_bounded_chunks(self):
        self.sources['extra-source'] = b'x' * (3 * 1024 * 1024)
        self.pack_sources()
        sizes = []
        original = tarfile.ExFileObject.read

        def read(stream, size=-1):
            sizes.append(size)
            return original(stream, size)

        with patch.object(tarfile.ExFileObject, 'read', read):
            self.verify()
        self.assertEqual(sizes.count(-1), 1)  # Only the small JSON manifest.
        self.assertLessEqual(max(sizes), 1024 * 1024)

    def test_missing_manifest_rejected(self):
        self.rewrite_sources(lambda members: members[:-1])
        with self.assertRaisesRegex(ValueError, 'Missing source manifest'):
            self.verify()

    def test_glibc_source_must_match_runtime_provenance(self):
        self.sources['glibc-2.44.tar.xz'] = b'another upstream source'
        self.pack_sources()
        with self.assertRaisesRegex(ValueError, 'Wrong upstream glibc source'):
            self.verify()

    def test_source_provenance_must_match_apk(self):
        self.sources['provenance.json'] += b'\n'
        self.pack_sources()
        with self.assertRaisesRegex(ValueError, 'Source provenance differs from APK'):
            self.verify()

    def test_changed_member_rejected_without_updating_manifest(self):
        self.rewrite_sources(lambda members: [(m, b'corrupt' if m.name == './exec.c' else d)
                                               for m, d in members])
        with self.assertRaisesRegex(ValueError, 'hash mismatch: exec.c'):
            self.verify()

    def test_missing_required_member_rejected(self):
        self.rewrite_sources(lambda members: [(m, d) for m, d in members if m.name != './exec.c'])
        with self.assertRaisesRegex(ValueError, 'Incomplete source archive'):
            self.verify()

    def test_duplicate_member_and_manifest_rejected(self):
        for name in ('./exec.c', './manifest.json'):
            with self.subTest(name=name):
                self.pack_sources()
                self.rewrite_sources(lambda members: members + [(m, d) for m, d in members if m.name == name])
                with self.assertRaisesRegex(ValueError, 'Duplicate source entry'):
                    self.verify()

    def test_symlink_source_rejected(self):
        def symlink(members):
            for member, data in members:
                if member.name == './exec.c':
                    member.type = tarfile.SYMTYPE
                    member.linkname = './compat.c'
                    data = b''
                yield member, data
        self.rewrite_sources(symlink)
        with self.assertRaisesRegex(ValueError, 'not a regular file: exec.c'):
            self.verify()

    def test_gzip_crc_and_truncated_trailer_rejected(self):
        original = self.archive.read_bytes()
        bad_crc = bytearray(original)
        bad_crc[-8] ^= 1
        for data in (bad_crc, original[:-4]):
            with self.subTest(length=len(data)):
                self.archive.write_bytes(data)
                with self.assertRaises((package.gzip.BadGzipFile, EOFError)):
                    self.verify()

    def test_missing_sources_rejected(self):
        self.archive.unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_missing_bundled_probe_rejected(self):
        del self.entries['assets/aether/aether-probe']
        self.pack_apk()
        with self.assertRaises(KeyError):
            self.verify()

    def test_different_commit_rejected(self):
        self.pack_sources(commit='older-commit')
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.verify()

    def test_stale_compatibility_source_rejected(self):
        self.sources['exec.c'] = b'older code'
        self.pack_sources()
        with self.assertRaisesRegex(ValueError, 'differs from checkout'):
            self.verify()

    def test_mismatched_generated_probe_rejected(self):
        self.entries['assets/aether/aether-probe'] = b'\x7fELFolder probe'
        self.pack_apk()
        with self.assertRaisesRegex(ValueError, 'Generated payload mismatch'):
            self.verify()

    def test_disabled_payload_rejected(self):
        with self.assertRaisesRegex(ValueError, 'opted-out'):
            self.verify(enabled=False)

    def test_opt_out_needs_no_sources(self):
        self.entries = {'assets/normal': b'normal APK'}
        self.pack_apk()
        self.archive.unlink()
        self.verify(enabled=False)


if __name__ == '__main__':
    unittest.main()
