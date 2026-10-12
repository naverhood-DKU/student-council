import json
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest


class RuntimeRedeployTest(unittest.TestCase):
    def test_installer_reloads_running_process_after_copying_new_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            staging = work / 'staging'
            staging.mkdir()
            for name in ('app.jar', 'runtime.env', 'council-flow.service'):
                (staging / name).write_text('new deployment')
            state = work / 'state.json'
            state.write_text(json.dumps({'active': True, 'installed': 'old', 'running': 'old'}))
            stub = work / 'command'
            stub.write_text('#!' + sys.executable + '\n' + '''import json, os, sys
from pathlib import Path
p=Path(os.environ['FAKE_SYSTEM_STATE'])
s=json.loads(p.read_text()); name=Path(sys.argv[0]).name; args=sys.argv[1:]
if name=='install' and args[-1]=='/opt/council-flow/app.jar': s['installed']='new'
if name=='systemctl':
    if args[0]=='restart' or (args[0]=='enable' and '--now' in args and not s['active']):
        s['active']=True; s['running']=s['installed']
    if args[0]=='is-active' and not s['active']: sys.exit(3)
p.write_text(json.dumps(s))
''')
            stub.chmod(0o755)
            for command in ('java', 'id', 'useradd', 'install', 'rm', 'systemctl', 'systemd-analyze'):
                (work / command).symlink_to(stub)
            source = Path(__file__).resolve().parents[1] / 'install-runtime.sh'
            script = work / 'installer.sh'
            script.write_text(source.read_text().replace('/usr/lib/jvm/jre-21-openjdk/bin/java', str(work / 'java')))
            env = {**os.environ, 'PATH': str(work), 'FAKE_SYSTEM_STATE': str(state)}
            subprocess.run(['/bin/bash', str(script), str(staging)], env=env, check=True)
            self.assertEqual(json.loads(state.read_text())['running'], 'new',
                             'An already running JVM must restart after jar/env replacement')


if __name__ == '__main__':
    unittest.main()
