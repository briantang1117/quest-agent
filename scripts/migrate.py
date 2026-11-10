"""显式执行已批准的 Flyway目标；与日常启动分离，不批准未来任意迁移。"""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

from dev import ROOT, service_environment


def migration_environment(root: Path, target: str = '3') -> dict[str, str]:
    if target not in ('3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13'):
        raise ValueError('仅允许已定义的目标3–13；执行迁移需独立审查授权')
    config = service_environment(root, 'api')
    config.update({
        'SPRING_FLYWAY_ENABLED': 'true',
        'SPRING_FLYWAY_TARGET': target,
        'SPRING_FLYWAY_CLEAN_DISABLED': 'true',
        'SERVER_ADDRESS': '127.0.0.1',
        'SERVER_PORT': '18081',
        'QUEST_PUBLIC_IMPORT_ENABLED': 'false',
    })
    return config


def main() -> None:
    parser = argparse.ArgumentParser(description='显式迁移工具；先核对目标和授权，不用于日常启动')
    parser.add_argument('--target', choices=('3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13'), default='3')
    arguments = parser.parse_args()
    config = migration_environment(ROOT, arguments.target)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 18081))
    java = Path(os.environ.get('QUEST_JAVA_HOME') or ROOT / 'backend/tools/jdk/Contents/Home')
    java = java / 'bin/java'
    jar = ROOT / 'backend/api-java/target/api-java-0.1.0.jar'
    if not java.is_file() or not jar.is_file():
        raise SystemExit('缺少JDK27或API JAR；先执行 ./backend/mvnw clean verify')
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG') if key in os.environ}
    env.update(config)
    log = ROOT / f'backend/tools/dev/migrate-v{arguments.target}.log'
    print(f'显式迁移群晖quest库，目标版本{arguments.target}；不执行clean，不输出凭据。', flush=True)
    with open(log, 'w', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
        process = subprocess.Popen([str(java), '-Xms128m', '-Xmx512m', '-jar', str(jar)],
                                   cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'迁移进程提前退出；检查本机受限日志 {log}')
                try:
                    with urllib.request.urlopen(
                            'http://127.0.0.1:18081/actuator/health/readiness', timeout=2) as response:
                        if response.status == 200:
                            print('Flyway启动迁移完成，数据库readiness=200；停止临时迁移进程。')
                            return
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(0.3)
            raise RuntimeError(f'迁移超时；检查本机受限日志 {log}')
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == '__main__':
    main()
