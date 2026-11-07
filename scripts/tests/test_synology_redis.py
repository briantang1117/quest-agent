"""Redis 群晖部署包静态契约，不代表 NAS 实机验收。"""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2] / 'deploy/synology-redis'


class SynologyRedisTests(unittest.TestCase):
    def test_compose_uses_x86_persistence_and_required_password(self):
        self.assertTrue((ROOT / 'docker-compose.yml').exists())
        service = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())['services']['redis']
        self.assertEqual(service['platform'], 'linux/amd64')
        self.assertEqual(service['image'], 'redis:7.4.12-alpine')
        self.assertIn('./data:/data', service['volumes'])
        self.assertIn('./redis.conf:/usr/local/etc/redis/redis.conf:ro', service['volumes'])
        self.assertIn('${REDIS_BIND_IP:?', service['ports'][0])
        self.assertIn('${REDIS_PASSWORD:?', service['environment']['REDIS_PASSWORD'])
        self.assertIn('--requirepass', str(service['command']))
        self.assertIn('docker-entrypoint.sh', str(service['command']))
        self.assertIn('REDISCLI_AUTH', str(service['healthcheck']))
        self.assertIn('grep -qx PONG', str(service['healthcheck']))
        self.assertNotIn('privileged', service)

    def test_configuration_preserves_security_keys_and_persistence(self):
        config = (ROOT / 'redis.conf').read_text()
        for setting in ('protected-mode yes', 'appendonly yes', 'appendfsync everysec',
                        'maxmemory 256mb', 'maxmemory-policy noeviction', 'dir /data',
                        'port 6379', 'daemonize no'):
            self.assertIn(setting, config)
        self.assertNotIn('requirepass', config)
        env = (ROOT / '.env.example').read_text()
        self.assertIn('REDIS_BIND_IP=192.168.3.40', env)
        self.assertIn('REDIS_PORT=16379', env)
        self.assertIn('REDIS_PASSWORD=\n', env)


if __name__ == '__main__':
    unittest.main()
