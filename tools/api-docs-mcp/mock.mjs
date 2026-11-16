#!/usr/bin/env node
/**
 * 从 OpenAPI 生成前端 Mock 数据（JSON），供 Vite 开发中间件在「后端接口未就绪」时使用。
 *
 * 设计边界：
 * - 只读 OpenAPI 快照，输出 platform/web/mock/<tag>.json；
 * - 结构：{ generatedAt, source, routes: { "GET /api/v1/xxx": { status, body } } }
 * - 跳过 text/event-stream（SSE 无法用静态 JSON 模拟）；
 * - 生成结果属于构建产物，不提交仓库。
 *
 * 用法：
 *   node mock.mjs [--openapi <file>] [--out <dir>] [--max <n>] [--quiet]
 *   npm run mock:gen --prefix platform/web
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../..");
const DEFAULT_OPENAPI = path.resolve(
  REPO_ROOT,
  "platform/agent-gateway/src/main/resources/api/openapi.json",
);
const DEFAULT_OUT = path.resolve(REPO_ROOT, "platform/web/mock");
const METHODS = ["get", "post", "put", "patch", "delete", "head", "options", "trace"];

function deref(schema, openapi) {
  let node = schema;
  let guard = 0;
  while (node?.$ref && guard < 20) {
    const name = node.$ref.replace("#/components/schemas/", "");
    node = openapi.components?.schemas?.[name];
    guard += 1;
  }
  return node;
}

function sampleString(schema, fieldName) {
  if (schema.format === "date-time") return "2026-01-01T00:00:00Z";
  if (schema.format === "date") return "2026-01-01";
  if (schema.format === "uuid") return "00000000-0000-4000-8000-000000000000";
  if (schema.format === "email") return "mock@example.com";
  if (schema.format === "uri" || schema.format === "url") return "https://example.com/mock";
  const name = fieldName.toLowerCase();
  if (name.endsWith("id") || name === "id") return "mock-id";
  if (name.includes("name")) return "mock-name";
  if (name.includes("status")) return "ok";
  if (name.includes("message")) return "mock message";
  return "string";
}

/** 按 JSON Schema 生成示例值：优先 example/default/enum，$ref 解析并防循环。 */
export function sampleValue(schema, openapi, fieldName = "", depth = 0, seen = new Set()) {
  if (!schema || depth > 6) return null;
  if (schema.example !== undefined) return schema.example;
  if (schema.default !== undefined) return schema.default;
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[0];
  if (schema.const !== undefined) return schema.const;
  if (schema.$ref) {
    if (seen.has(schema.$ref)) return null;
    const next = new Set(seen);
    next.add(schema.$ref);
    return sampleValue(deref(schema, openapi), openapi, fieldName, depth + 1, next);
  }
  if (Array.isArray(schema.allOf)) {
    const merged = {};
    for (const part of schema.allOf) {
      const value = sampleValue(part, openapi, fieldName, depth + 1, seen);
      if (value && typeof value === "object" && !Array.isArray(value)) Object.assign(merged, value);
    }
    return merged;
  }
  const variant = schema.oneOf?.[0] ?? schema.anyOf?.[0];
  if (variant) return sampleValue(variant, openapi, fieldName, depth + 1, seen);
  if (schema.type === "array" || schema.items) {
    return [sampleValue(schema.items, openapi, fieldName, depth + 1, seen)];
  }
  if (schema.type === "object" || schema.properties) {
    const out = {};
    for (const [key, prop] of Object.entries(schema.properties ?? {})) {
      out[key] = sampleValue(prop, openapi, key, depth + 1, seen);
    }
    return out;
  }
  if (schema.type === "integer" || schema.type === "number") return 0;
  if (schema.type === "boolean") return false;
  return sampleString(schema, fieldName);
}

function pickJson(response) {
  const content = response?.content ?? {};
  for (const [media, body] of Object.entries(content)) {
    if (media.includes("application/json")) return body;
  }
  return undefined;
}

/** 生成「tag → { "METHOD /path": { status, body } }」映射。 */
export function generateMocks(openapi, { maxPerTag = 500 } = {}) {
  const tags = new Map();
  for (const [route, item] of Object.entries(openapi?.paths ?? {})) {
    for (const method of METHODS) {
      const operation = item?.[method];
      if (!operation || typeof operation !== "object") continue;
      const success = Object.entries(operation.responses ?? {})
        .find(([code]) => /^2\d\d$/.test(code));
      if (!success) continue;
      const [status, response] = success;
      const hasSse = Object.keys(response.content ?? {}).some((media) =>
        media.includes("text/event-stream"));
      if (hasSse) continue;
      const media = pickJson(response);
      const body = media?.schema
        ? sampleValue(media.schema, openapi)
        : (status === "204" ? null : {});
      const tag = (operation.tags?.[0] ?? "default").replace(/[^\w.-]+/g, "_");
      const routes = tags.get(tag) ?? new Map();
      if (routes.size < maxPerTag) {
        routes.set(`${method.toUpperCase()} ${route}`, { status: Number(status), body });
      }
      tags.set(tag, routes);
    }
  }
  return tags;
}

export function writeMocks(tags, { out = DEFAULT_OUT, source = DEFAULT_OPENAPI } = {}) {
  if (existsSync(out)) {
    for (const file of readdirSync(out)) {
      if (file.endsWith(".json")) rmSync(path.join(out, file));
    }
  } else {
    mkdirSync(out, { recursive: true });
  }
  const generatedAt = new Date().toISOString();
  const files = [];
  for (const [tag, routes] of tags) {
    const file = path.join(out, `${tag}.json`);
    writeFileSync(file, `${JSON.stringify({
      generatedAt, source, routes: Object.fromEntries(routes),
    }, null, 2)}\n`);
    files.push({ file, routes: routes.size });
  }
  return files;
}

function parseArgs(argv) {
  const options = { openapi: DEFAULT_OPENAPI, out: DEFAULT_OUT, max: 500, quiet: false };
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (key === "--openapi") options.openapi = path.resolve(argv[++i]);
    else if (key === "--out") options.out = path.resolve(argv[++i]);
    else if (key === "--max") options.max = Number(argv[++i]);
    else if (key === "--quiet") options.quiet = true;
    else if (key === "--help" || key === "-h") options.help = true;
    else throw new Error(`未知参数：${key}`);
  }
  return options;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(
      "用法：node mock.mjs [--openapi <file>] [--out <dir>] [--max <n>] [--quiet]\n",
    );
    return;
  }
  if (!existsSync(options.openapi)) throw new Error(`OpenAPI 快照不存在：${options.openapi}`);
  const openapi = JSON.parse(readFileSync(options.openapi, "utf8"));
  const files = writeMocks(generateMocks(openapi, { maxPerTag: options.max }), {
    out: options.out, source: options.openapi,
  });
  if (!options.quiet) {
    const total = files.reduce((sum, item) => sum + item.routes, 0);
    process.stdout.write(`Mock 已生成：${files.length} 个文件，${total} 条路由 → ${options.out}\n`);
    for (const item of files) {
      process.stdout.write(`- ${path.basename(item.file)}: ${item.routes}\n`);
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`生成 Mock 失败：${error.message ?? error}\n`);
    process.exitCode = 1;
  }
}
