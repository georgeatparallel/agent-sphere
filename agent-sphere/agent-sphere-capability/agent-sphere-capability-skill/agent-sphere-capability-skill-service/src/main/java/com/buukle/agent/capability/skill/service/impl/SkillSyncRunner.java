package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncRunStatusEnum;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncTriggerEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillSyncRunVO;
import com.buukle.agent.capability.skill.repository.SkillSyncRunMapper;
import com.buukle.agent.capability.skill.repository.SkillSyncRunRowVO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.support.AbstractRecordedTask;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Skill Hub 自动同步的执行体（定时 + 手动共用）。
 *
 * <p><b>修掉的「没效果」根因</b>：原 {@code SkillAutoUpdateSweeper} 的扫描条件带
 * {@code created_at >= now - 7 天}，而副本的 created_at 是<b>安装时间</b> ——
 * 装得超过 7 天的副本<b>永远不再被扫描</b>。现在只按 {@code auto_update=true AND
 * origin_skill_id IS NOT NULL} 筛选，装多久都跟着源更新。
 * （索引 {@code idx_skill_auto_update(auto_update, origin_skill_id)} 正好完整命中。）
 *
 * <p><b>为什么必须有执行记录</b>：这个任务的 logger 被 logback 的
 * {@code com.buukle.agent.capability=WARN} 规则压住，而它唯一的成功日志是 log.info
 * —— 任务跑了还是没跑、扫到几条、同步了几条，光看日志永远无法回答。每轮写一行记录就是答案。
 *
 * <p>锁与异常语义复用 {@link AbstractRecordedTask}，与会话清理同一套骨架。
 */
@Slf4j
@Component
public class SkillSyncRunner extends AbstractRecordedTask<SkillSyncRunVO, String> {

    private static final String LOCK_KEY = "scheduler:skill-auto-update";
    private static final int DEFAULT_LOG_RETENTION_DAYS = 30;
    /** 跳过明细最多记多少条，避免源全下线时把 detail 撑成大 JSON */
    private static final int MAX_DETAIL_ITEMS = 100;
    private static final String TASK_NAME = "Skill auto-update sync";

    private final CapabilitySkillService capabilitySkillService;
    private final SkillSyncRunMapper skillSyncRunMapper;
    private final SystemConfigSpi systemConfigSpi;
    private final Executor syncExecutor;

    public SkillSyncRunner(CapabilitySkillService capabilitySkillService,
                           SkillSyncRunMapper skillSyncRunMapper,
                           SystemConfigSpi systemConfigSpi,
                           RedissonClient redissonClient,
                           @Qualifier("runtimeAsyncExecutor") Executor syncExecutor) {
        super(redissonClient);
        this.capabilitySkillService = capabilitySkillService;
        this.skillSyncRunMapper = skillSyncRunMapper;
        this.systemConfigSpi = systemConfigSpi;
        this.syncExecutor = syncExecutor;
    }

    @Value("${buukle.agent.skill.auto-update-batch-size:100}")
    private int batchSize;

    // ==================== 入口 ====================

