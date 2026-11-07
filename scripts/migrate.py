"""显式执行已批准的 Flyway V1–V3；与日常开发启动分离。"""
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

from dev import ROOT, service_environment


def migration_environment(root: Path) -> dict[str, str]:
    config = service_environment(root, 'api')
    config.update({
        'SPRING_FLYWAY_ENABLED': 'true',
        'SPRING_FLYWAY_TARGET': '3',
        'SPRING_FLYWAY_CLEAN_DISABLED': 'true',
        'SERVER_ADDRESS': '127.0.0.1',
        'SERVER_PORT': '18081',
    })
    return config


def main() -> None:
    config = migration_environment(ROOT)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 18081))
    java = Path(os.environ.get('QUEST_JAVA_HOME') or ROOT / 'backend/tools/jdk/Contents/Home')
    java = java / 'bin/java'
    jar = ROOT / 'backend/api-java/target/api-java-0.1.0.jar'
    if not java.is_file() or not jar.is_file():
        raise SystemExit('缺少JDK27或API JAR；先执行 ./backend/mvnw clean verify')
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG') if key in os.environ}
    env.update(config)
    log = ROOT / 'backend/tools/dev/migrate.log'
    print('显式迁移群晖quest库，目标版本3；不执行clean，不输出凭据。', flush=True)
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
