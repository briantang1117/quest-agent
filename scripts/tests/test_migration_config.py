"""迁移配置测试只检查本地环境映射，绝不连接数据库或启动迁移。"""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import migrate  # noqa: E402


class MigrationConfigTests(unittest.TestCase):
    def test_only_explicit_migration_enables_flyway_with_version_limit(self):
        with patch.object(migrate, 'service_environment', return_value={
                'SPRING_FLYWAY_ENABLED': 'false',
                'SPRING_DATASOURCE_USERNAME': 'quest_app'}):
            env = migrate.migration_environment(Path('/local/test'))
        self.assertEqual(env['SPRING_FLYWAY_ENABLED'], 'true')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '3')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')
        self.assertEqual(env['SERVER_PORT'], '18081')

    def test_explicit_v4_target_keeps_clean_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={}):
            env = migrate.migration_environment(Path('/local/test'), target='4')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '4')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')

    def test_unknown_target_is_rejected_before_reading_credentials(self):
        with patch.object(migrate, 'service_environment') as load:
            with self.assertRaises(ValueError):
                migrate.migration_environment(Path('/local/test'), target='5')
            load.assert_not_called()


if __name__ == '__main__':
    unittest.main()
