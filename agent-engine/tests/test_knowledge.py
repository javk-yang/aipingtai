"""P12 知识库（降级方案）测试：分块、索引、检索、溯源。"""

import pytest

from app.knowledge.store import KnowledgeStore, _embed, _cosine


@pytest.fixture()
def store(tmp_path) -> KnowledgeStore:
    return KnowledgeStore(data_dir=tmp_path)


def test_chunk_long_text_by_paragraph(store: KnowledgeStore) -> None:
    text = ("第一段内容。" * 30) + "\n\n" + "第二段简短。"
    doc = store.index_text("测试文档", text, doc_id="doc-chunk")
    assert doc["chunk_count"] >= 2


def test_search_finds_relevant_chunk(store: KnowledgeStore) -> None:
    store.index_text(
        "员工手册",
        "员工每月享有两天带薪年假。请假需提前一天在系统提交申请。"
        "入职满一年后年假天数增加至五天。",
        doc_id="doc-handbook",
    )
    store.index_text(
        "产品说明",
        "AgentForge 是一个低代码智能体开发平台，支持技能、工具与知识库。",
        doc_id="doc-product",
    )
    results = store.search("年假有几天", top_k=2)
    assert results, "应能检索到结果"
    top = results[0]
    assert top["title"] == "员工手册"
    assert "年假" in top["text"]
    assert top["doc_id"] == "doc-handbook"
    assert top["score"] > 0


def test_search_no_hit(store: KnowledgeStore) -> None:
    store.index_text("产品说明", "AgentForge 智能体平台。", doc_id="doc-x")
    results = store.search("量子计算退相干", top_k=3)
    assert results == []


def test_delete_doc(store: KnowledgeStore) -> None:
    store.index_text("临时", "将被删除的内容。", doc_id="doc-del")
    assert store.delete("doc-del") is True
    assert store.delete("doc-del") is False
    assert store.search("将被删除", top_k=3) == []


def test_cosine_self_similarity() -> None:
    emb = _embed("AgentForge 智能体平台")
    assert _cosine(emb, emb) > 0.999


def test_embed_empty_returns_empty() -> None:
    assert _embed("") == {}
    assert _cosine({}, {}) == 0.0
