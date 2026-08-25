"""P10 代码执行沙箱。

安全模型（进程级隔离，Docker 缺失下的受限环境方案）：
1. AST 预检（主进程）：拒绝 import 白名单之外的模块、open/eval/exec/__import__ 等
   IO/反射入口、`__` 双下划线属性访问、危险全局名（os/sys/subprocess/socket）。
2. 进程隔离：`start_new_session=True` 独立进程组 + 最小化 env + 隔离工作目录，
   超时对整组 killpg 强杀（防 fork 炸弹逃逸）。
3. 资源限额：子进程 preexec_fn 内 setrlimit 锁 CPU 时间 / 虚拟内存 / 文件描述符 / 栈。
4. 输出治理：stdout/stderr 各截断 8KB，防止日志轰炸。

演示定位：安全执行"纯计算/算法"脚本。IO、网络、进程操作一律拒绝。
"""

from __future__ import annotations

import ast
import os
import resource
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

MAX_OUTPUT_CHARS = 8 * 1024
DEFAULT_TIMEOUT = 3.0

# 允许的纯计算 import（无 IO/网络/进程能力）
ALLOWED_IMPORTS = {
    "math", "statistics", "json", "re", "string", "collections",
    "itertools", "functools", "textwrap", "decimal", "fractions",
    "random", "bisect", "heapq", "array", "unicodedata", "dataclasses",
    "typing", "enum", "operator",
}

# 危险调用名：函数级黑名单
FORBIDDEN_CALLS = {
    "open", "eval", "exec", "compile", "__import__", "input", "breakpoint",
    "help", "exit", "quit", "globals", "locals", "vars", "getattr",
    "setattr", "delattr", "memoryview", "vars", "execfile", "input",
}

# 危险全局名：裸 Name 黑名单
FORBIDDEN_NAMES = {
    "os", "sys", "subprocess", "socket", "shutil", "pathlib", "platform",
    "ctypes", "winreg", "builtins", "importlib", "sysconfig", "pwd", "grp",
}

# 结果状态
STATUS_OK = "ok"
STATUS_REJECTED = "rejected"
STATUS_TIMEOUT = "timeout"
STATUS_CRASH = "crash"


class SandboxRejected(Exception):
    """代码未通过 AST 预检。"""


