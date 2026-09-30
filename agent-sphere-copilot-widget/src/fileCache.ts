/**
 * 附件字节 objectURL 的 module 级缓存：
 * - 同一 fileKey 并发请求去重（in-flight promise 复用），避免重复 fetch / 重复 createObjectURL；
 * - 命中缓存直接返回，渲染抖动不再触发重复下载（修复 ERR_INSUFFICIENT_RESOURCES）；
 * - 支持 AbortController：clearFileCache 会中止在途请求并释放全部 objectURL。
 *
 * 生命周期：缓存归属模块，组件只读不 revoke；切会话/卸载时调用 clearFileCache 统一回收。
 */

interface CacheEntry {
  url?: string;
  promise?: Promise<string>;
  controller?: AbortController;
}

const cache = new Map<string, CacheEntry>();

/** 取（或加载）objectURL；同 key 并发去重；结果缓存至 clearFileCache。 */
export function loadCachedObjectUrl(
  cacheKey: string,
  loader: (fileKey: string, signal: AbortSignal) => Promise<Blob>,
  fileKey: string,
): Promise<string> {
  const existing = cache.get(cacheKey);
  if (existing?.url) return Promise.resolve(existing.url);
  if (existing?.promise) return existing.promise;

  const entry: CacheEntry = existing ?? {};
  const controller = new AbortController();
  entry.controller = controller;
  const promise = loader(fileKey, controller.signal)
    .then((blob) => {
      entry.url = URL.createObjectURL(blob);
      entry.promise = undefined;
      entry.controller = undefined;
      return entry.url;
    })
    .catch((err) => {
      entry.promise = undefined;
      entry.controller = undefined;
      if (!entry.url) cache.delete(cacheKey);
      throw err;
    });
  entry.promise = promise;
  cache.set(cacheKey, entry);
  return promise;
}

/** 清空缓存：中止在途请求并释放所有 objectURL（会话切换 / 组件卸载时调用）。 */
export function clearFileCache(): void {
  for (const entry of cache.values()) {
    entry.controller?.abort();
    if (entry.url) {
      try {
        URL.revokeObjectURL(entry.url);
      } catch {
        /* 忽略 */
      }
    }
  }
  cache.clear();
}

/** 当前缓存条目数（调试用）。 */
export function cachedObjectUrlCount(): number {
  return cache.size;
}
