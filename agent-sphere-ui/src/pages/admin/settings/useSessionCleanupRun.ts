import { useCallback, useEffect, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import type { SessionCleanupRun } from '@/services/agentSphere/api';

/** 轮询间隔：后端每处理完一批就写一次进度，1 秒足够跟上且最省 */
const POLL_INTERVAL_MS = 1000;

const TERMINAL_STATUS = ['SUCCESS', 'FAILED', 'SKIPPED'];

/**
 * 轮询一条会话清理执行记录，直到它落到终态。
 *
 * <p>清理是异步的：提交接口立刻返回 `status=RUNNING` 的记录，真正的删除在后台跑，
 * 每批结束写一次进度。所以前端只能靠轮询拿进度条的数据源。
 *
 * <p>记录可能永远停在 RUNNING（进程崩了），后端用 `stale` 标出来，这里据此停止轮询
 * —— 否则就是一条永不停止的定时器。
 */
export function useSessionCleanupRun(runId: number | null) {
  const [run, setRun] = useState<SessionCleanupRun | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stoppedRef = useRef(false);

  const stop = useCallback(() => {
    stoppedRef.current = true;
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const schedule = useCallback(
    (tick: () => void, delay = POLL_INTERVAL_MS) => {
      timerRef.current = setTimeout(tick, delay);
    },
    [],
  );

  useEffect(() => {
    stoppedRef.current = false;
    if (runId == null) {
      setRun(null);
      return;
    }

    let cancelled = false;
    const tick = async () => {
      try {
        const next = await agentApi.admin.getSessionCleanupRun(runId);
        if (cancelled || stoppedRef.current) return;
        setRun(next);
        if (TERMINAL_STATUS.includes(next.status) || next.stale) {
          stoppedRef.current = true;
          return;
        }
      } catch {
        // 单次轮询失败不终止：下一拍重试，避免网络抖动把进行中的清理显示成结束
        if (cancelled || stoppedRef.current) return;
      }
      if (!cancelled && !stoppedRef.current) {
        schedule(tick);
      }
    };

    tick();
    return () => {
      cancelled = true;
      stop();
    };
  }, [runId, schedule, stop]);

  return {
    run,
    stop,
    running: !!run && run.status === 'RUNNING' && !run.stale,
  };
}