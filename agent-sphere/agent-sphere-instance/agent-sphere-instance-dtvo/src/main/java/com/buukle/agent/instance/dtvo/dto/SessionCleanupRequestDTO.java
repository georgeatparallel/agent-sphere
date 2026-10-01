package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 手动触发历史会话清理的请求。
 *
 * <p>{@code dryRun} 默认 true：清理是<b>不可逆硬删</b>，接口设计上必须让人先看清单再删。
 */
@Data
public class SessionCleanupRequestDTO implements Serializable {

    /** 只统计「将会删除多少行」而不落刀；默认 true */
    private boolean dryRun = true;
}
