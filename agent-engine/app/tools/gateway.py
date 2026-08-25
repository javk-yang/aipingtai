import asyncio
import time
from typing import Any

from jsonschema import FormatChecker, ValidationError, validate

from app.config import Settings
from app.tools.builtin import BUILTIN_HANDLERS
from app.tools.errors import ToolGatewayError
from app.tools.mcp_client import McpClientAdapter
from app.tools.registry import ToolRegistryClient
from app.tools.schemas import ToolCallRequest, ToolCallResult, ToolDescriptor


class ToolGateway:
    """统一工具入口：发现、参数校验、超时隔离和结果规约。"""

    def __init__(self, settings: Settings) -> None:
        self.registry = ToolRegistryClient(settings)
        self.mcp = McpClientAdapter()

    async def list_tools(self, tenant_id: int = 1) -> list[ToolDescriptor]:
        return await self.registry.list_tools(tenant_id)

    async def execute(
        self,
        request: ToolCallRequest,
        descriptor: ToolDescriptor,
    ) -> ToolCallResult:
        started = time.perf_counter()
        try:
            self._validate(request.arguments, descriptor.input_schema)
            result = await asyncio.wait_for(
                self._dispatch(descriptor, request.arguments),
                timeout=descriptor.timeout_ms / 1000,
            )
            if descriptor.output_schema:
                self._validate(result, descriptor.output_schema, output=True)
            return ToolCallResult(
                call_id=request.call_id,
                tool_code=request.tool_code,
                status="success",
                result=result,
                duration_ms=self._elapsed(started),
            )
        except TimeoutError:
            return ToolCallResult(
                call_id=request.call_id,
                tool_code=request.tool_code,
                status="timeout",
                error_code="TOOL_TIMEOUT",
                error_message=f"工具执行超过 {descriptor.timeout_ms}ms",
                duration_ms=self._elapsed(started),
            )
        except ToolGatewayError as exc:
            return ToolCallResult(
                call_id=request.call_id,
                tool_code=request.tool_code,
                status="error",
                error_code=exc.code,
                error_message=exc.message,
                duration_ms=self._elapsed(started),
            )
        except Exception as exc:
            return ToolCallResult(
                call_id=request.call_id,
                tool_code=request.tool_code,
                status="error",
                error_code="TOOL_EXECUTION_ERROR",
                error_message=str(exc),
                duration_ms=self._elapsed(started),
            )

    async def _dispatch(self, descriptor: ToolDescriptor, arguments: dict[str, Any]) -> Any:
        if descriptor.executor_type == "builtin":
            handler = BUILTIN_HANDLERS.get(descriptor.code)
            if handler is None:
                raise ToolGatewayError("TOOL_NOT_FOUND", f"内置工具不存在: {descriptor.code}")
            return await handler(arguments)
        return await self.mcp.call(descriptor, arguments)

    def _validate(self, value: Any, schema: dict[str, Any], output: bool = False) -> None:
        try:
            validate(value, schema, format_checker=FormatChecker())
        except ValidationError as exc:
            code = "INVALID_TOOL_RESULT" if output else "INVALID_TOOL_ARGUMENTS"
            prefix = "工具结果" if output else "工具参数"
            raise ToolGatewayError(code, f"{prefix}校验失败: {exc.message}") from exc

    def _elapsed(self, started: float) -> int:
        return max(0, int((time.perf_counter() - started) * 1000))
