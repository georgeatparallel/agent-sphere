package com.buukle.agent.capability.skill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;

public interface CapabilitySkillService extends IService<CapabilitySkill>, CapabilitySkillSpi {

    /**
     * 按源版本同步一个已安装副本，返回<b>具体结果</b>（含跳过的原因）。
     *
     * <p>同步成功时会一并写入副本的 {@code synced_at} 与 {@code synced_from_version}
     * 两个展示快照字段；{@code origin_version} 仍按条件 UPDATE 推进，两者取值一致但互不影响。
     *
     * @param copy 已安装副本（origin_skill_id 非空、auto_update=true）
     */
    SkillSyncOutcome syncWithOutcome(CapabilitySkill copy);

    /**
     * 按源版本同步一个已安装副本（只关心成不成功时用这个）。
     *
     * @deprecated 语义太粗：跳过时无法区分原因。改用 {@link #syncWithOutcome}。
     */
    @Deprecated
    boolean syncFromOrigin(CapabilitySkill copy);
}