"""在临时目录使用工具替身验证子工程独立启动，不访问NAS。"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class ShellLauncherTests(unittest.TestCase):
    def test_java_launcher_needs_no_python_or_parent_repository(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder) / 'java project'
            (project / 'scripts').mkdir(parents=True)
            for name in ('start-api.sh', 'start-worker.sh', 'start-java.sh'):
                source = ROOT / 'backend/scripts' / name
                self.assertTrue(source.is_file(), f'缺少{name}')
                shutil.copy(source, project / 'scripts' / name)
            tools = Path(folder) / 'jdk/bin'
            tools.mkdir(parents=True)
            java = tools / 'java'
            java.write_text('#!/bin/sh\nif [ "$1" = -version ]; then printf \'openjdk version "27.0.1"\\n\' >&2; exit 0; fi\nprintf "%s\\n" "$PWD" "$@"\nexit 7\n')
            java.chmod(0o755)
            maven = project / 'mvnw'
            maven.write_text('#!/bin/sh\nexit 0\n')
            maven.chmod(0o755)
            for module, service in (('api-java', 'api'), ('worker-java', 'worker')):
                target = project / module / 'target'
                target.mkdir(parents=True)
                (target / f'{module}-0.1.0.jar').touch()
                (project / f'.env.{service}.properties').write_text('spring.flyway.enabled=false\n')
                env = {**os.environ, 'QUEST_JAVA_HOME': str(tools.parent)}
                run = subprocess.run(['bash', str(project / f'scripts/start-{service}.sh')],
                                     cwd='/', env=env, capture_output=True, text=True)
                self.assertEqual(run.returncode, 7, run.stderr)
                self.assertIn(str(project), run.stdout.splitlines())
                self.assertIn(str(target / f'{module}-0.1.0.jar'), run.stdout)
                self.assertNotIn('python', run.stdout)

    def test_python_startup_is_self_contained(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder) / 'python project'
            project.mkdir()
            source = ROOT / 'backend/ai-python/start.sh'
            self.assertTrue(source.is_file())
            shutil.copy(source, project / 'start.sh')
            (project / '.env').write_text('APP_ENV=local\n')
            uv = Path(folder) / 'uv'
            uv.write_text('#!/bin/sh\nprintf "%s\\n" "$PWD" "$@"\nexit 7\n')
            uv.chmod(0o755)
            run = subprocess.run(['bash', str(project / 'start.sh')], cwd='/',
                                 env={**os.environ, 'QUEST_UV_BIN': str(uv)},
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 7, run.stderr)
            self.assertIn(str(project), run.stdout.splitlines())
            self.assertIn('--env-file', run.stdout)
            self.assertIn(str(project / '.env'), run.stdout)

    def test_ide_configs_live_in_each_project(self):
        for project, filename in [('backend', 'Quest-API.run.xml'),
                                  ('backend', 'Quest-Worker.run.xml'),
                                  ('backend/ai-python', 'Quest-AI.run.xml'),
                                  ('frontend', 'Quest-PC.run.xml'),
                                  ('frontend', 'Quest-Mobile.run.xml')]:
            path = ROOT / project / '.run' / filename
            self.assertTrue(path.is_file(), str(path))
            config = ET.parse(path).find('configuration')
            options = {o.get('name'): o.get('value') for o in config.findall('option')}
            self.assertEqual(options['SCRIPT_WORKING_DIRECTORY'], '$PROJECT_DIR$')
            self.assertNotIn('PASSWORD', path.read_text())


if __name__ == '__main__':
    unittest.main()
