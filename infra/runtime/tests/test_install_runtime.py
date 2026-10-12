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
if name=='java': print('openjdk version \"' + os.environ.get('FAKE_JAVA_VERSION','21.0.8') + '\"'); sys.exit(0)
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
            script.write_text(source.read_text().replace('/usr/bin/java', str(work / 'java')).replace('/usr/lib/jvm/jre-21-openjdk/bin/java', str(work / 'java')))
            (work / 'grep').symlink_to('/usr/bin/grep')
            env = {**os.environ, 'PATH': str(work), 'FAKE_SYSTEM_STATE': str(state)}
            subprocess.run(['/bin/bash', str(script), str(staging)], env=env, check=True)
            self.assertEqual(json.loads(state.read_text())['running'], 'new',
                             'An already running JVM must restart after jar/env replacement')


    def test_service_and_installer_use_distribution_independent_java(self):
        runtime = Path(__file__).resolve().parents[1]
        self.assertIn('ExecStart=/usr/bin/java ', (runtime / 'council-flow.service').read_text())
        self.assertIn('/usr/bin/java -version', (runtime / 'install-runtime.sh').read_text())

    def test_java_17_is_rejected_before_installing_artifacts(self):
        source = Path(__file__).resolve().parents[1] / 'install-runtime.sh'
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            for name in ('app.jar','runtime.env','council-flow.service'):
                (work / name).write_text('new deployment')
            java = work / 'java'
            java.write_text("#!/bin/bash\necho 'openjdk version \"17.0.1\"' >&2\n")
            java.chmod(0o755)
            installed = work / 'installed'
            stub = work / 'command'
            stub.write_text('#!/bin/bash\ntouch "' + str(installed) + '"\n')
            stub.chmod(0o755)
            for name in ('id','useradd','install','rm','systemd-analyze','systemctl'):
                (work / name).symlink_to(stub)
            script = work / 'installer.sh'
            script.write_text(source.read_text().replace('/usr/bin/java',str(java)).replace('/usr/lib/jvm/jre-21-openjdk/bin/java',str(java)))
            result = subprocess.run(['/bin/bash',str(script),str(work)], env={**os.environ,'PATH':str(work)+':/usr/bin:/bin'}, capture_output=True)
            self.assertNotEqual(result.returncode,0,'Wrong Java must fail before modifying the running deployment')
            self.assertFalse(installed.exists(),'Wrong Java must not install artifacts or restart service')

if __name__ == '__main__':
    unittest.main()
