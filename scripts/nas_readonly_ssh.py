"""从本机.env读取SSH密码，执行诊断命令；不输出密码，不接受未知主机指纹。"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
values = dotenv_values(ROOT / '.env', interpolate=False)
password = values.get('NAS_SSH_PASSWORD')
if not password:
    raise SystemExit('缺少 NAS_SSH_PASSWORD')
if len(sys.argv) != 2:
    raise SystemExit('用法：uv run --with python-dotenv python scripts/nas_readonly_ssh.py <诊断命令>')
secrets = [str(value) for key, value in values.items() if value and
           any(word in key for word in ('PASSWORD', 'SECRET', 'TOKEN', 'KEY'))]
with tempfile.TemporaryDirectory(prefix='quest-ssh-') as folder:
    askpass = Path(folder) / 'askpass'
    askpass.write_text('#!/bin/sh\nprintf "%s\\n" "$QUEST_SSH_PASSWORD"\n')
    askpass.chmod(0o700)
    env = {**os.environ, 'SSH_ASKPASS': str(askpass), 'SSH_ASKPASS_REQUIRE': 'force',
           'DISPLAY': 'quest:0', 'QUEST_SSH_PASSWORD': password}
    args = ['ssh', '-T', '-p', str(values.get('NAS_SSH_PORT') or 22),
            '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8',
            '-o', 'PreferredAuthentications=password,keyboard-interactive',
            '-o', 'PubkeyAuthentication=no', '-o', 'NumberOfPasswordPrompts=1',
            f"{values.get('NAS_SSH_USER') or 'brian'}@{values.get('NAS_SSH_HOST') or '192.168.3.40'}",
            sys.argv[1]]
    result = subprocess.run(args, input=password + '\n', capture_output=True,
                            text=True, env=env, timeout=60)
    output = result.stdout + result.stderr
    for value in sorted(secrets, key=len, reverse=True):
        output = output.replace(value, '[已隐藏]')
    print(output, end='')
    raise SystemExit(result.returncode)
