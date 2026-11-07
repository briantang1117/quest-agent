import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DevConfigTests(unittest.TestCase):
    def test_maps_nas_endpoints_without_admin_or_ssh_secrets(self):
        spec = importlib.util.spec_from_file_location('quest_dev', ROOT / 'scripts/dev.py')
        self.assertIsNotNone(spec)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'deploy/synology-postgres').mkdir(parents=True)
            (root / 'deploy/synology-redis').mkdir(parents=True)
            (root / 'deploy/synology-postgres/.env').write_text(
                'PG_BIND_IP=192.168.3.40\nPG_PORT=15432\nPG_APP_PASSWORD=app\n'
                'PG_OWNER_PASSWORD=owner\nPG_ADMIN_PASSWORD=never-pass\n')
            (root / 'deploy/synology-redis/.env').write_text(
                'REDIS_BIND_IP=192.168.3.40\nREDIS_PORT=16379\nREDIS_PASSWORD=redis\n')
            (root / '.env').write_text('NAS_SSH_PASSWORD=never-pass\n')
            env = module.service_environment(root, 'api')
            self.assertEqual(env['SPRING_DATASOURCE_URL'], 'jdbc:postgresql://192.168.3.40:15432/quest')
            self.assertEqual(env['SPRING_DATASOURCE_USERNAME'], 'quest_app')
            self.assertEqual(env['SPRING_FLYWAY_USER'], 'quest_owner')
            self.assertEqual(env['SPRING_FLYWAY_ENABLED'], 'false')
            self.assertEqual(env['REDIS_PORT'], '16379')
            self.assertNotIn('PG_ADMIN_PASSWORD', env)
            self.assertNotIn('NAS_SSH_PASSWORD', env)
            self.assertGreaterEqual(len(env['JWT_SECRET']), 32)
            self.assertEqual(env['JWT_SECRET'], module.service_environment(root, 'api')['JWT_SECRET'])
            worker = module.service_environment(root, 'worker')
            self.assertNotIn('SPRING_FLYWAY_PASSWORD', worker)
            self.assertEqual(worker['ROCKETMQ_NAME_SERVER'], '192.168.3.40:9876')


if __name__ == '__main__':
    unittest.main()
