"""本机开发入口：复用NAS中间件，不部署远程容器、不输出密钥。"""
import json
import os
from pathlib import Path
import secrets
import sys

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]


def service_environment(root: Path, service: str) -> dict[str, str]:
    app = dotenv_values(root / '.env', interpolate=False)
    pg = dotenv_values(root / 'deploy/synology-postgres/.env', interpolate=False)
    redis = dotenv_values(root / 'deploy/synology-redis/.env', interpolate=False)
    folder = root / 'backend/tools/dev'
    folder.mkdir(parents=True, exist_ok=True)
    secret_file = folder / 'secrets.json'
    if not secret_file.exists():
        data = {key: secrets.token_hex(32) for key in
                ('JWT_SECRET', 'INTERNAL_SERVICE_TOKEN', 'INTERNAL_HMAC_SECRET')}
        with open(secret_file, 'x', opener=lambda p, flags: os.open(p, flags, 0o600)) as handle:
            json.dump(data, handle)
    generated = json.loads(secret_file.read_text())
    env = {key: app.get(key) or value for key, value in generated.items()}
    for key in ('LLM_BASE_URL', 'LLM_API_KEY', 'LLM_MODEL', 'STORAGE_ENDPOINT',
                'STORAGE_PUBLIC_ENDPOINT', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY'):
        if app.get(key):
            env[key] = app[key]
    env.update({
        'APP_ENV': 'production',
        'AI_SERVICE_URL': 'http://127.0.0.1:8000',
        'REDIS_HOST': redis.get('REDIS_BIND_IP') or '192.168.3.40',
        'REDIS_PORT': redis.get('REDIS_PORT') or '16379',
        'REDIS_PASSWORD': redis.get('REDIS_PASSWORD') or '',
        'ROCKETMQ_NAME_SERVER': app.get('ROCKETMQ_NAME_SERVER') or '192.168.3.40:9876',
    })
    if service in ('api', 'worker'):
        for key in ('PG_APP_PASSWORD', 'PG_OWNER_PASSWORD') if service == 'api' else ('PG_APP_PASSWORD',):
            if not pg.get(key):
                raise ValueError(f'请在deploy/synology-postgres/.env填写{key}')
        env.update({
            'SPRING_PROFILES_ACTIVE': 'deployment',
            'SPRING_DATASOURCE_URL': f"jdbc:postgresql://{pg.get('PG_BIND_IP') or '192.168.3.40'}:{pg.get('PG_PORT') or '15432'}/quest",
            'SPRING_DATASOURCE_USERNAME': 'quest_app',
            'SPRING_DATASOURCE_PASSWORD': pg['PG_APP_PASSWORD'],
            'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '5',
            'OUTBOX_ENABLED': 'false',
            'TZ': 'UTC',
        })
        if service == 'api':
            env.update({
                'SPRING_FLYWAY_USER': 'quest_owner',
                'SPRING_FLYWAY_PASSWORD': pg['PG_OWNER_PASSWORD'],
                'SPRING_FLYWAY_LOCATIONS': 'filesystem:' + str(root / 'deploy/migrations'),
                # 远程建表/授权迁移必须独立明确批准，默认只连接、不迁移。
                'SPRING_FLYWAY_ENABLED': 'false',
                'SERVER_ADDRESS': '127.0.0.1',
                'SERVER_PORT': '8080',
            })
    return env


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in ('api', 'worker', 'ai', 'pc', 'mobile'):
        raise SystemExit('用法：uv run --with python-dotenv python scripts/dev.py api|worker|ai|pc|mobile')
    service = sys.argv[1]
    # 旧入口仅兼容转发；日常启动使用各子工程自己的配置，不再调用上面的旧迁移映射。
    launchers = {
        'api': ROOT / 'backend/scripts/start-api.sh',
        'worker': ROOT / 'backend/scripts/start-worker.sh',
        'ai': ROOT / 'backend/ai-python/start.sh',
        'pc': ROOT / 'frontend/start-pc.sh',
        'mobile': ROOT / 'frontend/start-mobile.sh',
    }
    os.execvp('/bin/bash', ['/bin/bash', str(launchers[service])])


if __name__ == '__main__':
    main()
