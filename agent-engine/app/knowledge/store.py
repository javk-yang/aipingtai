"""P12 RAG 知识库（PG 降级方案）。

环境无 PostgreSQL + pgvector（且无 brew 可安装），采用纯 Python 本地索引：
- 文档解析：纯文本 / Markdown
- 分块：段落优先，超长按句切，块上限 300 字符
- 向量化：字符 bigram + 词 TF 归一化（零外部依赖，特征维度 ~2000）
- 检索：余弦相似度 + 元数据过滤 + 溯源（返回文档标题 + 块文本 + 分数）

目标架构（backend/sql/02-postgres-vector.sql）保留不动，此模块是受限环境下的可运行等价实现。
切换 pgvector 时只需替换 store 的 index/search 两个方法。
"""

from __future__ import annotations

import json
import math
import re
import threading
import uuid
from pathlib import Path
from typing import Any

DATA_DIR = Path(__file__).resolve().parent.parent.parent / "data" / "knowledge"
MAX_CHUNK_CHARS = 300
TOP_K_DEFAULT = 3

_SENT_SPLIT = re.compile(r"(?<=[。！？!?；;])\s*")
_PARA_SPLIT = re.compile(r"\n\s*\n")

_lock = threading.Lock()


class KnowledgeStore:
    """文档分块索引 + 检索（进程内单例，文件持久化）。"""

    def __init__(self, data_dir: Path = DATA_DIR) -> None:
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self._docs: dict[str, dict[str, Any]] = {}
        self._load()

    # ------------------------------------------------------------------
    # 文档管理
    # ------------------------------------------------------------------
    def index_text(self, title: str, text: str, doc_id: str | None = None) -> dict[str, Any]:
        doc_id = doc_id or uuid.uuid4().hex[:32]
        chunks = self._chunk_text(text)
        doc = {
            "doc_id": doc_id,
            "title": title,
            "chunks": [
                {
                    "id": f"{doc_id}:{idx}",
                    "text": chunk,
                    "embed": _embed(chunk),
                }
                for idx, chunk in enumerate(chunks)
            ],
            "chunk_count": len(chunks),
        }
        with _lock:
            self._docs[doc_id] = doc
            self._persist(doc_id, doc)
        return {"doc_id": doc_id, "title": title, "chunk_count": len(chunks)}

    def get_doc(self, doc_id: str) -> dict[str, Any] | None:
        with _lock:
            return self._docs.get(doc_id)

    def update_text(self, doc_id: str, title: str, text: str) -> dict[str, Any] | None:
        if self.get_doc(doc_id) is None:
            return None
        return self.index_text(title, text, doc_id)

    def delete(self, doc_id: str) -> bool:
        with _lock:
            existed = self._docs.pop(doc_id, None) is not None
            path = self.data_dir / f"{doc_id}.json"
            if path.exists():
                path.unlink()
        return existed

    def list_docs(self) -> list[dict[str, Any]]:
        with _lock:
            return [
                {
                    "doc_id": doc["doc_id"],
                    "title": doc["title"],
                    "chunk_count": doc["chunk_count"],
                }
                for doc in self._docs.values()
            ]

    def doc_count(self) -> int:
        with _lock:
            return len(self._docs)

    # ------------------------------------------------------------------
    # 检索
    # ------------------------------------------------------------------
    def search(
        self,
        query: str,
        top_k: int = TOP_K_DEFAULT,
        doc_ids: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        query_embed = _embed(query)
        if not query_embed:
            return []
        scored: list[tuple[float, dict[str, Any], dict[str, Any]]] = []
        allowed_docs = set(doc_ids or [])
        with _lock:
            for doc in self._docs.values():
                if allowed_docs and doc["doc_id"] not in allowed_docs:
                    continue
                for chunk in doc["chunks"]:
                    score = _cosine(query_embed, chunk["embed"])
                    scored.append((score, chunk, doc))
        scored.sort(key=lambda item: item[0], reverse=True)
        results = []
        for score, chunk, doc in scored[:top_k]:
            if score <= 0:
                continue
            results.append(
                {
                    "doc_id": doc["doc_id"],
                    "title": doc["title"],
                    "chunk_id": chunk["id"],
                    "text": chunk["text"],
                    "score": round(score, 4),
                }
            )
        return results

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------
    def _load(self) -> None:
        for path in self.data_dir.glob("*.json"):
            try:
                doc = json.loads(path.read_text(encoding="utf-8"))
                self._docs[doc["doc_id"]] = doc
            except (json.JSONDecodeError, KeyError, OSError):
                continue

    def _persist(self, doc_id: str, doc: dict[str, Any]) -> None:
        path = self.data_dir / f"{doc_id}.json"
        path.write_text(
            json.dumps(doc, ensure_ascii=False), encoding="utf-8"
        )

    def _chunk_text(self, text: str) -> list[str]:
        text = text.strip()
        if not text:
            return []
        paragraphs = [p.strip() for p in _PARA_SPLIT.split(text) if p.strip()]
        chunks: list[str] = []
        for paragraph in paragraphs:
            if len(paragraph) <= MAX_CHUNK_CHARS:
                chunks.append(paragraph)
                continue
            # 超长段落按句切，句子再超长按硬切
            sentences = [s for s in _SENT_SPLIT.split(paragraph) if s.strip()]
            buf = ""
            for sentence in sentences:
                if len(sentence) > MAX_CHUNK_CHARS:
                    if buf:
                        chunks.append(buf)
                        buf = ""
                    for start in range(0, len(sentence), MAX_CHUNK_CHARS):
                        chunks.append(sentence[start:start + MAX_CHUNK_CHARS])
                    continue
                if len(buf) + len(sentence) > MAX_CHUNK_CHARS:
                    chunks.append(buf)
                    buf = sentence
                else:
                    buf += sentence
            if buf:
                chunks.append(buf)
        return chunks


# ---------------------------------------------------------------------------
# 向量化与相似度（纯 Python）
# ---------------------------------------------------------------------------

_STOP_CHARS = set(" \t\r\n，。！？；：、,.!?;:()（）[]【】\"'\"'《》<>")


def _tokenize(text: str) -> list[str]:
    """字符 bigram 分词（中文友好，对常见停用字符去重后提取）。"""
    cleaned = "".join(ch for ch in text if ch not in _STOP_CHARS)
    tokens: list[str] = []
    for i in range(len(cleaned) - 1):
        tokens.append(cleaned[i:i + 2])
    if len(cleaned) == 1:
        tokens.append(cleaned)
    return tokens


def _embed(text: str) -> dict[str, float]:
    """TF 归一化特征向量：{bigram: 频次/长度}。"""
    tokens = _tokenize(text)
    total = max(len(tokens), 1)
    counts: dict[str, int] = {}
    for token in tokens:
        counts[token] = counts.get(token, 0) + 1
    return {token: count / total for token, count in counts.items()}


def _cosine(a: dict[str, float], b: dict[str, float]) -> float:
    if not a or not b:
        return 0.0
    if len(a) > len(b):
        a, b = b, a
    dot = sum(value * b.get(token, 0.0) for token, value in a.items())
    norm_a = math.sqrt(sum(v * v for v in a.values()))
    norm_b = math.sqrt(sum(v * v for v in b.values()))
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)


# 进程内单例
_store: KnowledgeStore | None = None


def get_store() -> KnowledgeStore:
    global _store
    if _store is None:
        _store = KnowledgeStore()
    return _store
