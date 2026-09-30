/**
 * 用量显示（自 UmiJS 主站 Usage.tsx 移植，去除 antd）。
 * 千分位缩写 + 缓存命中率 + 悬浮明细（用 title 属性代替 Tooltip）。
 */
import type { UsageData } from '../types';

export type { UsageData };

/** 千分位缩写：1234 → 1.2k；不足千显示原文。 */
export function formatTokens(n?: number | null): string {
  if (n == null) return '-';
  if (n >= 1000) {
    const k = n / 1000;
    return `${k >= 100 ? Math.round(k) : Math.round(k * 10) / 10}k`;
  }
  return String(n);
}

/** 缓存命中率(%)：hit / (hit+miss)；无基数返回 null。 */
export function cacheHitRate(u?: UsageData): number | null {
  if (!u) return null;
  const hit = u.cacheHitTokens || 0;
  const miss = u.cacheMissTokens || 0;
  const denom = hit + miss;
  if (denom <= 0) return null;
  return Math.round((hit * 1000) / denom) / 10;
}

/** 用量小标；无用量时不渲染。 */
export default function UsageChip({ usage }: { usage?: UsageData }) {
  if (!usage || !usage.totalTokens) return null;
  const rate = cacheHitRate(usage);
  const tip = [
    `Prompt ${formatTokens(usage.promptTokens)}`,
    `Completion ${formatTokens(usage.completionTokens)}`,
    `Total ${formatTokens(usage.totalTokens)}`,
    `Cache hit ${formatTokens(usage.cacheHitTokens)}`,
    `Cache miss ${formatTokens(usage.cacheMissTokens)}`,
    rate != null ? `Cache rate ${rate}%` : '',
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <span className="aw-usage-chip" title={tip}>
      ⛓ {formatTokens(usage.totalTokens)} tokens
      {rate != null ? ` · cache ${rate}%` : ''}
    </span>
  );
}
