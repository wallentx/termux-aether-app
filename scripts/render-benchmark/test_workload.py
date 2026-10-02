"""Exercise terminal restoration and key receipt through a real local PTY."""
import fcntl
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time
import unittest


class WorkloadTest(unittest.TestCase):
    def run_workload(self, abort=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'workload'
            master, slave = os.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
            original = termios.tcgetattr(slave)
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('workload.py')),
                                        str(root), '--seconds', '0.2', '--input-phase'],
                                       stdin=slave, stdout=slave, stderr=slave)
            output = bytearray()
            def drain():
                try:
                    while True:
                        data = os.read(master, 65536)
                        if not data:
                            return
                        output.extend(data)
                except OSError:
                    pass
            reader = threading.Thread(target=drain, daemon=True)
            reader.start()
            try:
                deadline = time.monotonic() + 15
                sent = False
                while process.poll() is None:
                    if time.monotonic() > deadline:
                        self.fail('Workload did not finish')
                    try:
                        state = json.loads((root / 'state.json').read_text())
                    except (OSError, ValueError):
                        time.sleep(.01)
                        continue
                    if abort:
                        (root / 'abort').touch()
                    elif state['state'] == 'ready':
                        (root / (state['phase'] + '.go')).touch()
                        if state['phase'] == 'input_echo' and not sent:
                            os.write(master, b'aaa')
                            sent = True
                    elif state['state'] == 'done':
                        (root / (state['phase'] + '.ack')).touch()
                    time.sleep(.01)
                self.assertEqual(original, termios.tcgetattr(slave))
                self.assertIn(b'\x1b[?1049l', output)
                if abort:
                    self.assertNotEqual(0, process.returncode)
                    self.assertFalse((root / 'result.json').exists())
                else:
                    self.assertEqual(0, process.returncode, output.decode(errors='replace'))
                    result = json.loads((root / 'result.json').read_text())
                    self.assertEqual(3, len(result['input_received_ns']))
                    self.assertEqual(4, len(result['results']))
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
                os.close(slave)
                reader.join(timeout=1)
                os.close(master)

    def test_keys_received_and_terminal_restored(self):
        self.run_workload()

    def test_collector_abort_restores_terminal(self):
        self.run_workload(abort=True)


if __name__ == '__main__':
    unittest.main()
