"""在临时工程中验证启动脚本，不启动真实服务或连接中间件。"""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/start-java.sh"


def executable(path, source):
    path.write_text(f"#!{sys.executable}\n" + source)
    path.chmod(0o700)


@pytest.fixture
def project(tmp_path):
    backend = tmp_path / "backend"
    (backend / "scripts").mkdir(parents=True)
    shutil.copyfile(SCRIPT, backend / "scripts/start-java.sh")
    for kind in ("api", "worker"):
        (backend / f".env.{kind}.properties").write_text("")
    jdk = tmp_path / "jdk"
    (jdk / "bin").mkdir(parents=True)
    executable(
        jdk / "bin/java",
        """
import json, os, signal, sys, time
from pathlib import Path
if '-version' in sys.argv:
    sys.stderr.write('openjdk version "27.0.1"\\n')
    sys.exit(0)
jar = Path(sys.argv[sys.argv.index('-jar') + 1])
module = jar.parent.name.split('.')[0]
root = Path(os.environ['QUEST_FAKE_ROOT'])
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
(root / ('ready-' + module + '.json')).write_text(json.dumps({'jar': str(jar), 'pid': os.getpid()}))
while True:
    if jar.read_text() != module:
        (root / ('broken-' + module)).write_text('running JAR changed')
        sys.exit(2)
    time.sleep(0.02)
""",
    )
    executable(
        backend / "fake_maven",
        """
import json, os, sys, time
from pathlib import Path
root = Path(os.environ['QUEST_FAKE_ROOT'])
module = sys.argv[sys.argv.index('-pl') + 1]
with (root / 'builds.jsonl').open('a') as log:
    log.write(json.dumps({'module': module, 'args': sys.argv[1:]}) + '\\n')
marker = root / 'building'
try:
    marker.mkdir()
except FileExistsError:
    (root / 'overlap').write_text('concurrent Maven builds')
    sys.exit(4)
try:
    time.sleep(0.15)
    if os.environ.get('QUEST_FAKE_FAIL') == module:
        sys.exit(23)
    target = root / 'backend' / module / 'target'
    target.mkdir(parents=True, exist_ok=True)
    (target / (module + '-0.1.0.jar')).write_text(module)
finally:
    marker.rmdir()
""",
    )
    (backend / "mvnw").write_text('#!/bin/bash\nexec "$QUEST_FAKE_ROOT/backend/fake_maven" "$@"\n')
    env = {**os.environ, "QUEST_JAVA_HOME": str(jdk), "QUEST_FAKE_ROOT": str(tmp_path)}
    return backend, env


def start(project, kind, **extra):
    backend, env = project
    return subprocess.Popen(
        ["bash", str(backend / "scripts/start-java.sh"), kind],
        cwd=backend.parent,
        env={**env, **extra},
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def wait_ready(root, kind, process):
    ready = root / f"ready-{kind}.json"
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if ready.exists():
            return json.loads(ready.read_text())
        if process.poll() is not None:
            pytest.fail(process.stdout.read().decode())
        time.sleep(0.02)
    pytest.fail("临时服务未按时启动")


def stop(process):
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=5)


def test_services_build_serially_and_use_immutable_running_copies(project):
    backend, _ = project
    root = backend.parent
    api = start(project, "api")
    worker = start(project, "worker")
    try:
        a = wait_ready(root, "api-java", api)
        w = wait_ready(root, "worker-java", worker)
        assert not (root / "overlap").exists()
        builds = [json.loads(row) for row in (root / "builds.jsonl").read_text().splitlines()]
        assert sorted(row["module"] for row in builds) == ["api-java", "worker-java"]
        assert all("-am" in row["args"] for row in builds)
        for module, state in [("api-java", a), ("worker-java", w)]:
            target = backend / module / "target" / f"{module}-0.1.0.jar"
            assert Path(state["jar"]) != target
            target.write_text("later-build-output")
            assert Path(state["jar"]).read_text() == module
        time.sleep(0.1)
        assert api.poll() is None and worker.poll() is None
        assert not list(root.glob("broken-*"))
    finally:
        stop(api)
        stop(worker)
    assert not Path(a["jar"]).exists()
    assert not Path(w["jar"]).exists()


def test_failed_build_releases_lock_and_does_not_start_service(project):
    backend, _ = project
    failure = start(project, "api", QUEST_FAKE_FAIL="api-java")
    assert failure.wait(timeout=10) == 23
    assert not (backend.parent / "ready-api-java.json").exists()
    success = start(project, "api")
    try:
        state = wait_ready(backend.parent, "api-java", success)
        assert Path(state["jar"]).exists()
    finally:
        stop(success)
    assert not Path(state["jar"]).exists()
