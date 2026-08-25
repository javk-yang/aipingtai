package com.agentforge.agent.knowledge.service;

import com.agentforge.agent.knowledge.dto.KnowledgeCreateRequest;
import com.agentforge.agent.knowledge.dto.KnowledgeDetailResponse;
import com.agentforge.agent.knowledge.dto.KnowledgeResponse;
import com.agentforge.agent.knowledge.dto.KnowledgeSearchRequest;
import com.agentforge.agent.knowledge.dto.KnowledgeSearchResult;
import com.agentforge.agent.knowledge.dto.KnowledgeUpdateRequest;
import com.agentforge.agent.knowledge.entity.KnowledgeDoc;
import com.agentforge.agent.knowledge.mapper.KnowledgeDocMapper;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 知识库治理服务：文档上传、编辑重索引、列表、删除、检索。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeEngineClient engineClient;

    @Transactional
    public KnowledgeResponse create(Long tenantId, KnowledgeCreateRequest request) {
        String docId = UUID.randomUUID().toString().replace("-", "");
        int chunkCount = engineClient.index(docId, request.getTitle(), request.getText());

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTenantId(tenantId);
        doc.setDocId(docId);
        doc.setTitle(request.getTitle());
        doc.setContent(request.getText());
        doc.setChunkCount(chunkCount);
        doc.setStatus(1);
        docMapper.insert(doc);
        log.info("knowledge doc indexed tenant={} docId={} title={} chunks={}",
                tenantId, docId, request.getTitle(), chunkCount);
        return toResponse(doc);
    }

    @Transactional
    public KnowledgeResponse update(Long tenantId, String docId, KnowledgeUpdateRequest request) {
        KnowledgeDoc doc = require(tenantId, docId);
        int chunkCount = engineClient.index(docId, request.getTitle(), request.getText());
        doc.setTitle(request.getTitle());
        doc.setContent(request.getText());
        doc.setChunkCount(chunkCount);
        doc.setStatus(1);
        docMapper.updateById(doc);
        log.info("knowledge doc reindexed tenant={} docId={} title={} chunks={}",
                tenantId, docId, request.getTitle(), chunkCount);
        return toResponse(doc);
    }

    @Transactional
    public KnowledgeResponse reindex(Long tenantId, String docId) {
        KnowledgeDoc doc = require(tenantId, docId);
        if (doc.getContent() == null || doc.getContent().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文档没有保存原始正文，无法重索引");
        }
        int chunkCount = engineClient.index(docId, doc.getTitle(), doc.getContent());
        doc.setChunkCount(chunkCount);
        doc.setStatus(1);
        docMapper.updateById(doc);
        return toResponse(doc);
    }

    public List<KnowledgeResponse> list(Long tenantId) {
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getTenantId, tenantId)
                        .orderByDesc(KnowledgeDoc::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    public KnowledgeDetailResponse getDetail(Long tenantId, String docId) {
        KnowledgeDoc doc = require(tenantId, docId);
        return new KnowledgeDetailResponse(doc.getDocId(), doc.getTitle(), doc.getContent(),
                doc.getChunkCount(), doc.getStatus(), doc.getCreatedAt(), doc.getUpdatedAt());
    }

    @Transactional
    public void delete(Long tenantId, String docId) {
        int rows = docMapper.delete(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getTenantId, tenantId)
                .eq(KnowledgeDoc::getDocId, docId));
        if (rows == 0) {
            throw new BizException(ErrorCode.KNOWLEDGE_DOC_NOT_FOUND);
        }
        engineClient.delete(docId);
        log.info("knowledge doc deleted tenant={} docId={}", tenantId, docId);
    }

    public KnowledgeSearchResult search(Long tenantId, KnowledgeSearchRequest request) {
        return engineClient.search(request.getQuery(), request.getTopK() == null ? 3 : request.getTopK());
    }

    private KnowledgeDoc require(Long tenantId, String docId) {
        KnowledgeDoc doc = docMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getTenantId, tenantId)
                .eq(KnowledgeDoc::getDocId, docId));
        if (doc == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_DOC_NOT_FOUND);
        }
        return doc;
    }

    private KnowledgeResponse toResponse(KnowledgeDoc doc) {
        return new KnowledgeResponse(doc.getDocId(), doc.getTitle(), doc.getChunkCount(),
                doc.getStatus(), doc.getCreatedAt());
    }
}
