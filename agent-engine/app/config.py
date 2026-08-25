from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """引擎集中配置：环境变量前缀统一为 AGENT_ENGINE_。"""

    model_config = SettingsConfigDict(
        env_prefix="AGENT_ENGINE_",
        env_file=".env",
        extra="ignore",
    )

    app_name: str = "AgentForge Engine"
    host: str = "127.0.0.1"
    port: int = 8000
    log_level: str = "INFO"
    model_provider: str = "deterministic"
    model_name: str = "agentforge-dev-model"
    model_base_url: str = "https://api.openai.com/v1"
    model_api_key: str = ""
    request_timeout_seconds: float = Field(default=300.0, gt=0)
    stream_chunk_size: int = Field(default=18, ge=1, le=200)
    tool_registry_url: str = "http://127.0.0.1:8090/internal/tools"
    tool_registry_timeout_seconds: float = Field(default=2.0, gt=0, le=30)
    tool_max_rounds: int = Field(default=3, ge=1, le=10)
    skill_registry_url: str = "http://127.0.0.1:8090/internal/skills"
    skill_registry_timeout_seconds: float = Field(default=2.0, gt=0, le=30)
    skill_meta_cache_seconds: float = Field(default=30.0, gt=0, le=600)


@lru_cache
def get_settings() -> Settings:
    return Settings()
