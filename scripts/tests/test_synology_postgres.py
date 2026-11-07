"""群晖 PostgreSQL 部署包静态契约，不代表已在 NAS 启动。"""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2] / 'deploy/synology-postgres'


class SynologyPostgresTests(unittest.TestCase):
    def test_x86_image_persistent_storage_and_health(self):
        self.assertTrue((ROOT / 'docker-compose.yml').exists())
        service = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())['services']['postgres']
        self.assertEqual(service['platform'], 'linux/amd64')
        self.assertEqual(service['image'], 'pgvector/pgvector:0.8.2-pg16')
        self.assertIn('./data:/var/lib/postgresql/data', service['volumes'])
        self.assertIn('./init:/docker-entrypoint-initdb.d:ro', service['volumes'])
        self.assertIn('pg_isready', str(service['healthcheck']))
        self.assertNotIn('user', service)
        self.assertNotIn('privileged', service)
        self.assertNotIn('POSTGRES_HOST_AUTH_METHOD', service['environment'])

    def test_requires_network_binding_and_independent_passwords(self):
        service = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())['services']['postgres']
        self.assertIn('${PG_BIND_IP:', service['ports'][0])
        for key in ('PG_ADMIN_PASSWORD', 'PG_OWNER_PASSWORD', 'PG_APP_PASSWORD', 'PG_AI_PASSWORD'):
            self.assertIn(f'${{{key}:?', str(service['environment']))
            # 用户可能已填写本地模板；仅验证字段存在，避免断言输出泄露密码。
            keys = {line.split('=', 1)[0] for line in (ROOT / '.env.example').read_text().splitlines()
                    if line and not line.startswith('#') and '=' in line}
            self.assertTrue(key in keys, f'缺少配置项 {key}')

    def test_initialization_creates_extensions_and_separate_roles(self):
        script = (ROOT / 'init/001-bootstrap.sh').read_text()
        for value in ('CREATE EXTENSION IF NOT EXISTS vector', 'CREATE EXTENSION IF NOT EXISTS pg_trgm',
                      'CREATE ROLE quest_owner', 'CREATE ROLE quest_app', 'CREATE ROLE quest_ai',
                      'REVOKE CREATE ON SCHEMA public FROM PUBLIC'):
            self.assertIn(value, script)
        self.assertNotIn('CREATE TABLE question', script)
        self.assertIn('pg_stat_statements', (ROOT / 'docker-compose.yml').read_text())


if __name__ == '__main__':
    unittest.main()
