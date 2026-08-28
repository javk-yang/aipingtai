import logging
import uuid

from fastapi import FastAPI, Header, Request
from fastapi.responses import StreamingResponse

from app.config import get_settings
from app.graph.agent_graph import AgentGraph
from app.knowledge.store import get_store
from app.logging_config import configure_logging, trace_id_var
from app.model.factory import create_model
from app.schemas import ChatStreamRequest, EngineEvent

settings = get_settings()
configure_logging(settings.log_level)
logger = logging.getLogger(__name__)
app = FastAPI(title=settings.app_name, version="0.1.0")
graph = AgentGraph(settings)


@app.middleware("http")
async def trace_middleware(request: Request, call_next):
    trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex
    token = trace_id_var.set(trace_id)
    try:
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response
    finally:
        trace_id_var.reset(token)


@app.get("/health")
async def health() -> dict:
    return {
        "status": "up",
        "engine": "langgraph",
        "provider": graph.provider,
        "model": graph.model.model_name,
    }


@app.post("/v1/chat/stream")
async def chat_stream(
    body: ChatStreamRequest,
    request: Request,
    x_trace_id: str | None = Header(default=None),
):
    trace_id = x_trace_id or body.trace_id or trace_id_var.get()

    async def event_generator():
        try:
            bundle = create_model(settings, body.llm_config)
            request_model_name = bundle.model.model_name
            yield EngineEvent(
                type="message_start",
                data={"model": request_model_name},
                trace_id=trace_id,
            ).to_ndjson()
            reply = ""
            async for graph_event in graph.stream(
                body.prompt,
                body.conversation_id,
                trace_id,
                body.tenant_id,
                model_config=body.llm_config,
                agent_config=body.agent_config.model_dump() if body.agent_config else None,
            ):
                event_type = graph_event["type"]
                event_data = graph_event["data"]
                if event_type in {
                    "tool_call_start", "tool_call_result", "tool_call_error",
                    "skill_call_start", "skill_call_result", "skill_call_error",
                    "reasoning",
                }:
                    yield EngineEvent(
                        type=event_type,
                        data=event_data,
                        trace_id=trace_id,
                    ).to_ndjson()
                elif event_type == "assistant_final":
                    reply = str(event_data.get("content", ""))

            for start in range(0, len(reply), settings.stream_chunk_size):
                if await request.is_disconnected():
                    logger.info("client disconnected conversation=%s", body.conversation_id)
                    return
                yield EngineEvent(
                    type="content_delta",
                    data={"delta": reply[start:start + settings.stream_chunk_size]},
                    trace_id=trace_id,
                ).to_ndjson()
            yield EngineEvent(
                type="message_done",
                data={
                    "model": request_model_name,
                    "token_input": max(1, len(body.prompt) // 2),
                    "token_output": max(1, len(reply) // 2),
                },
                trace_id=trace_id,
            ).to_ndjson()
        except Exception as exc:
            logger.exception("agent stream failed conversation=%s", body.conversation_id)
            yield EngineEvent(
                type="error",
                data={"code": "ENGINE_ERROR", "message": str(exc)},
                trace_id=trace_id,
            ).to_ndjson()

    return StreamingResponse(event_generator(), media_type="application/x-ndjson")


# ---------------------------------------------------------------------------
# P12 知识库（Java 管理端中转 / 工具检索共用此本地索引）
# ---------------------------------------------------------------------------

from pydantic import BaseModel, Field  # noqa: E402


class KnowledgeIndexRequest(BaseModel):
    doc_id: str | None = Field(default=None, max_length=32)
    title: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1)


class KnowledgeSearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=512)
    top_k: int = Field(default=3, ge=1, le=10)


@app.post("/api/knowledge/index")
async def knowledge_index(body: KnowledgeIndexRequest) -> dict:
    store = get_store()
    result = store.index_text(body.title, body.text, body.doc_id)
    return {"status": "ok", **result}


@app.post("/api/knowledge/search")
async def knowledge_search(body: KnowledgeSearchRequest) -> dict:
    results = get_store().search(body.query, body.top_k)
    return {"status": "ok", "results": results}


@app.get("/api/knowledge/docs")
async def knowledge_docs() -> dict:
    return {"status": "ok", "docs": get_store().list_docs(), "count": get_store().doc_count()}


@app.put("/api/knowledge/docs/{doc_id}")
async def knowledge_update(doc_id: str, body: KnowledgeIndexRequest) -> dict:
    result = get_store().update_text(doc_id, body.title, body.text)
    if result is None:
        return {"status": "not_found", "doc_id": doc_id}
    return {"status": "ok", **result}

@app.delete("/api/knowledge/docs/{doc_id}")
async def knowledge_delete(doc_id: str) -> dict:
    deleted = get_store().delete(doc_id)
    return {"status": "ok", "deleted": deleted}