class SandboxExecutor:
    """进程隔离 + 资源限额的 Python 代码执行器。"""

    def __init__(self, timeout_seconds: float = DEFAULT_TIMEOUT, work_root: str | None = None) -> None:
        self.timeout = timeout_seconds
        self.work_root = Path(work_root) if work_root else Path(tempfile.gettempdir()) / "af-sandbox"
        self.work_root.mkdir(parents=True, exist_ok=True)

    async def run(self, code: str) -> dict[str, Any]:
        started = time.perf_counter()
        if not code or len(code) > 16 * 1024:
            return self._result(
                STATUS_REJECTED, "", "代码为空或超过 16KB 上限", started, error_code="CODE_TOO_LARGE"
            )
        try:
            self._validate_code(code)
        except SandboxRejected as exc:
            return self._result(STATUS_REJECTED, "", str(exc), started, error_code="CODE_REJECTED")

        workdir = tempfile.mkdtemp(prefix="run_", dir=self.work_root)
        try:
            env = {
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "HOME": workdir,
                "TMPDIR": workdir,
                "LANG": "C.UTF-8",
                "PYTHONIOENCODING": "utf-8",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
            proc = subprocess.Popen(
                [sys.executable, "-u", "-I", "-c", code],
                cwd=workdir,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
                preexec_fn=_limit_resources,
            )
            try:
                stdout_bytes, stderr_bytes = proc.communicate(timeout=self.timeout)
                timed_out = False
            except subprocess.TimeoutExpired:
                # 对整个进程组强杀，防子进程逃逸
                _kill_group(proc.pid)
                stdout_bytes, stderr_bytes = proc.communicate()
                timed_out = True

            stdout = stdout_bytes.decode("utf-8", errors="replace")
            stderr = stderr_bytes.decode("utf-8", errors="replace")
            if timed_out:
                return self._result(
                    STATUS_TIMEOUT,
                    _truncate(stdout),
                    f"执行超时（{self.timeout}s 上限），已终止整个进程组",
                    started,
                    error_code="EXEC_TIMEOUT",
                )
            if proc.returncode != 0:
                return self._result(
                    STATUS_CRASH,
                    _truncate(stdout),
                    _truncate(stderr) or f"进程退出码 {proc.returncode}",
                    started,
                    error_code="EXEC_CRASH",
                    exit_code=proc.returncode,
                )
            return self._result(STATUS_OK, _truncate(stdout), _truncate(stderr), started, exit_code=0)
        finally:
            _rmtree_safe(workdir)

    # ------------------------------------------------------------------
    def _validate_code(self, code: str) -> None:
        try:
            tree = ast.parse(code, mode="exec")
        except SyntaxError as exc:
            raise SandboxRejected(f"语法错误: {exc.msg}") from exc

        for node in ast.walk(tree):
            # 拒绝所有动态执行/IO 入口（exec/eval/open 等经 Call 黑名单拦截）
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id in FORBIDDEN_CALLS
            ):
                raise SandboxRejected(f"禁止调用: {node.func.id}")
            # import 白名单
            if isinstance(node, ast.Import):
                for alias in node.names:
                    top = alias.name.split(".")[0]
                    if top not in ALLOWED_IMPORTS:
                        raise SandboxRejected(f"禁止 import: {alias.name}")
            if isinstance(node, ast.ImportFrom):
                if node.module is None or node.module.split(".")[0] not in ALLOWED_IMPORTS:
                    raise SandboxRejected(f"禁止 import: {node.module or '(相对导入)'}")
                if node.level and node.level > 0:
                    raise SandboxRejected("禁止相对导入")
            # 双下划线属性（getattr 攻击面）
            if isinstance(node, ast.Attribute) and node.attr.startswith("__"):
                raise SandboxRejected(f"禁止访问魔术属性: {node.attr}")
            # 危险全局名
            if isinstance(node, ast.Name) and node.id in FORBIDDEN_NAMES:
                raise SandboxRejected(f"禁止使用: {node.id}")
        # 代码必须包含可执行语句（排除纯 pass 空白）
        if all(isinstance(n, ast.Pass) for n in tree.body):
            raise SandboxRejected("代码块为空")

    def _result(self, status: str, stdout: str, stderr: str, started: float,
                error_code: str | None = None, exit_code: int | None = None) -> dict[str, Any]:
        return {
            "status": status,
            "stdout": stdout,
            "stderr": stderr,
            "error_code": error_code,
            "exit_code": exit_code,
            "duration_ms": int((time.perf_counter() - started) * 1000),
        }


def _limit_resources() -> None:
    """子进程内资源限额（fork 后、exec 前）。"""
    try:
        resource.setrlimit(resource.RLIMIT_CPU, (2, 2))  # 2s CPU
        resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024,) * 2)  # 256MB 虚拟内存
        resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))  # 32 fd
        resource.setrlimit(resource.RLIMIT_STACK, (8 * 1024 * 1024,) * 2)  # 8MB 栈
    except (ValueError, OSError):
        pass  # 平台不支持时静默降级，主流程超时兜底


def _kill_group(pid: int) -> None:
    try:
        os.killpg(pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def _truncate(text: str) -> str:
    return text if len(text) <= MAX_OUTPUT_CHARS else text[:MAX_OUTPUT_CHARS] + f"\n…[输出截断 {len(text) - MAX_OUTPUT_CHARS} 字符]"


def _rmtree_safe(path: str) -> None:
    try:
        for root, dirs, files in os.walk(path, topdown=False):
            for name in files:
                try:
                    os.unlink(os.path.join(root, name))
                except OSError:
                    pass
            for name in dirs:
                try:
                    os.rmdir(os.path.join(root, name))
                except OSError:
                    pass
        os.rmdir(path)
    except OSError:
        pass
