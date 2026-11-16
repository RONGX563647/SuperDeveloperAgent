/**
 * Mock 生成器单元测试：node --test mock.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { generateMocks, sampleValue, writeMocks } from "./mock.mjs";

const OPENAPI = {
  info: { title: "test", version: "v1" },
  components: {
    schemas: {
      Item: {
        type: "object",
        properties: {
          id: { type: "string" },
          at: { type: "string", format: "date-time" },
          count: { type: "integer" },
          ok: { type: "boolean" },
        },
      },
      Node: {
        type: "object",
        properties: {
          id: { type: "string" },
          parent: { $ref: "#/components/schemas/Node" },
        },
      },
    },
  },
  paths: {
    "/api/v1/items/{id}": {
      get: {
        tags: ["items"],
        responses: {
          200: {
            content: { "application/json": { schema: { $ref: "#/components/schemas/Item" } } },
          },
        },
      },
    },
    "/api/v1/stream": {
      get: {
        tags: ["sse"],
        responses: {
          200: { content: { "text/event-stream": { schema: { type: "string" } } } },
        },
      },
    },
    "/api/v1/items": {
      delete: { tags: ["items"], responses: { 204: { description: "ok" } } },
    },
  },
};

test("按 $ref 生成对象，format 有合理默认值", () => {
  const body = sampleValue({ $ref: "#/components/schemas/Item" }, OPENAPI);
  assert.equal(body.id, "mock-id");
  assert.equal(body.at, "2026-01-01T00:00:00Z");
  assert.equal(body.count, 0);
  assert.equal(body.ok, false);
});

test("循环 $ref 不会无限递归", () => {
  const body = sampleValue({ $ref: "#/components/schemas/Node" }, OPENAPI);
  assert.equal(body.id, "mock-id");
  assert.equal(body.parent, null);
});

test("生成路由时跳过 SSE，保留 204", () => {
  const tags = generateMocks(OPENAPI);
  assert.ok(!tags.has("sse"));
  const routes = tags.get("items");
  assert.ok(routes.has("GET /api/v1/items/{id}"));
  assert.equal(routes.get("GET /api/v1/items/{id}").status, 200);
  assert.equal(routes.get("DELETE /api/v1/items").body, null);
});

test("writeMocks 输出可被 Vite 中间件消费的 JSON", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "api-mock-"));
  try {
    const files = writeMocks(generateMocks(OPENAPI), { out: dir, source: "fixture.json" });
    assert.equal(files.length, 1);
    const parsed = JSON.parse(readFileSync(files[0].file, "utf8"));
    assert.equal(parsed.source, "fixture.json");
    assert.ok(parsed.routes["GET /api/v1/items/{id}"]);
    assert.equal(parsed.routes["GET /api/v1/items/{id}"].status, 200);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
