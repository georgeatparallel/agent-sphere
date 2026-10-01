package com.buukle.agent.instance.controller;

import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.instance.dtvo.dto.SessionCleanupRequestDTO;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.service.impl.SessionCleanupTask;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史会话清理的手动触发入口（运维用）。
 *
 * <p>定时任务默认在低峰期（04:30）跑；这个接口用于「现在就释放磁盘」或「先看清楚会删什么」。
 *
 * <p><b>默认 dry-run</b>：清理是<b>不可逆硬删</b>，请求体必须显式传 {@code "dryRun": false} 才会落刀。
 *
 * <p><b>同步执行</b>：实删一轮可能耗时数分钟（受 max-batches 限制），期间占用一个请求线程；
 * 这是刻意的 —— 同步才能把报告直接回给调用方，无需另做进度查询。
 */
@RestController
@RequestMapping("/api/v1/instance/session-cleanup")
@RequiredArgsConstructor
public class SessionCleanupController extends BaseController {

    private final SessionCleanupTask sessionCleanupTask;

    /**
     * 执行一轮清理。
     *
     * <p>权限复用 {@code admin:settings:update}：它已经是「改系统配置」的既有权限位，
     * 而清理行为完全由系统配置（保留天数/开关）驱动，语义一致且不必新增权限种子。
     */
    @AuditLog(action = "CLEANUP", resourceType = "Session")
    @RequirePermission("admin:settings:update")
    @PostMapping
    public ResponseEntity<?> cleanup(@Valid @RequestBody(required = false) SessionCleanupRequestDTO dto) {
        boolean dryRun = dto == null || dto.isDryRun();
        return ok(sessionCleanupTask.runCleanup(dryRun));
    }
}
