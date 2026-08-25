package com.agentforge.session.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 更新会话请求 —— 重命名 / 归档
 *
 * 设计决策:
 * status 只暴露 1活跃 / 2归档 两种写操作, 删除走独立的软删接口(带确认),
 * 不在通用 update 里混删, 避免误删路径和归档路径耦合。
 */
@Data
public class ConversationUpdateRequest implements Serializable {

    /** 标题: 重命名 */
    @Size(max = 128, message = "标题过长")
    private String title;

    /** 状态: 1活跃 2归档 (3已删除由软删接口处理) */
    private Integer status;
}
