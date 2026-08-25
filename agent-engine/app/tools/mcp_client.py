from contextlib import AsyncExitStack
from typing import Any

from mcp import Client, StdioServerParameters

from app.tools.errors import ToolGatewayError
from app.tools.schemas import ToolDescriptor


class McpClientAdapter:
    """MCP SDK v2 适配器：屏蔽 stdio/Streamable HTTP 的连接差异。"""

    async def call(self, descriptor: ToolDescriptor, arguments: dict[str, Any]) -> Any:
        config = descriptor.executor_config
        async with AsyncExitStack() as stack:
            client = await stack.enter_async_context(Client(self._transport(descriptor, config)))
            result = await client.call_tool(descriptor.code, arguments)
            if getattr(result, "is_error", False):
                raise ToolGatewayError("MCP_TOOL_ERROR", self._error_text(result))
            structured = getattr(result, "structured_content", None)
            if structured is not None:
                return structured
            return self._content_value(getattr(result, "content", []))

    def _transport(self, descriptor: ToolDescriptor, config: dict[str, Any]):
        if descriptor.transport == "stdio":
            command = config.get("command")
            if not command:
                raise ToolGatewayError("MCP_CONFIG_ERROR", "stdio MCP Server 缺少 command")
            return StdioServerParameters(
                command=str(command),
                args=[str(item) for item in config.get("args", [])],
                env={str(k): str(v) for k, v in config.get("env", {}).items()} or None,
            )
        url = config.get("url")
        if not url:
            raise ToolGatewayError("MCP_CONFIG_ERROR", "网络 MCP Server 缺少 url")
        # SDK v2 的 URL 入口使用 Streamable HTTP；旧 SSE 端点后续单独加兼容 transport。
        return str(url)

    def _content_value(self, content: list[Any]) -> Any:
        values = []
        for block in content:
            if hasattr(block, "text"):
                values.append(block.text)
            elif hasattr(block, "model_dump"):
                values.append(block.model_dump())
            else:
                values.append(str(block))
        return values[0] if len(values) == 1 else values

    def _error_text(self, result: Any) -> str:
        value = self._content_value(getattr(result, "content", []))
        return str(value or "MCP 工具执行失败")
