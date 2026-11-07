"""检查子工程配置与部署兼容副本，不读取任何真实凭据。"""
from pathlib import Path
import json
import unittest
import xml.etree.ElementTree as ET

import yaml

ROOT = Path(__file__).resolve().parents[2]


class IndependentProjectTests(unittest.TestCase):
    def test_frontend_has_local_workspace_and_lockfile(self):
        folder = ROOT / 'frontend'
        config = yaml.safe_load((folder / 'pnpm-workspace.yaml').read_text())
        self.assertEqual(set(config['packages']), {'pc-web', 'mobile-agent', 'shared'})
        lock = yaml.safe_load((folder / 'pnpm-lock.yaml').read_text())
        self.assertEqual(set(lock['importers']), {'.', 'pc-web', 'mobile-agent', 'shared'})
        self.assertNotIn('--dir frontend', (folder / 'package.json').read_text())
        self.assertEqual(json.loads((ROOT / 'package.json').read_text())['scripts']['test'],
                         'pnpm --dir frontend test')

    def test_java_configuration_is_local_and_migrations_packaged_unchanged(self):
        for service in ('api', 'worker'):
            config = yaml.safe_load((ROOT / f'backend/{service}-java/src/main/resources/application.yml').read_text())
            self.assertEqual(config['spring']['config']['import'],
                             f'optional:file:./.env.{service}.properties')
        api = yaml.safe_load((ROOT / 'backend/api-java/src/main/resources/application-deployment.yml').read_text())
        self.assertEqual(api['spring']['flyway']['locations'],
                         '${SPRING_FLYWAY_LOCATIONS:classpath:db/migration}')
        for path in (ROOT / 'deploy/migrations').glob('V*__*.sql'):
            self.assertEqual(path.read_bytes(),
                             (ROOT / 'backend/api-java/src/main/resources/db/migration' / path.name).read_bytes())
        for service, main in [('API', 'com.quest.api.ApiApplication'),
                              ('Worker', 'com.quest.worker.WorkerApplication')]:
            config = ET.parse(ROOT / f'backend/.run/Quest-{service}-Native.run.xml').find('configuration')
            self.assertEqual(config.get('type'), 'Application')
            options = {o.get('name'): o.get('value') for o in config.findall('option')}
            self.assertEqual(options['MAIN_CLASS_NAME'], main)
            self.assertEqual(options['WORKING_DIRECTORY'], '$PROJECT_DIR$')


if __name__ == '__main__':
    unittest.main()
