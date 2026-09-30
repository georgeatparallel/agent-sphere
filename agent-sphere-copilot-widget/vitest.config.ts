import { defineConfig } from 'vitest/config';

// 纯函数单测（toolRenderers 等），node 环境即可，无需 jsdom。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
