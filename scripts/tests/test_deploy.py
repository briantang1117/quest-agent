"""基础设施契约：静态验证不等同于真实容器集成测试。"""
import pathlib
import re
import unittest

import yaml
from pglast import parse_sql

ROOT = pathlib.Path(__file__).resolve().parents[2]


class DeploymentContractTests(unittest.TestCase):
    def test_compose_keeps_ai_private_and_database_credentials_separate(self):
        services = yaml.safe_load((ROOT / 'deploy/docker-compose.yml').read_text())['services']
        self.assertNotIn('ports', services['ai-service'])
        self.assertNotIn('ports', services['postgres'])
        self.assertNotIn('ports', services['redis'])
        self.assertEqual(services['api-java']['environment']['SPRING_DATASOURCE_USERNAME'], 'quest_app')
        self.assertEqual(services['api-java']['environment']['SPRING_FLYWAY_USER'], 'quest_owner')
        self.assertEqual(services['ai-service']['environment']['APP_ENV'], 'production')
        self.assertNotIn('minio', services)
        self.assertIn('MINIO_ROOT_PASSWORD', (ROOT / 'deploy/nas-minio/docker-compose.yml').read_text())

    def test_nginx_never_bypasses_java_for_agent_requests(self):
        config = (ROOT / 'deploy/nginx/default.conf').read_text()
        self.assertNotIn('ai-service', config)
        self.assertIn('proxy_pass http://api-java:8080', config)
        self.assertIn('proxy_buffering off', config)
        self.assertIn('location /m/', config)

    def test_migrations_parse_and_cover_core_domains(self):
        migrations = sorted((ROOT / 'deploy/migrations').glob('V*__*.sql'))
        self.assertGreaterEqual(len(migrations), 2)
        sql = '\n'.join(path.read_text() for path in migrations)
        self.assertGreater(len(parse_sql(sql)), 35)
        for table in ('question', 'question_version', 'question_embedding', 'mq_outbox',
                      'mq_consume_log', 'answer_record', 'paper', 'exam_attempt',
                      'app_user', 'audit_log', 'agent_session', 'agent_message'):
            self.assertRegex(sql, rf'CREATE TABLE {table}\s*\(')
        self.assertIn('vector(1024)', sql)
        self.assertIn('PARTITION BY RANGE (answered_at)', sql)
        self.assertIn('PRIMARY KEY (id, answered_at)', sql)

    def test_ai_role_is_read_only_except_index_tables(self):
        sql = (ROOT / 'deploy/migrations/V3__runtime_privileges.sql').read_text()
        self.assertIsNotNone(parse_sql(sql))
        self.assertRegex(sql, r'GRANT SELECT ON question,')
        self.assertIn('GRANT SELECT, INSERT, UPDATE, DELETE ON question_embedding, kb_doc TO quest_ai', sql)
        self.assertNotRegex(sql, r'GRANT ALL.*TO quest_ai')
        self.assertNotRegex(sql, r'GRANT .*ON ALL TABLES.*TO quest_ai')
        self.assertNotIn('IN SCHEMA public GRANT SELECT', sql)

    def test_runtime_dockerfiles_match_compose_build_contexts(self):
        services = yaml.safe_load((ROOT / 'deploy/docker-compose.yml').read_text())['services']
        for service in ('api-java', 'worker-java'):
            build = services[service]['build']
            context = (ROOT / 'deploy' / build['context']).resolve()
            dockerfile = (context / build['dockerfile']).read_text()
            self.assertIn(f'{service}/target/{service}-0.1.0.jar', dockerfile)
            self.assertNotIn('**/target', (context / '.dockerignore').read_text())
        worker = yaml.safe_load((ROOT / 'backend/worker-java/src/main/resources/application-deployment.yml').read_text())
        self.assertFalse(worker['spring']['flyway']['enabled'])

    def test_environment_template_has_no_real_credentials(self):
        template = (ROOT / '.env.example').read_text()
        for variable in ('PG_ADMIN_PASSWORD', 'PG_OWNER_PASSWORD', 'PG_APP_PASSWORD',
                         'PG_AI_PASSWORD', 'JWT_SECRET', 'INTERNAL_SERVICE_TOKEN',
                         'INTERNAL_HMAC_SECRET', 'LLM_BASE_URL', 'LLM_API_KEY', 'LLM_MODEL'):
            self.assertRegex(template, rf'(?m)^{variable}=')
        self.assertIn('.env', (ROOT / '.gitignore').read_text())


if __name__ == '__main__':
    unittest.main()
