"""Collector regressions without a device or wall-clock delays."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('capture', Path(__file__).with_name('capture.py'))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureTest(unittest.TestCase):
    def run_capture(self, metadata, empty_frames=False, delayed_start=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        output = Path(directory.name) / 'report'
        states = ('ready', 'running', 'done') if delayed_start else ('ready', 'done')
        phases = iter((name, state) for name in ('text_scroll', 'image_redraw', 'image_replace')
                      for state in states)
        now = 0
        samples = 0

        def monotonic():
            nonlocal now
            now += 1
            return now

        def adb(args, **kwargs):
            nonlocal now, samples
            command = args[-1]
            if 'gfxinfo com.termux reset' in command:
                samples = 0
            if 'state.json' in command:
                phase, state = next(phases)
                if delayed_start:
                    # Ready within the documented window, then a 120-second phase.
                    now += 100 if state == 'ready' else 60
                return json.dumps({'phase': phase, 'state': state})
            if 'framestats' in command:
                if empty_frames:
                    return ''
                samples += 1
                timestamp = samples * 100
                return ('---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n'
                        f'0,{timestamp},{timestamp + 50}\n---PROFILEDATA---')
            if 'result.json' in command:
                return metadata
            return ''

        with patch.object(capture.subprocess, 'check_output', side_effect=adb), \
             patch.object(capture.time, 'sleep'), \
             patch.object(capture.time, 'monotonic', side_effect=monotonic), \
             patch('sys.argv', ['capture.py', '--serial', 'fake', '--remote-dir', '/run',
                                '--output', str(output)]):
            if empty_frames:
                with self.assertRaisesRegex(RuntimeError, 'no frames for text_scroll'):
                    capture.main()
                self.assertFalse((output / 'summary.json').exists())
                self.assertTrue((output / 'workload.json').exists())
            elif metadata == 'incomplete':
                with self.assertRaisesRegex(TimeoutError, 'result.json'):
                    capture.main()
                self.assertFalse((output / 'summary.json').exists())
            else:
                capture.main()
                for phase in ('text_scroll', 'image_redraw', 'image_replace'):
                    frames = json.loads((output / (phase + '-frames.json')).read_text())
                    expected = [200, 300, 400] if delayed_start else [200, 300]
                    self.assertEqual(expected, [frame['IntendedVsync'] for frame in frames])
                self.assertEqual({'complete': True}, json.loads((output / 'workload.json').read_text()))

    def test_collects_tail_after_done(self):
        self.run_capture('{"complete": true}')

    def test_incomplete_metadata_fails_run(self):
        self.run_capture('incomplete')

    def test_hidden_display_fails_run(self):
        self.run_capture('{"complete": true}', empty_frames=True)

    def test_late_ready_allows_full_length_phase(self):
        self.run_capture('{"complete": true}', delayed_start=True)


    def test_display_rejects_focus_loss_lock_and_rotation(self):
        visible = ('isKeyguardShowing=false\n'
                   'mFocusedWindow=Window{123 u0 com.termux/com.termux.app.TermuxActivity}\n'
                   'mRotation=0 mDeferredRotationPauseCount=0')
        self.assertEqual(0, capture.validate_display(visible))
        for state in (visible.replace('com.termux/', 'other.app/'),
                      visible.replace('Showing=false', 'Showing=true'), '',
                      visible.replace('mRotation=0', 'mRotation=1')):
            with self.assertRaises(RuntimeError):
                capture.validate_display(state, 0)

    def test_deadlines_use_each_frame_budget(self):
        rows = {
            100: {'IntendedVsync': 100, 'FrameCompleted': 120, 'FrameDeadline': 120,
                  'FrameInterval': 16666667},
            200: {'IntendedVsync': 200, 'FrameCompleted': 221, 'FrameDeadline': 220,
                  'FrameInterval': 8333333},
            300: {'IntendedVsync': 300, 'FrameCompleted': 350},
            400: {'IntendedVsync': 400, 'FrameCompleted': 2**63 - 1, 'FrameDeadline': 450},
        }
        result = capture.summarize_frames('test', rows)
        self.assertEqual(2, result['sampled_deadlines'])
        self.assertEqual(1, result['sampled_deadline_misses'])
        self.assertEqual(50, result['sampled_deadline_miss_percent'])
        self.assertEqual([8.333, 16.667], result['frame_intervals_ms'])

    def test_local_shell_uses_complete_files_and_cleans_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            transfer = Path(tmp) / 'transfer'
            transfer.mkdir()
            def run(args, **kwargs):
                (transfer / 'out').write_text('complete output\n')
                (transfer / 'err').write_text('failure detail')
                (transfer / 'status').write_text('0')
            with patch.object(capture.tempfile, 'TemporaryDirectory') as directory, \
                 patch.object(capture.subprocess, 'run', side_effect=run):
                directory.return_value.__enter__.return_value = str(transfer)
                self.assertEqual('complete output\n', capture.local_shell('id'))
                directory.return_value.__exit__.assert_called_once()

    def test_local_shell_preserves_remote_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            transfer = Path(tmp)
            for name, data in [('out', ''), ('err', 'denied'), ('status', '2')]:
                (transfer / name).write_text(data)
            with patch.object(capture.tempfile, 'TemporaryDirectory') as directory, \
                 patch.object(capture.subprocess, 'run'):
                directory.return_value.__enter__.return_value = str(transfer)
                with self.assertRaises(capture.subprocess.CalledProcessError) as error:
                    capture.local_shell('input --help')
                self.assertEqual(2, error.exception.returncode)
                self.assertEqual('denied', error.exception.stderr)

    def test_unsupported_input_fails_before_creating_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'report'
            with patch.object(capture, 'local_shell', side_effect=
                              capture.subprocess.CalledProcessError(2, 'input --help', '', 'denied')), \
                 patch('sys.argv', ['capture.py', '--local-rish', '--input-phase',
                                    '--remote-dir', '/unused', '--output', str(output)]):
                with self.assertRaises(SystemExit) as error:
                    capture.main()
                self.assertEqual(2, error.exception.code)
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
