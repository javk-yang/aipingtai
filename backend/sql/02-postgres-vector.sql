-- ============================================================
-- AgentForge 向量知识库 · PostgreSQL + pgvector 建表脚本
-- 库名: agentforge_vector    用户: agentforge
-- 职责边界: 本库只承载「知识库/文档/分块/向量检索」,
--           业务数据(用户/会话/消息/订单)仍在 MySQL —— 单一数据源铁律
--
-- 本地安装 (macOS, 无 Docker 环境):
--   brew install postgresql@16 pgvector
--   brew services start postgresql@16
--   psql postgres -c "CREATE USER agentforge WITH PASSWORD 'agentforge123';"
--   psql postgres -c "CREATE DATABASE agentforge_vector OWNER agentforge;"
--   psql -d agentforge_vector -f 02-postgres-vector.sql
-- ============================================================

-- 0. 扩展必须由超级用户执行一次
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. knowledge_base 知识库
--    记录: 元信息 + embedding 模型 + 分块策略
-- ============================================================
CREATE TABLE knowledge_base (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL DEFAULT 1,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    -- 换模型 = 换维度 = 全部向量重建。模型记录在库, 代码从库读取, 不硬编码
    embed_model   VARCHAR(64) NOT NULL DEFAULT 'bge-large-zh-v1.5',
    embed_dim     INT NOT NULL DEFAULT 1024,
    chunk_size    INT NOT NULL DEFAULT 500,      -- 字符数
    chunk_overlap INT NOT NULL DEFAULT 50,
    status        SMALLINT NOT NULL DEFAULT 1,   -- 1 启用 / 0 停用
    created_by    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE knowledge_base IS '知识库: 元信息 + embedding 模型 + 分块策略';
COMMENT ON COLUMN knowledge_base.embed_dim IS '向量维度, 必须与模型输出一致, 列定义靠它决定, 换模型需重建';

-- ============================================================
-- 2. document 文档
--    记录: 源文件 + 解析状态。文件本体放对象存储, 这里只存路径
-- ============================================================
CREATE TABLE document (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL DEFAULT 1,
    kb_id        BIGINT NOT NULL REFERENCES knowledge_base(id),
    file_name    VARCHAR(255) NOT NULL,
    file_path    VARCHAR(512) NOT NULL,          -- 对象存储路径
    file_size    BIGINT NOT NULL DEFAULT 0,
    file_type    VARCHAR(16),                    -- pdf / docx / md / txt / html
    parse_status SMALLINT NOT NULL DEFAULT 0,    -- 0 待解析 / 1 解析中 / 2 成功 / 3 失败
    chunk_count  INT NOT NULL DEFAULT 0,
    error_msg    TEXT,                           -- 解析失败原因, 前端展示用
    created_by   BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE document IS '知识文档: 源文件元信息 + 解析状态机';
CREATE INDEX idx_document_kb ON document (tenant_id, kb_id);

-- ============================================================
-- 3. doc_chunk 文档分块 —— 核心表, 承载向量
--    检索 SQL: 一次查询同时完成 向量相似度 + 元数据过滤
-- ============================================================
CREATE TABLE doc_chunk (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL DEFAULT 1,
    kb_id       BIGINT NOT NULL,                 -- 冗余: 过滤只查 kb_id, 不用 join document
    doc_id      BIGINT NOT NULL,
    seq         INT NOT NULL,                    -- 块在文档内的顺序, 用于还原原文顺序
    content     TEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,          -- 粗算 token 数, 供上下文裁剪
    metadata    JSONB NOT NULL DEFAULT '{}',     -- {"page": 3, "title": "xx", "tag": ["财务"]}
    embedding   vector(1024),                    -- 维度必须与 embed_dim 一致, 换模型需 ALTER
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE doc_chunk IS '文档分块: 内容 + 向量 + 元数据, 检索的物理主体';

-- 唯一约束: 同文档内 seq 唯一 → 重传分块幂等 (upsert 的安全锚点)
CREATE UNIQUE INDEX uk_chunk_doc_seq ON doc_chunk (tenant_id, doc_id, seq);

-- 元数据过滤索引: WHERE kb_id = ? AND 相似度排序, 先过滤再算向量
CREATE INDEX idx_chunk_kb ON doc_chunk (tenant_id, kb_id);

-- 核心: HNSW 向量索引
-- m=16 是默认值: 图连接稀疏, 内存省, 召回率在千万级会掉点
-- 调大到 32: 连接更密, 召回更好, 但内存近乎翻倍、建索引时间上升
-- ef_construction=128: 建索引质量更高, 换取检索时更稳的召回
-- 注意: 检索期参数 ef_search 是会话级 SET, 不进建表语句 (常见坑)
CREATE INDEX idx_chunk_embedding ON doc_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);

-- 备选: IVFFlat 索引 (数据量 < 100w 且频繁更新时, 建索引快得多)
-- CREATE INDEX idx_chunk_embedding_ivf ON doc_chunk
--     USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ============================================================
-- 4. embed_job embedding 任务 —— 异步解析管道
--    文档上传 → 解析 → 分块 → 逐批 embedding → 写 doc_chunk
-- ============================================================
CREATE TABLE embed_job (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL DEFAULT 1,
    kb_id        BIGINT NOT NULL,
    doc_id       BIGINT NOT NULL,
    status       SMALLINT NOT NULL DEFAULT 0,    -- 0 排队 / 1 执行中 / 2 成功 / 3 失败
    total_chunks INT NOT NULL DEFAULT 0,
    done_chunks  INT NOT NULL DEFAULT 0,
    error_msg    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
COMMENT ON TABLE embed_job IS 'embedding 异步任务: 分批写入, 可断点续跑';
CREATE INDEX idx_embed_job_status ON embed_job (status, created_at);

-- ============================================================
-- 5. 核心检索 SQL 示例 (P12 将封装为函数/服务)
--    语义 + 元数据 同一条 SQL:
--
-- SELECT c.id, c.content, c.metadata,
--        1 - (c.embedding <=> :query_vec) AS score
-- FROM doc_chunk c
-- WHERE c.tenant_id = 1
--   AND c.kb_id = :kb_id
--   AND c.metadata->>'page' = '3'          -- 可选: 元数据过滤
-- ORDER BY c.embedding <=> :query_vec
-- LIMIT 5;
--
-- 检索前设置会话级召回参数 (每个连接都要设, 不是全局配置):
--   SET hnsw.ef_search = 100;   -- 越大召回越高, 延迟也越高
--   SET LOCAL hnsw.ef_search = 100;  -- 事务内生效, 推荐
-- ============================================================