    /** 定时入口：固定延迟 5 分钟（原先的间隔保留）。 */
    @Scheduled(fixedDelayString = "${buukle.agent.skill.auto-update-interval:PT5M}")
    public void scheduledSync() {
        submitSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);
    }

    /** 异步提交一轮并立即返回初始记录；前端拿 id 去轮询 {@link #getRun(Long)}。 */
    public SkillSyncRunVO submitSync(String trigger) {
        String operator = currentOperator();
        Long recordId = insertRunning(trigger, operator);
        syncExecutor.execute(() -> runWithLock(trigger, recordId, operator, System.currentTimeMillis()));
        SkillSyncRunRowVO row = skillSyncRunMapper.getRun(recordId, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    /** 同步执行一轮（单测走它，可直接断言扫描条件与记录终态）。 */
    public SkillSyncRunVO runSync(String trigger) {
        String operator = currentOperator();
        Long recordId = insertRunning(trigger, operator);
        return runWithLock(trigger, recordId, operator, System.currentTimeMillis());
    }

    public SkillSyncRunVO getRun(Long id) {
        SkillSyncRunRowVO row = skillSyncRunMapper.getRun(id, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    public IPage<SkillSyncRunVO> listRuns(String triggerType, String status, long page, long size) {
        Page<SkillSyncRunRowVO> rowPage = new Page<>(page, size);
        IPage<SkillSyncRunRowVO> rows = skillSyncRunMapper.pageRuns(rowPage, triggerType, status, staleBefore());
        Page<SkillSyncRunVO> voPage = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        voPage.setRecords(rows.getRecords().stream().map(this::toRunVO).toList());
        return voPage;
    }

    // ==================== 基类骨架 ====================

    @Override
    protected String lockKey() {
        return LOCK_KEY;
    }

    @Override
    protected String taskName() {
        return TASK_NAME;
    }

    @Override
    protected Long insertRunning(String trigger, String operator) {
        return skillSyncRunMapper.insertRunning(trigger, SkillSyncRunStatusEnum.STATUS_RUNNING, operator);
    }

    @Override
    protected SkillSyncRunVO doRun(String trigger, Long recordId, String operator, long startedAtMillis) {
        if (!isEnabled()) {
            markSkipped(recordId, SKIP_DISABLED_REASON, System.currentTimeMillis() - startedAtMillis, operator);
            SkillSyncRunVO vo = new SkillSyncRunVO();
            vo.setId(recordId);
            vo.setTriggerType(trigger);
            vo.setStatus(SkillSyncRunStatusEnum.STATUS_SKIPPED);
            vo.setSkipReason(SKIP_DISABLED_REASON);
            return vo;
        }
        return sweep(recordId, operator, startedAtMillis);
    }

    @Override
    protected void markSkipped(Long recordId, String reason, long elapsedMillis, String operator) {
        skillSyncRunMapper.markSkipped(recordId, SkillSyncRunStatusEnum.STATUS_SKIPPED,
                reason, elapsedMillis, operator);
    }

    @Override
    protected void markFailed(Long recordId, Exception e, long elapsedMillis, String operator) {
        skillSyncRunMapper.markFailed(recordId, SkillSyncRunStatusEnum.STATUS_FAILED,
                abbreviate(e.getMessage()), elapsedMillis, operator);
    }

    // ==================== 真正的扫描 ====================

    private SkillSyncRunVO sweep(Long recordId, String operator, long startedAtMillis) {
        int sizeLimit = Math.max(batchSize, 1);
        int scanned = 0;
        int updated = 0;
        int skipped = 0;
        List<SkillSyncRunVO.SkippedCopy> detail = new ArrayList<>();

        long pageNo = 1;
        while (true) {
            Page<CapabilitySkill> page = capabilitySkillService.page(
                    new Page<>(pageNo, sizeLimit),
                    new LambdaQueryWrapper<CapabilitySkill>()
                            // 注意：这里刻意没有时间窗口。原先的 created_at >= now-7d
                            // 是本次修复的根因 —— 它按「安装时间」筛，装久了就永远扫不到。
                            .eq(CapabilitySkill::getAutoUpdate, true)
                            .isNotNull(CapabilitySkill::getOriginSkillId)
                            .orderByAsc(CapabilitySkill::getId));
            List<CapabilitySkill> copies = page.getRecords();
            if (copies.isEmpty()) {
                break;
            }
            scanned += copies.size();
            for (CapabilitySkill copy : copies) {
                try {
                    SkillSyncOutcome outcome = capabilitySkillService.syncWithOutcome(copy);
                    if (outcome == SkillSyncOutcome.UPDATED) {
                        updated++;
                    } else {
                        skipped++;
                        if (detail.size() < MAX_DETAIL_ITEMS) {
                            detail.add(toSkippedCopy(copy, outcome));
                        }
                    }
                } catch (Exception e) {
                    // 单个副本失败不影响其余；但必须留痕，否则「没效果」再次无从排查
                    skipped++;
                    if (detail.size() < MAX_DETAIL_ITEMS) {
                        SkillSyncRunVO.SkippedCopy failed = toSkippedCopy(copy, SkillSyncOutcome.CONCURRENT_UPDATE);
                        failed.setReason("ERROR: " + e.getMessage());
                        detail.add(failed);
                    }
                    log.warn("{} failed: copyId={}, originId={}", TASK_NAME, copy.getId(),
                            copy.getOriginSkillId(), e);
                }
            }
            if (copies.size() < sizeLimit) {
                break;
            }
            pageNo++;
        }

        // 记录表自身也只保留 N 天：5 分钟一轮，不清会一直涨
        long retentionDays = resolveLogRetentionDays();
        long pruned = skillSyncRunMapper.deleteOlderThan(
                LocalDateTime.now().minusDays(retentionDays), recordId);

        long elapsed = System.currentTimeMillis() - startedAtMillis;
        skillSyncRunMapper.markSuccess(recordId, SkillSyncRunStatusEnum.STATUS_SUCCESS,
                scanned, scanned, updated, skipped,
                sizeLimit, JsonUtils.toJson(detail), elapsed, operator);
        log.info("{} done: scanned={}, updated={}, skipped={}, prunedOldRuns={}, elapsed={}ms",
                TASK_NAME, scanned, updated, skipped, pruned, elapsed);
        return null;
    }

    private SkillSyncRunVO.SkippedCopy toSkippedCopy(CapabilitySkill copy, SkillSyncOutcome outcome) {
        SkillSyncRunVO.SkippedCopy item = new SkillSyncRunVO.SkippedCopy();
        item.setCopyId(copy.getId());
        item.setOriginId(copy.getOriginSkillId());
        item.setReason(outcome.name());
        item.setOriginVersion(copy.getOriginVersion());
        return item;
    }

    private SkillSyncRunVO toRunVO(SkillSyncRunRowVO row) {
        SkillSyncRunVO vo = new SkillSyncRunVO();
        vo.setId(row.getId());
        vo.setTriggerType(row.getTriggerType());
        vo.setStatus(row.getStatus());
        vo.setScannedCount(row.getScannedCount());
        vo.setHandled(row.getHandled());
        vo.setUpdated(row.getUpdated());
        vo.setSkipped(row.getSkipped());
        vo.setBatchSize(row.getBatchSize());
        vo.setSkipReason(row.getSkipReason());
        vo.setErrorMessage(row.getErrorMessage());
        vo.setElapsedMs(row.getElapsedMs());
        vo.setStartedAt(row.getStartedAt());
        vo.setFinishedAt(row.getFinishedAt());
        vo.setStale(row.getStale());
        vo.setCreatedBy(row.getCreatedBy());
        if (row.getDetail() != null && !row.getDetail().isBlank()) {
            vo.setDetail(JsonUtils.parse(row.getDetail(), SKIP_DETAIL_TYPE));
        }
        return vo;
    }

    // ==================== 配置 ====================

    private boolean isEnabled() {
        return parseBoolean(systemConfigSpi.get(SystemConfigKeys.SKILL_AUTO_UPDATE_ENABLED, "true"), true);
    }

    private int resolveLogRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SKILL_SYNC_LOG_RETENTION_DAYS,
                String.valueOf(DEFAULT_LOG_RETENTION_DAYS)), DEFAULT_LOG_RETENTION_DAYS);
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        log.warn("{}: switch value {} is not a valid boolean, fallback {}", TASK_NAME, value, fallback);
        return fallback;
    }

    private int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            log.warn("{}: retention value {} is not an integer", TASK_NAME, raw);
        }
        return fallback;
    }

    // ==================== 常量 ====================

    private static final String SKIP_DISABLED_REASON = "自动同步总开关 skill.auto-update-enabled 为 false，本次跳过";

    private static final TypeReference<List<SkillSyncRunVO.SkippedCopy>> SKIP_DETAIL_TYPE =
            new TypeReference<>() {
            };
}