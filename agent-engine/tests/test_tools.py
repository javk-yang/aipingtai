import pytest

from app.config import Settings
from app.tools.builtin import BUILTIN_DESCRIPTORS
from app.tools.gateway import ToolGateway
from app.tools.schemas import ToolCallRequest


@pytest.mark.asyncio
async def test_calculator_tool_executes_without_eval() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "calculator")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-calc",
            tool_code="calculator",
            arguments={"expression": "12 * (3 + 4)"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "success"
    assert result.result["value"] == "84"


@pytest.mark.asyncio
async def test_calculator_rejects_unsafe_syntax() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "calculator")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-unsafe",
            tool_code="calculator",
            arguments={"expression": "__import__('os').system('id')"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "error"
    assert result.error_code in {"INVALID_TOOL_ARGUMENTS", "INVALID_EXPRESSION"}


@pytest.mark.asyncio
async def test_calculator_hides_internal_division_error() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "calculator")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-zero",
            tool_code="calculator",
            arguments={"expression": "1 / 0"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "error"
    assert result.error_code == "INVALID_EXPRESSION"
    assert result.error_message == "除数不能为 0"
    assert "decimal" not in result.error_message


# ---------------------------------------------------------------------------
# P10 代码执行沙箱
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_code_exec_success() -> None:
    from app.tools.builtin import code_exec

    result = await code_exec({"code": "nums = [3, 1, 4, 1, 5]\nprint('sorted:', sorted(nums))\nprint('sum:', sum(nums))"})
    assert result["status"] == "ok"
    assert "sorted: [1, 1, 3, 4, 5]" in result["stdout"]
    assert "sum: 14" in result["stdout"]
    assert result["duration_ms"] < 3000


@pytest.mark.asyncio
async def test_code_exec_rejects_import_os() -> None:
    from app.tools.builtin import code_exec
    from app.tools.errors import ToolGatewayError

    with pytest.raises(ToolGatewayError) as exc_info:
        await code_exec({"code": "import os\nprint(os.getcwd())"})
    assert "禁止 import" in exc_info.value.message


@pytest.mark.asyncio
async def test_code_exec_rejects_open_file() -> None:
    from app.tools.builtin import code_exec
    from app.tools.errors import ToolGatewayError

    with pytest.raises(ToolGatewayError) as exc_info:
        await code_exec({"code": "f = open('/etc/passwd')\nprint(f.read())"})
    assert "禁止调用" in exc_info.value.message


@pytest.mark.asyncio
async def test_code_exec_rejects_magic_attribute() -> None:
    from app.tools.builtin import code_exec
    from app.tools.errors import ToolGatewayError

    with pytest.raises(ToolGatewayError) as exc_info:
        await code_exec({"code": "print(''.__class__)"})
    assert "魔术属性" in exc_info.value.message


@pytest.mark.asyncio
async def test_code_exec_timeout_kills_process_group() -> None:
    from app.sandbox.executor import SandboxExecutor

    box = SandboxExecutor(timeout_seconds=0.8)
    result = await box.run("while True:\n    pass")
    assert result["status"] == "timeout"
    assert result["error_code"] == "EXEC_TIMEOUT"


@pytest.mark.asyncio
async def test_code_exec_crash_reports_traceback() -> None:
    from app.tools.builtin import code_exec

    result = await code_exec({"code": "print(1)\nraise ValueError('boom')"})
    assert result["status"] == "crash"
    assert "ValueError" in result["stderr"]


@pytest.mark.asyncio
async def test_code_exec_math_import_allowed() -> None:
    from app.tools.builtin import code_exec

    result = await code_exec({"code": "import math\nprint(math.factorial(10))"})
    assert result["status"] == "ok"
    assert "3628800" in result["stdout"]


@pytest.mark.asyncio
async def test_code_exec_through_gateway() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "code_exec")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-sandbox",
            tool_code="code_exec",
            arguments={"code": "print('hello from sandbox')"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "success"
    assert "hello from sandbox" in result.result["stdout"]


@pytest.mark.asyncio
async def test_unit_converter_weight() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "unit_converter")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-conv-1",
            tool_code="unit_converter",
            arguments={"value": 5, "from_unit": "kg", "to_unit": "jin"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "success"
    assert result.result["result"] == 10
    assert result.result["display"] == "5 kg = 10 jin"


@pytest.mark.asyncio
async def test_unit_converter_temperature_offset() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "unit_converter")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-conv-2",
            tool_code="unit_converter",
            arguments={"value": 100, "from_unit": "celsius", "to_unit": "fahrenheit"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "success"
    assert result.result["result"] == 212


@pytest.mark.asyncio
async def test_unit_converter_rejects_cross_category() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "unit_converter")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-conv-3",
            tool_code="unit_converter",
            arguments={"value": 1, "from_unit": "kg", "to_unit": "m"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "error"
    assert result.error_code == "INCOMPATIBLE_UNIT"


@pytest.mark.asyncio
async def test_unit_converter_rejects_unknown_unit() -> None:
    gateway = ToolGateway(Settings())
    descriptor = next(tool for tool in BUILTIN_DESCRIPTORS if tool.code == "unit_converter")
    result = await gateway.execute(
        ToolCallRequest(
            call_id="call-conv-4",
            tool_code="unit_converter",
            arguments={"value": 1, "from_unit": "parsec", "to_unit": "m"},
            conversation_id="conv-test",
            trace_id="trace-test",
        ),
        descriptor,
    )
    assert result.status == "error"
    assert result.error_code == "UNSUPPORTED_UNIT"
