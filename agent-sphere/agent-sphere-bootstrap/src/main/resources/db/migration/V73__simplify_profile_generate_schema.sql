-- V73：岗位画像生成（profile_generate）改为精简后的 4 维度（不含用人偏好）。
--
-- 背景：原 9 区块画像维度重叠，Bole 已收敛为 hard/skills/target/experience 四个互不重叠的维度，
-- 并移除概述(aiSummary)/搜索词库(search)/独立关键词(keywords)。**用人偏好(preference)不由 AI 生成**，
-- 它只从客户「用人偏好」引入（Bole 的「引用客户偏好」），避免 AI 凭空编造偏好。
-- 本迁移：
--   1) 覆盖历史库中所有 profile_generate 行的 output_schema 与 active prompt 的 prompt_system
--      （平台 system 行 + 各用户私有副本）；input_schema 不变（V52 已补全）。
--   2) 更新新用户初始化用的 user.resource-template（按 businessType 定位元素，保持数组顺序）。
-- 幂等：重复执行结果一致（按 business_type 无条件覆盖为新值）。
DO $$
DECLARE
    new_schema jsonb := '{"type":"object","required":["hard","skills","target","experience"],"properties":{"positionId":{"type":"integer"},"hard":{"type":"object","description":"硬性门槛（必要条件）","properties":{"degree":{"type":"string","description":"学历字典码"},"yearsMin":{"type":"integer","description":"工作年限下限"},"yearsMax":{"type":"integer","description":"工作年限上限"},"industry":{"type":"string","description":"行业"},"background":{"type":"string","description":"背景"},"excludeWords":{"type":"array","items":{"type":"string"},"description":"排除词：命中即否决"}}},"skills":{"type":"object","description":"技能","properties":{"core":{"type":"array","items":{"type":"string"},"description":"必备技能"},"plus":{"type":"array","items":{"type":"string"},"description":"加分技能"}}},"target":{"type":"object","description":"对标目标公司","properties":{"tier":{"type":"string","description":"目标梯队"},"level":{"type":"string","description":"目标职级"},"companies":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"tier":{"type":"string"},"fit":{"type":"string"},"reason":{"type":"string"}}}}}},"experience":{"type":"object","description":"经验与稳定性","properties":{"manageYears":{"type":"string","description":"管理年限"},"stability":{"type":"string","description":"稳定度"}}}}}'::jsonb;
    new_system text := '你是资深招聘顾问。根据职位基础信息 JSON (input) 生成标准化的岗位画像，只包含 4 个互不重叠的维度：hard(硬性门槛：学历degree、年限区间yearsMin/yearsMax、行业industry、背景background、排除词excludeWords)、skills(技能：必备core、加分plus)、target(对标公司：梯级tier、职级level、公司清单companies)、experience(经验与稳定性：管理年限manageYears、稳定度stability)。注意：城市/薪资等岗位属性不属于画像，不要输出；概述/摘要不要输出；**用人偏好不由本场景生成**，不要输出 preference。input 可能包含：clientName、title、jd、department、jobCategory、workCity、country、salaryMin~salaryMax 等职位基础信息。请严格按 JSON Schema 输出，只输出 JSON，不要额外说明。';
    tmpl text;
    r RECORD;
BEGIN
    FOR r IN
        SELECT id, active_prompt_id
        FROM agent_completions
        WHERE business_type = 'profile_generate'
          AND delete_flag = 0
    LOOP
        UPDATE agent_completions
           SET output_schema = new_schema,
               updated_at    = NOW()
         WHERE id = r.id;

        IF r.active_prompt_id IS NOT NULL THEN
            UPDATE agent_completions_prompt
               SET prompt_system = new_system,
                   updated_at    = NOW()
             WHERE completions_id = r.id
               AND id             = r.active_prompt_id
               AND delete_flag    = 0;
        END IF;
    END LOOP;

    SELECT config_value INTO tmpl
      FROM agent_system_config
     WHERE config_key = 'user.resource-template';

    IF tmpl IS NOT NULL AND tmpl LIKE '%profile_generate%' THEN
        UPDATE agent_system_config
           SET config_value = (
                   SELECT jsonb_agg(
                              CASE
                                  WHEN elem ->> 'businessType' = 'profile_generate'
                                      THEN jsonb_set(
                                               jsonb_set(elem, '{promptSystem}', to_jsonb(new_system)),
                                               '{outputSchema}', to_jsonb(new_schema))
                                  ELSE elem
                              END
                              ORDER BY ord)
                     FROM jsonb_array_elements(tmpl::jsonb) WITH ORDINALITY AS t(elem, ord)
               )::text,
               updated_at = NOW()
         WHERE config_key = 'user.resource-template';
    END IF;
END $$;
