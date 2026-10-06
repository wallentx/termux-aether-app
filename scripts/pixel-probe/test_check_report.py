import copy
import unittest

from check_report import problems


def fixture():
    def result(name, code=0, output=''):
        return dict(name=name, exit_code=code, output=output, signal=0,
                    timed_out=False, truncated=False, wait_errno=0, read_errno=0)
    return dict(schema_version=1, package='com.termux', uid=10123, sdk=37,
                target_sdk=37, is_64_bit=True,
                cpu=dict(architecture='aarch64', hwcap='0x2', hwcap2='0x0',
                         hwcap_errno=0, hwcap2_errno=0,
                         features=dict(asimd=True, sve=False, sve2=False, sme=False,
                                       sme2=False, i8mm=False, bf16=False),
                         sve=dict(query_errno=0, vector_length_bytes=None),
                         sme=dict(query_errno=0, vector_length_bytes=None)),
                bootstrap_shell_present=False, termux_exec_present=False,
                execution=[result('system_control'), result('private_linker'),
                           result('private_direct', 1, 'exec: Permission denied')])


class ReportTests(unittest.TestCase):
    def test_baseline_needs_no_optional_simd(self):
        self.assertEqual([], problems(fixture()))

    def test_missing_bootstrap_cannot_pass_full_gate(self):
        self.assertTrue(problems(fixture(), require_bootstrap=True))

    def test_full_gate_requires_real_child_output(self):
        report = fixture()
        report.update(bootstrap_shell_present=True, termux_exec_present=True)
        for name, output in [('bootstrap_linker', 'pixel-shell-ok\r\n'),
                             ('bootstrap_preload_child', 'pixel-child-ok\r\n')]:
            row = copy.deepcopy(report['execution'][0])
            row.update(name=name, output=output)
            report['execution'].append(row)
        self.assertEqual([], problems(report, True))
        report['execution'][-1]['output'] = ''
        self.assertTrue(problems(report, True))

    def test_shell_uid_is_not_app_evidence(self):
        report = fixture()
        report['uid'] = 2000
        self.assertTrue(problems(report))

    def test_legacy_target_is_rejected(self):
        report = fixture()
        report['target_sdk'] = 28
        self.assertTrue(problems(report))

    def test_advertised_sve_requires_valid_vector_length(self):
        report = fixture()
        report['cpu']['features']['sve'] = True
        self.assertTrue(problems(report))
        report['cpu']['sve']['vector_length_bytes'] = 32
        self.assertEqual([], problems(report))
        report['cpu']['sve']['query_errno'] = 22
        self.assertTrue(problems(report))

    def test_timeout_and_signal_are_not_success(self):
        for changes in [dict(timed_out=True), dict(signal=9), dict(truncated=True),
                        dict(wait_errno=10), dict(read_errno=5), dict(exit_code=None)]:
            report = fixture()
            report['execution'][1].update(changes)
            with self.subTest(changes=changes):
                self.assertTrue(problems(report))

    def test_missing_and_duplicate_rows_fail(self):
        report = fixture()
        report['execution'].append(copy.deepcopy(report['execution'][0]))
        self.assertTrue(problems(report))
        report['execution'] = []
        self.assertTrue(problems(report))

    def test_malformed_reports_fail(self):
        for value in [None, [], {}, dict(cpu=None), dict(cpu={}),
                      dict(cpu=dict(features=[]))]:
            with self.subTest(value=value):
                self.assertTrue(problems(value))

    def test_direct_execution_success_is_not_a_denial(self):
        report = fixture()
        report['execution'][2]['exit_code'] = 0
        self.assertTrue(problems(report))


if __name__ == '__main__':
    unittest.main()
