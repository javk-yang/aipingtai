package com.agentforge.agent.knowledge.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** knowledge_doc 表实体：知识库文档元数据（向量索引在 Python 侧）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_doc")
public class KnowledgeDoc extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;
    private String title;
    /** 原始正文，用于后续编辑和重索引。 */
    private String content;
    private Integer chunkCount;
    /** 0 索引中 1 就绪 2 失败 */
    private Integer status;
}
