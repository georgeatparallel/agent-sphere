import { describe, expect, it } from 'vitest';
import {
  browserResult,
  docSummary,
  extractTodos,
  genericSummary,
  isBrowserTool,
  todoProgress,
  todoTotal,
  toolFamily,
} from './toolRenderers';

describe('toolRenderers', () => {
  describe('toolFamily', () => {
    it('detects todo family from todos array in args', () => {
      const content = {
        displayName: '待办写入',
        args: JSON.stringify({
          todos: [{ content: 'a', status: 'pending', priority: 'high' }],
        }),
      };
      expect(toolFamily(content)).toBe('todo');
    });

    it('detects doc family from action+title args', () => {
      const content = {
        displayName: '文档操作',
        args: JSON.stringify({ action: 'create', title: 'My doc' }),
      };
      expect(toolFamily(content)).toBe('doc');
    });

    it('falls back to doc via displayName keyword', () => {
      const content: any = { displayName: '文档操作' };
      expect(toolFamily(content)).toBe('doc');
    });

    it('returns other for unrelated args', () => {
      const content: any = { args: JSON.stringify({ query: 'hello' }) };
      expect(toolFamily(content)).toBe('other');
    });
  });

  describe('extractTodos', () => {
    it('parses todos from args with normalized fields', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: [
            { content: 'a', status: 'completed', priority: 'high' },
            { content: 'b' },
          ],
        }),
      });
      expect(todos).toHaveLength(2);
      expect(todos[0]).toEqual({
        content: 'a',
        status: 'completed',
        priority: 'high',
      });
      expect(todos[1].status).toBeUndefined();
    });

    it('returns empty array for invalid payloads', () => {
      expect(extractTodos({ args: 'not-json' })).toEqual([]);
      expect(extractTodos({})).toEqual([]);
    });

    it('restores compressed array form { _count, items }', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: { _count: 13, _showing: 1, items: [{ content: 'a' }] },
        }),
      });
      expect(todos).toHaveLength(1);
      expect(todos[0].content).toBe('a');
    });
  });

  describe('docSummary', () => {
    it('renders action + document + title', () => {
      const s = docSummary({
        args: JSON.stringify({ action: 'create', title: 'Hello' }),
      });
      expect(s).toBe('create document Hello');
    });

    it('renders action + document when no title', () => {
      const s = docSummary({ args: JSON.stringify({ action: 'update' }) });
      expect(s).toBe('update document');
    });

    it('uses #documentId when title missing', () => {
      const s = docSummary({
        args: JSON.stringify({ action: 'read', documentId: 7 }),
      });
      expect(s).toBe('read document#7');
    });
  });

  describe('genericSummary', () => {
    it('extracts query as subject', () => {
      const s = genericSummary({
        args: JSON.stringify({ action: 'search', query: 'a b' }),
      });
      expect(s).toBe('search a b');
    });

    it('falls back to displayName', () => {
      expect(genericSummary({ displayName: '工具' })).toBe('工具');
    });
  });

  describe('todoProgress', () => {
    it('counts completed vs total', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: [
            { content: 'a', status: 'completed' },
            { content: 'b', status: 'pending' },
            { content: 'c', status: 'in_progress' },
          ],
        }),
      });
      expect(todoProgress(todos)).toEqual({ done: 1, total: 3 });
    });
  });

  describe('todoTotal', () => {
    it('uses _count from compressed form', () => {
      expect(
        todoTotal({
          args: JSON.stringify({
            todos: { _count: 13, _showing: 1, items: [{ content: 'a' }] },
          }),
        }),
      ).toBe(13);
    });
  });

  describe('browserResult', () => {
    it('detects browser tool by display name', () => {
      expect(isBrowserTool({}, '浏览器操作')).toBe(true);
      expect(isBrowserTool({}, '文档操作')).toBe(false);
    });

    it('marks failure from artifact success=false', () => {
      const r = browserResult(
        {
          displayName: '浏览器导航',
          artifact: JSON.stringify({ success: false, errorCategory: 'timeout' }),
        },
        '浏览器导航',
      );
      expect(r.failed).toBe(true);
      expect(r.errorCategory).toBe('timeout');
    });
  });
});
