package com.buukle.agent.capability.skill.dtvo.enums;

/**
 * 单个副本在一轮自动同步中的结果。
 *
 * <p>存在的意义：自动同步「没效果」时最需要回答的是「为什么这个技能没跟上」，
 * 而原先 {@code syncFromOrigin} 对所有情况只返回 boolean（true=同步、false=跳过），
 * 源被删、取消公开、版本未领先、条件更新失败这四种完全不同的原因在日志里长得一模一样。
 *
 * <p>非 UPDATED 的取值会原样写进 {@code capability_skill_sync_run.detail}，
 * 管理端可直接看到「哪个副本因为什么没同步」。
 */
public enum SkillSyncOutcome {

    /** 已把源内容同步到副本 */
    UPDATED,

    /** 源 skill 已被删除（逻辑删），副本永远停在旧内容 */
    SOURCE_GONE,

    /** 源 skill 已取消公开，副本不再跟随 */
    NOT_PUBLIC,

    /** 源版本没有领先于副本已同步的版本，无需同步（这是绝大多数副本的常态） */
    VERSION_NOT_AHEAD,

    /** 条件 UPDATE 影响 0 行：origin_version 被并发改动，本轮按未同步计 */
    CONCURRENT_UPDATE
}
