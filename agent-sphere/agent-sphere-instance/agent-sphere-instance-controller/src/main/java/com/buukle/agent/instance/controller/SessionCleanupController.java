package com.buukle.agent.instance.controller;

import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.SessionCleanupRequestDTO;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupTriggerEnum;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupRunVO;
import com.buukle.agent.instance.service.impl.SessionCleanupTask;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史会话清理的手动触发入口（运维用）。
 *
 * <p>定时任务默认在低峰期（04:30）跑；这个接口用于「现在就释放磁盘」或「先看清楚会删什么」。
 *
 * <p><b>默认 dry-run</b>：清理是<b>不可逆硬删</b>，请求体必须显式传 {@code "dryRun": false} 才会落刀。
 *
 * <p><b>异步</b>：提交即返回执行记录（status=RUNNING），前端拿其中的 id 轮询
 * {@link #run(Long)} 看进度与结果。一轮清理最多 200×50 个会话、耗时可达数分钟，
 * 让请求线程干等会拖垮连接池，前端也只能靠超时猜结果。
 */
@RestController
@RequestMapping("/api/v1/instance/session-cleanup")
@RequiredArgsConstructor
public class SessionCleanupController extends BaseController {

    private final SessionCleanupTask sessionCleanupTask;

    /**
     * 提交一轮清理（异步）。
     *
     * <p>权限复用 {@code admin:settings:update}：它已经是「改系统配置」的既有权限位，
     * 而清理行为完全由系统配置（保留天数/开关）驱动，语义一致且不必新增权限种子。
     *
     * @return 执行记录（含 id，供轮询）；不会返回清理结果，结果看轮询
     */
    @AuditLog(action = "CLEANUP", resourceType = "Session")
    @RequirePermission("admin:settings:update")
    @PostMapping
    public ResponseEntity<?> cleanup(@Valid @RequestBody(required = false) SessionCleanupRequestDTO dto) {
        boolean dryRun = dto == null || dto.isDryRun();
        SessionCleanupRunVO run = sessionCleanupTask.submitCleanup(dryRun, SessionCleanupTriggerEnum.TRIGGER_MANUAL);
        return ok(run);
    }

    /**
     * 轮询单条执行记录（含实时进度：已处理会话数 / 已清理行数 / 耗时）。
     *
     * <p>进程崩了会让记录永远停在 RUNNING，SQL 里用 {@code stale} 标出来
     * （finished_at 为空且开始超过 30 分钟），避免被误读成正在跑。
     */
    @RequirePermission("admin:settings:read")
    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable Long id) {
        SessionCleanupRunVO run = sessionCleanupTask.getRun(id);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    /**
     * 执行记录分页。定时与手动两种触发都会出现在这里，预演与真实删除也在同一条时间线上
     * （靠「类型」列区分），这样「谁在什么时候预演过、看到多少行」也能回溯。
     *
     * @param dryRun null = 不按预演/实删过滤
     */
    @RequirePermission("admin:settings:read")
    @GetMapping("/runs")
    public ResponseEntity<?> runs(@RequestParam(required = false) String triggerType,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Boolean dryRun,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size) {
        return ok(sessionCleanupTask.listRuns(triggerType, status, dryRun, page, size));
    }
}