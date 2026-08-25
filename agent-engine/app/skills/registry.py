import logging
import time

import httpx

from app.config import Settings
from app.skills.schemas import SkillDescriptor

logger = logging.getLogger(__name__)


class SkillRegistryClient:
    """从 Java 治理中心渐进式发现技能。

    - list_meta: 只拉元数据层（content=None），带 TTL 缓存，用于每轮路由匹配；
    - get_detail: 命中后才拉全文（content），同样带 TTL 缓存。
    Java 不可用时降级为空列表（普通对话不受影响）。
    """

    def __init__(self, settings: Settings) -> None:
        self.base_url = settings.skill_registry_url.rstrip("/")
        self.timeout = settings.skill_registry_timeout_seconds
        self.ttl_seconds = settings.skill_meta_cache_seconds
        self._meta: dict[int, tuple[float, list[SkillDescriptor]]] = {}
        self._detail: dict[tuple[int, str], tuple[float, SkillDescriptor | None]] = {}

    async def list_meta(self, tenant_id: int = 1) -> list[SkillDescriptor]:
        now = time.monotonic()
        cached = self._meta.get(tenant_id)
        if cached and now - cached[0] < self.ttl_seconds:
            return cached[1]
        skills: list[SkillDescriptor] = []
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(f"{self.base_url}", headers={"X-Tenant-Id": str(tenant_id)})
                response.raise_for_status()
                body = response.json()
                if body.get("code") != 0:
                    raise ValueError(body.get("msg", "skill registry error"))
                for item in body.get("data", []):
                    skill = SkillDescriptor.model_validate(item)
                    if skill.enabled:
                        skills.append(skill)
        except Exception as exc:
            logger.warning("skill meta registry unavailable, fallback empty: %s", exc)
        self._meta[tenant_id] = (now, skills)
        return skills

    async def get_detail(self, code: str, tenant_id: int = 1) -> SkillDescriptor | None:
        key = (tenant_id, code)
        now = time.monotonic()
        cached = self._detail.get(key)
        if cached and now - cached[0] < self.ttl_seconds:
            return cached[1]
        detail: SkillDescriptor | None = None
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(
                    f"{self.base_url}/{code}",
                    headers={"X-Tenant-Id": str(tenant_id)},
                )
                response.raise_for_status()
                body = response.json()
                if body.get("code") == 0 and body.get("data"):
                    detail = SkillDescriptor.model_validate(body["data"])
        except Exception as exc:
            logger.warning("skill detail unavailable code=%s: %s", code, exc)
        self._detail[key] = (now, detail)
        return detail
