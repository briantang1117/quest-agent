"""用户模块跨工程静态契约；不连接共享数据库，不创建Admin。"""
from pathlib import Path
import unittest

from pglast import parse_sql

ROOT = Path(__file__).resolve().parents[2]


class UserModuleContractTests(unittest.TestCase):
    def test_v4_migration_is_packaged_and_deployment_copy_is_identical(self):
        folder = ROOT / 'backend/api-java/src/main/resources/db/migration'
        files = sorted(folder.glob('V4__*.sql'))
        self.assertEqual(len(files), 1, '用户模块应有单独V4，不改V1–V3')
        migration = files[0]
        self.assertGreater(len(parse_sql(migration.read_text())), 0)
        self.assertEqual(migration.read_bytes(),
                         (ROOT / 'deploy/migrations' / migration.name).read_bytes())

    def test_nginx_overwrites_login_client_source(self):
        config = (ROOT / 'deploy/nginx/default.conf').read_text()
        self.assertIn('proxy_set_header X-Quest-Client-IP $remote_addr;', config)
        self.assertNotIn('proxy_set_header X-Quest-Client-IP $http_', config)

    def test_user_contract_has_no_public_registration_or_content_admin_permissions(self):
        text = (ROOT / 'docs/用户模块接口与验收.md').read_text()
        self.assertIn('不提供', text)
        self.assertIn('Admin和Student类型互斥', text)
        self.assertIn('刷新后先请求/auth/me校验才恢复身份', text)
        self.assertIn('不保存密码或信任缓存角色', text)
        self.assertIn('首轮后台不包含题目', text)
        self.assertIn('当前没有可靠私有库统计',
                      (ROOT / 'docs/superpowers/plans/2026-11-07-user-module.md').read_text())


if __name__ == '__main__':
    unittest.main()
