package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SkillVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String definition;
    private String status;
    private String visibility;
    private Long originSkillId;
    private Integer installCount;
    private Integer version;
    private Integer originVersion;
    private Boolean autoUpdate;
    /** 最后一次与源头对齐的时间（展示快照）；安装时即为安装时间 */
    private String syncedAt;
    /** 最后一次同步到的源头版本号（展示快照） */
    private Integer syncedFromVersion;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
