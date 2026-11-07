"""MinIO 镜像渠道修复的静态回归，不等于 NAS 实机验收。"""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2] / 'deploy/synology-minio'
IMAGE = ('pgsty/silo:RELEASE.2026-10-26T00-00-00Z'
         '@sha256:7bb2aff6b89efe1cb1303c10fc21d579447434ed06649ac9ec05a5e2dbf30ad7')


class SynologyMinioTests(unittest.TestCase):
    def test_image_is_fixed_verified_source_and_x86(self):
        service = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())['services']['minio']
        self.assertEqual(service['image'], IMAGE)
        self.assertEqual(service['platform'], 'linux/amd64')
        self.assertNotIn('build', service)
        self.assertIn('./data:/data', service['volumes'])

    def test_health_uses_bundled_mcli_and_preserves_minio_environment(self):
        service = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())['services']['minio']
        health = str(service['healthcheck']['test'])
        self.assertNotIn('curl', health)
        self.assertIn('mcli ready', health)
        self.assertIn('$$MINIO_ROOT_PASSWORD', health)
        self.assertIn('${MINIO_ROOT_PASSWORD:?', service['environment']['MINIO_ROOT_PASSWORD'])
        self.assertEqual(service['environment']['MC_CONFIG_DIR'], '/tmp/.mc-health')


if __name__ == '__main__':
    unittest.main()
