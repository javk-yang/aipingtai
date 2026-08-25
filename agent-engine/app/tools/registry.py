import logging

import httpx

from app.config import Settings
from app.tools.builtin import BUILTIN_DESCRIPTORS
from app.tools.schemas import ToolDescriptor

logger = logging.getLogger(__name__)


class ToolRegistryClient:
    """从 Java 治理中心发现工具；Java 不可用时仅降级到安全内置工具。"""

    def __init__(self, settings: Settings) -> None:
        self.url = settings.tool_registry_url
        self.timeout = settings.tool_registry_timeout_seconds

    async def list_tools(self, tenant_id: int = 1) -> list[ToolDescriptor]:
        merged = {tool.code: tool for tool in BUILTIN_DESCRIPTORS}
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(self.url, headers={"X-Tenant-Id": str(tenant_id)})
                response.raise_for_status()
                body = response.json()
                if body.get("code") != 0:
                    raise ValueError(body.get("msg", "tool registry error"))
                for item in body.get("data", []):
                    tool = ToolDescriptor.model_validate(item)
                    if tool.enabled:
                        merged[tool.code] = tool
        except Exception as exc:
            logger.warning("tool registry unavailable, using builtin tools: %s", exc)
        return list(merged.values())
