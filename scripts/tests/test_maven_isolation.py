"""验证 Maven 项目配置隔离，使用临时工具替身，不访问公司仓库。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/SETTINGS/1.2.0'}


class MavenIsolationTests(unittest.TestCase):
    def test_project_settings_only_use_public_central(self):
        settings = ROOT / 'backend/.mvn/settings.xml'
        self.assertTrue(settings.exists(), '缺少独立项目 settings.xml')
        tree = ET.parse(settings)
        mirrors = tree.findall('m:mirrors/m:mirror', NS)
        self.assertEqual(len(mirrors), 1)
        self.assertEqual(mirrors[0].findtext('m:url', namespaces=NS), 'https://repo.maven.apache.org/maven2')
        self.assertEqual(mirrors[0].findtext('m:mirrorOf', namespaces=NS), '*')
        self.assertEqual(tree.findtext('m:localRepository', namespaces=NS), '${quest.maven.repo}')
        self.assertEqual(tree.findall('m:servers/m:server', NS), [])

    def test_wrapper_overrides_user_and_global_settings_and_uses_separate_cache(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder) / 'backend'
            (base / '.mvn/wrapper').mkdir(parents=True)
            shutil.copy(ROOT / 'backend/mvnw', base / 'mvnw')
            shutil.copy(ROOT / 'backend/.mvn/wrapper/maven-wrapper.properties', base / '.mvn/wrapper/maven-wrapper.properties')
            for name in ('settings.xml', 'global-settings.xml'):
                source = ROOT / 'backend/.mvn' / name
                if source.exists():
                    shutil.copy(source, base / '.mvn' / name)
            version = '3.10.0'
            binary = base / f'tools/distributions/apache-maven-{version}/bin/mvn'
            binary.parent.mkdir(parents=True)
            binary.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            binary.chmod(0o755)
            jdk = Path(folder) / 'jdk'
            (jdk / 'bin').mkdir(parents=True)
            java = jdk / 'bin/java'
            java.write_text('#!/bin/sh\nprintf \'openjdk version "27.0.1"\\n\' >&2\n')
            java.chmod(0o755)
            env = {**os.environ, 'JAVA_HOME': str(jdk)}
            result = subprocess.run(['sh', str(base / 'mvnw'), '-B', 'verify'], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            args = result.stdout.splitlines()
            self.assertIn('--settings', args)
            self.assertIn(str(base / '.mvn/settings.xml'), args)
            self.assertIn('--global-settings', args)
            self.assertIn(str(base / '.mvn/global-settings.xml'), args)
            self.assertIn(f'-Dquest.maven.repo={base}/tools/repository', args)
            self.assertNotIn(str(Path.home() / '.m2'), result.stdout)

    def test_java_build_and_runtime_target_27(self):
        pom = ET.parse(ROOT / 'backend/pom.xml')
        self.assertEqual(pom.findtext('.//{http://maven.apache.org/POM/4.0.0}java.version'), '27')
        for service in ('api-java', 'worker-java'):
            self.assertIn('FROM eclipse-temurin:27-jre', (ROOT / f'backend/{service}/Dockerfile').read_text())


if __name__ == '__main__':
    unittest.main()
