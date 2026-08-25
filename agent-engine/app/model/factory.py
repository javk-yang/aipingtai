from dataclasses import dataclass

from app.config import Settings
from app.model.deterministic import DeterministicModel
from app.model.openai_compatible import OpenAICompatibleModel, normalize_provider


@dataclass(frozen=True)
class ModelBundle:
    model: object
    provider: str


def create_model(settings: Settings, config: dict | None = None) -> ModelBundle:
    """模型工厂 seam。

    - config 为 None：使用 settings 默认（当前为 deterministic）。
    - config["provider"] == "deterministic"：确定性回退模型（无外部 Key 可演示）。
    - config["provider"] == "openai" | "openai-compatible"：真实大模型，
      按 base_url / api_key / model 调用 OpenAI 兼容端点。
    """
    if config is None:
        provider = normalize_provider(settings.model_provider)
        if provider == "deterministic":
            return ModelBundle(
                model=DeterministicModel(settings.model_name, settings.stream_chunk_size),
                provider=provider,
            )
        return ModelBundle(
            model=OpenAICompatibleModel(
                model_name=settings.model_name,
                base_url=settings.model_base_url,
                api_key=settings.model_api_key,
            ),
            provider=provider,
        )

    provider = normalize_provider(config.get("provider", "deterministic"))
    if provider == "deterministic":
        return ModelBundle(
            model=DeterministicModel(settings.model_name, settings.stream_chunk_size),
            provider="deterministic",
        )

    # OpenAI-compatible：真实大模型
    return ModelBundle(
        model=OpenAICompatibleModel(
            model_name=str(config.get("model") or settings.model_name),
            base_url=str(config.get("base_url") or settings.model_base_url),
            api_key=str(config.get("api_key") or settings.model_api_key),
            temperature=float(config.get("temperature", 0.7) or 0.7),
            max_tokens=int(config.get("max_tokens", 1024) or 1024),
        ),
        provider=provider,
    )
