/**
 * 契约审计单元测试：node --test audit.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { auditFrontend, extractCalls, searchFrontendApi } from "./audit.mjs";

const OPENAPI = {
  info: { title: "test-api", version: "v1" },
  paths: {
    "/api/v1/projects/{projectId}": {
      get: {
        parameters: [{ name: "projectId", in: "path", required: true }],
        responses: { 200: { content: { "application/json": { schema: { type: "object" } } } } },
      },
      delete: {
        parameters: [{ name: "projectId", in: "path", required: true }],
        responses: { 204: { description: "ok" } },
      },
    },
    "/api/v1/actions/{action}/run": {
      post: {
        parameters: [
          { name: "action", in: "path", required: true },
          { name: "env", in: "query", required: true },
        ],
        responses: { 200: { content: { "application/json": { schema: { type: "object" } } } } },
      },
    },
    "/api/v1/git-issues": {
      get: {
        parameters: [{ name: "state", in: "query" }],
        responses: {
          200: { content: { "application/json": { schema: { type: "array", items: { type: "object" } } } } },
        },
      },
    },
  },
};

function withFixture(source, run) {
  const dir = mkdtempSync(path.join(tmpdir(), "api-audit-"));
  try {
    writeFileSync(path.join(dir, "demo.ts"), source);
    return run(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("合法调用不产生问题", () => {
  const source = `
import { apiFetch } from './http'
export function getProject(projectId: string) {
  return apiFetch<{ id: string }>(\`/api/v1/projects/\${encodeURIComponent(projectId)}\`)
}
`;
  withFixture(source, (dir) => {
    const report = auditFrontend({ openapi: OPENAPI, frontendDir: dir });
    assert.equal(report.calls, 1);
    assert.deepEqual(report.issues, []);
  });
});

test("路径不存在与方法未声明都报 error", () => {
  const source = `
import { apiFetch } from './http'
export const missing = () => apiFetch('/api/v1/nope')
export const wrongMethod = (projectId: string) =>
  apiFetch(\`/api/v1/projects/\${projectId}\`, { method: 'PUT' })
`;
  withFixture(source, (dir) => {
    const report = auditFrontend({ openapi: OPENAPI, frontendDir: dir });
    const codes = report.issues.map((issue) => issue.code).sort();
    assert.deepEqual(codes, ["METHOD_NOT_FOUND", "PATH_NOT_FOUND"]);
    assert.ok(report.issues.every((issue) => issue.severity === "error"));
    assert.ok(report.issues.every((issue) => issue.line > 0));
  });
});

test("查询参数：字面量与 URLSearchParams 都能核对", () => {
  const source = `
import { apiFetch } from './http'
export function listIssues(state?: string, foo?: string) {
  const params = new URLSearchParams()
  if (state) params.set('state', state)
  const qs = params.toString()
  return apiFetch(\`/api/v1/git-issues\${qs ? \`?\${qs}\` : ''}\`)
}
export const unknown = () => apiFetch('/api/v1/git-issues?foo=1')
`;
  withFixture(source, (dir) => {
    const report = auditFrontend({ openapi: OPENAPI, frontendDir: dir });
    const codes = report.issues.map((issue) => issue.code);
    assert.ok(!codes.includes("UNSUPPORTED_PATH"), `不应把条件查询误判为动态路径：${codes}`);
    assert.ok(codes.includes("QUERY_PARAM_UNKNOWN"));
    assert.ok(report.issues.every((issue) => issue.severity === "warning"));
  });
});

test("必填查询参数缺失给出 warning", () => {
  const source = `
import { apiFetch } from './http'
export const run = (action: string) =>
  apiFetch(\`/api/v1/actions/\${encodeURIComponent(action)}/run\`, { method: 'POST' })
`;
  withFixture(source, (dir) => {
    const report = auditFrontend({ openapi: OPENAPI, frontendDir: dir });
    assert.deepEqual(report.issues.map((issue) => issue.code), ["QUERY_PARAM_MISSING"]);
  });
});

test("无法静态解析的路径给出 UNSUPPORTED_PATH", () => {
  const calls = extractCalls("const x = apiFetch(dynamicPath)", "demo.ts");
  assert.equal(calls.length, 1);
  assert.match(calls[0].unsupported, /无法解析/);
});

test("searchFrontendApi 能按关键词定位前端封装", () => {
  const source = `
import { apiFetch } from './http'
export function listIssues(state?: string): Promise<unknown[]> {
  const q = state ? \`?state=\${encodeURIComponent(state)}\` : ''
  return apiFetch(\`/api/v1/git-issues\${q}\`)
}
`;
  withFixture(source, (dir) => {
    const hits = searchFrontendApi({ frontendDir: dir, keyword: "git-issues" });
    assert.equal(hits.length, 1);
    assert.equal(hits[0].name, "listIssues");
    assert.equal(hits[0].method, "GET");
    assert.equal(hits[0].path, "/api/v1/git-issues");
  });
});
