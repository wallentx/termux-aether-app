import os
import unittest
from unittest import mock

import native_package_audit as audit


class AuditTests(unittest.TestCase):
    def test_dispatch_overrides_are_child_only_and_preserve_exec_environment(self):
        values = {'JSIMD_FORCENONE': '1', 'JSIMD_NOHUFFENC': '1',
                  'LIBDEFLATE_DISABLE_CPU_FEATURES': 'neon', 'LD_PRELOAD': 'exec.so'}
        with mock.patch.dict(os.environ, values, clear=True):
            normal = audit.child_environment('jpeg', 'default')
            disabled = audit.child_environment('jpeg', 'disabled')
            self.assertEqual(dict(os.environ), values)
        self.assertEqual(normal, {'LD_PRELOAD': 'exec.so'})
        self.assertEqual(disabled, {'LD_PRELOAD': 'exec.so', 'JSIMD_FORCENONE': '1'})

    def test_jpeg_rejects_changed_input_encoded_or_decoded_pixels(self):
        reference = {'input_sha256': 'input', 'encoded_sha256': 'jpeg',
                     'decoded_sha256': 'pixels'}
        audit.validate_pair([reference, dict(reference)], 'jpeg')
        for key in reference:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                audit.validate_pair([reference, dict(reference, **{key: 'different'})], 'jpeg')

    def test_compression_requires_the_same_decode_workload_and_roundtrip(self):
        reference = {'input_sha256': 'input', 'decode_input_sha256': 'packed', 'roundtrip': True}
        audit.validate_pair([reference, dict(reference)], 'records')
        for key, value in [('input_sha256', 'different'),
                           ('decode_input_sha256', 'different'), ('roundtrip', False)]:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                audit.validate_pair([reference, dict(reference, **{key: value})], 'records')

    def test_failed_measurement_never_returns_a_throughput(self):
        with self.assertRaisesRegex(RuntimeError, 'failed'):
            audit.measure(mock.Mock(side_effect=RuntimeError('failed')), 0.1, 1024)

    def test_corpora_are_distinct_and_reproducible(self):
        for kind in ('records', 'random'):
            self.assertEqual(len(audit.corpus(kind)), 2**20)
            self.assertEqual(audit.corpus(kind), audit.corpus(kind))
        self.assertNotEqual(audit.corpus('records'), audit.corpus('random'))


if __name__ == '__main__':
    unittest.main()
