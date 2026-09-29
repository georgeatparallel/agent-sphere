-- ============================================================
-- V71：MCP 连接测试 / 工具列表 / 试调用 权限
-- 沿用 V14/V15 幂等写法（角色不存在产生零行，NOT EXISTS 防重复授权）。
-- 超管走 AuthContext.isSuperAdmin()，不依赖角色行。
-- ============================================================
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'MCP测试', 'capability:mcp:test', 'BUTTON', id, 5
FROM sys_permission WHERE code = 'capability'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'capability:mcp:test');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER'
  AND p.code = 'capability:mcp:test'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);