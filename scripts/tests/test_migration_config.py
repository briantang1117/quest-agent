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

    def test_explicit_v5_target_never_enables_paid_import_processing(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='5')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '5')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v6_target_keeps_import_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='6')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '6')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v7_target_keeps_import_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='7')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '7')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v8_target_keeps_import_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='8')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '8')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v9_target_keeps_import_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='9')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '9')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v11_target_keeps_import_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='11')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '11')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_explicit_v12_target_keeps_clean_and_processing_disabled(self):
        with patch.object(migrate, 'service_environment', return_value={
                'QUEST_PUBLIC_IMPORT_ENABLED': 'true'}):
            env = migrate.migration_environment(Path('/local/test'), target='12')
        self.assertEqual(env['SPRING_FLYWAY_TARGET'], '12')
        self.assertEqual(env['SPRING_FLYWAY_CLEAN_DISABLED'], 'true')
        self.assertEqual(env['QUEST_PUBLIC_IMPORT_ENABLED'], 'false')

    def test_v12_soft_delete_migration_copies_match_without_data_changes(self):
        root = SCRIPTS.parent
        name = 'V12__student_coach_session_soft_delete.sql'
        api_sql = (root / 'backend/api-java/src/main/resources/db/migration' / name).read_text()
        self.assertEqual(api_sql, (root / 'deploy/migrations' / name).read_text())
        statements = '\n'.join(line for line in api_sql.splitlines()
                               if not line.lstrip().startswith('--')).strip()
        self.assertEqual(statements, 'ALTER TABLE student_coach_session ADD COLUMN '
                         'deleted_at TIMESTAMP WITH TIME ZONE;')

    def test_unknown_target_is_rejected_before_reading_credentials(self):
        with patch.object(migrate, 'service_environment') as load:
            with self.assertRaises(ValueError):
                migrate.migration_environment(Path('/local/test'), target='13')
            load.assert_not_called()


if __name__ == '__main__':
    unittest.main()
