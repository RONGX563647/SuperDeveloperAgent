#!/usr/bin/env node
/**
 * 只读 MCP 服务器：把平台的 OpenAPI 规范 / 工具目录暴露给 AI Agent。
 *
 * 设计边界：
 * - 只读 openapi.json 与 agent-tools.json（或显式配置的 /v3/api-docs），
 *   不调用任何业务接口，不缓存业务数据；
 * - 每次响应都带 provenance（数据源、获取时间、规范版本、接口数），便于追溯；
 * - stdio 传输，可直接配置进平台「设置 → MCP」或 Claude Code / Cursor。
 *
 * 用法：
 *   node server.js --file ./openapi.json [--tools ./agent-tools.json]
 *   node server.js --url http://127.0.0.1:8090/v3/api-docs [--token <token>]
 *   API_DOCS_FILE / API_DOCS_URL / API_DOCS_TOKEN / AGENT_TOOLS_FILE 环境变量等价。
 *   --frontend <dir> / AGENT_FRONTEND_DIR 指定前端 src/api 目录（契约审计用）。
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { readFile, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  DEFAULT_FRONTEND, auditFrontend, formatReport, searchFrontendApi,
} from "./audit.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const METHODS = ["get", "post", "put", "patch", "delete", "head", "options", "trace"];

function parseArgs(argv) {
  const args = { file: process.env.API_DOCS_FILE, url: process.env.API_DOCS_URL,
    token: process.env.API_DOCS_TOKEN, tools: process.env.AGENT_TOOLS_FILE,
    frontend: process.env.AGENT_FRONTEND_DIR };
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    const next = () => {
      const value = argv[i + 1];
      if (!value) throw new Error(`${key} 缺少参数`);
      i += 1;
      return value;
    };
    if (key === "--file") args.file = next();
    else if (key === "--url") args.url = next();
    else if (key === "--token") args.token = next();
    else if (key === "--tools") args.tools = next();
    else if (key === "--frontend") args.frontend = next();
    else if (key === "--help" || key === "-h") args.help = true;
    else throw new Error(`未知参数：${key}`);
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  process.stdout.write(`agent-platform-api-docs-mcp

  --file <path>      OpenAPI JSON 文件（默认自动查找仓库内快照）
  --url <url>        改为从运行中的平台拉取 /v3/api-docs
  --token <token>    --url 时的 Authorization Bearer Token
  --tools <path>     agent-tools.json（提供 tool_search / tool_get）
  --frontend <dir>   前端 src/api 目录（提供契约审计与封装检索）

也可以使用环境变量 API_DOCS_FILE / API_DOCS_URL / API_DOCS_TOKEN / AGENT_TOOLS_FILE /
AGENT_FRONTEND_DIR。
`);
  process.exit(0);
}

function firstExisting(candidates) {
  return candidates.find((candidate) => candidate && existsSync(candidate));
}

const specFile = args.url ? null : firstExisting([
  args.file,
  path.resolve(process.cwd(), "openapi.json"),
  path.resolve(HERE, "../../platform/agent-gateway/src/main/resources/api/openapi.json"),
  path.resolve(HERE, "../../data/api/openapi.json"),
]);
const toolsFile = firstExisting([
  args.tools,
  path.resolve(process.cwd(), "agent-tools.json"),
  path.resolve(HERE, "../../platform/data/api/agent-tools.json"),
  path.resolve(HERE, "../../data/api/agent-tools.json"),
]);
const frontendDir = path.resolve(args.frontend ?? DEFAULT_FRONTEND);

let specCache = null;
let toolsCache = null;

const provenance = (loaded) =>
  `source: ${loaded.source}\nfetchedAt: ${new Date(loaded.fetchedAt).toISOString()}\n` +
  `spec: ${loaded.title} ${loaded.version}；operations=${loaded.operations.size}`;

function indexSpec(root, source, fetchedAt) {
  const operations = new Map();
  for (const [route, item] of Object.entries(root.paths ?? {})) {
    for (const method of METHODS) {
      const node = item[method];
      if (!node || typeof node !== "object") continue;
      const tags = Array.isArray(node.tags) ? node.tags : [];
      operations.set(`${method.toUpperCase()} ${route}`, {
        method: method.toUpperCase(), path: route, tags, node,
        summary: node.summary ?? "", description: node.description ?? "",
      });
    }
  }
  const haystack = (op) =>
    `${op.path} ${op.summary} ${op.description} ${op.tags.join(" ")} ${op.node.operationId ?? ""}`
      .toLowerCase();
  for (const op of operations.values()) op.haystack = haystack(op);
  const schemas = new Map(Object.entries(root.components?.schemas ?? {}));
  return {
    source, fetchedAt, root,
    title: root.info?.title ?? "", version: root.info?.version ?? "",
    operations, schemas,
  };
}

async function getSpec() {
  if (specCache) return specCache;
  if (args.url) {
    const headers = { accept: "application/json" };
    if (args.token) headers.authorization = `Bearer ${args.token}`;
    const response = await fetch(args.url, { headers });
    if (!response.ok) {
      throw new Error(`拉取 OpenAPI 失败：HTTP ${response.status} ${args.url}`);
    }
    specCache = indexSpec(await response.json(), args.url, Date.now());
    return specCache;
  }
  if (!specFile) {
    throw new Error("没有找到 openapi.json：请用 --file 指定，或先启动一次平台生成快照。");
  }
  const [info, text] = await Promise.all([stat(specFile), readFile(specFile, "utf8")]);
  if (specCache && specCache.source === specFile && specCache.mtimeMs === info.mtimeMs) {
    return specCache;
  }
  const parsed = indexSpec(JSON.parse(text), specFile, info.mtimeMs);
  parsed.mtimeMs = info.mtimeMs;
  specCache = parsed;
  return specCache;
}

async function getTools() {
  if (toolsCache) return toolsCache;
  if (!toolsFile) {
    return null;
  }
  const [info, text] = await Promise.all([stat(toolsFile), readFile(toolsFile, "utf8")]);
  if (toolsCache && toolsCache.mtimeMs === info.mtimeMs) {
    return toolsCache;
  }
  const parsed = JSON.parse(text);
  toolsCache = {
    source: toolsFile, mtimeMs: info.mtimeMs, generatedAt: parsed.generatedAt,
    tools: Array.isArray(parsed.tools) ? parsed.tools : [],
  };
  return toolsCache;
}

function typeOf(schema) {
  if (!schema) return "";
  if (schema.$ref) return schema.$ref.replace("#/components/schemas/", "");
  if (schema.type === "array") return `array<${typeOf(schema.items)}>`;
  return schema.type ?? "";
}

function renderOperation(op) {
  const node = op.node;
  const lines = [`${op.method} ${op.path}`];
  if (op.summary) lines.push(`summary: ${op.summary}`);
  if (op.tags.length) lines.push(`tags: ${op.tags.join(", ")}`);
  if (node.operationId) lines.push(`operationId: ${node.operationId}`);
  if (op.description) lines.push(`description: ${op.description}`);
  if (Array.isArray(node.parameters) && node.parameters.length) {
    lines.push("parameters:");
    for (const p of node.parameters) {
      const required = p.required ? ", required" : "";
      lines.push(`  - ${p.name} (${p.in}${required}) ${typeOf(p.schema)} ${p.description ?? ""}`.trimEnd());
    }
  }
  if (node.requestBody) {
    lines.push(`requestBody${node.requestBody.required ? " (required)" : ""}:`);
    for (const [media, body] of Object.entries(node.requestBody.content ?? {})) {
      lines.push(`  ${media} -> ${typeOf(body.schema)}`);
    }
  }
  if (node.responses) {
    lines.push("responses:");
    for (const [status, response] of Object.entries(node.responses)) {
      lines.push(`  - ${status}${response.description ? `: ${response.description}` : ""}`);
      for (const [media, body] of Object.entries(response.content ?? {})) {
        lines.push(`    ${media} -> ${typeOf(body.schema)}`);
      }
    }
  }
  return lines.join("\n");
}

const server = new McpServer({ name: "agent-platform-api-docs", version: "0.1.0" });

server.registerTool("search_api", {
  title: "搜索平台 API",
  description: "只读搜索平台 OpenAPI：按关键词匹配路径/摘要/描述/标签，返回 method + path + 摘要。",
  inputSchema: {
    keyword: z.string().describe("关键词，如 deploy、workspace、settings；可为空"),
    tag: z.string().optional().describe("按 tag 过滤，如 environment-controller"),
    limit: z.number().int().min(1).max(20).optional().describe("返回条数，默认 10"),
  },
}, async ({ keyword, tag, limit }) => {
  try {
    const spec = await getSpec();
    const kw = (keyword ?? "").trim().toLowerCase();
    const tagFilter = (tag ?? "").trim().toLowerCase();
    const hits = [];
    for (const op of spec.operations.values()) {
      if (tagFilter && !op.tags.some((t) => t.toLowerCase().includes(tagFilter))) continue;
      if (kw && !op.haystack.includes(kw)) continue;
      hits.push(op);
    }
    const shown = hits.slice(0, limit ?? 10)
      .map((op) => `${op.method} ${op.path}\n    ${op.summary || "(无摘要)"}` +
        (op.tags.length ? `  [${op.tags.join(", ")}]` : ""))
      .join("\n");
    return { content: [{ type: "text",
      text: `${shown || "未匹配到接口"}\n${provenance(spec)}\nhint: get_api 查看参数与响应。` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("get_api", {
  title: "读取接口定义",
  description: "只读读取一个接口的完整签名：参数、请求体、响应。path 使用 OpenAPI 模板。",
  inputSchema: {
    method: z.string().describe("HTTP 方法，如 GET"),
    path: z.string().describe("路径模板，如 /api/v1/projects/{projectId}"),
  },
}, async ({ method, path: route }) => {
  try {
    const spec = await getSpec();
    const key = `${method.trim().toUpperCase()} ${route.trim()}`;
    const op = spec.operations.get(key);
    if (!op) {
      return { content: [{ type: "text",
        text: `未找到接口 ${key}\n${provenance(spec)}\nhint: 先用 search_api。` }] };
    }
    return { content: [{ type: "text", text: `${renderOperation(op)}\n${provenance(spec)}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("api_schema", {
  title: "读取模型定义",
  description: "只读读取 components.schemas 中的模型字段定义。",
  inputSchema: {
    name: z.string().describe("模型名，可带或不带 #/components/schemas 前缀"),
  },
}, async ({ name }) => {
  try {
    const spec = await getSpec();
    const wanted = name.trim().replace("#/components/schemas/", "");
    let schema = spec.schemas.get(wanted);
    let actual = wanted;
    if (!schema) {
      for (const [key, value] of spec.schemas) {
        if (key.toLowerCase() === wanted.toLowerCase()) { schema = value; actual = key; break; }
      }
    }
    if (!schema) {
      return { content: [{ type: "text",
        text: `未找到模型 ${name}。可用：${[...spec.schemas.keys()].join(", ")}\n${provenance(spec)}` }] };
    }
    return { content: [{ type: "text",
      text: `schema: ${actual}\n${JSON.stringify(schema, null, 2)}\n${provenance(spec)}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("list_tags", {
  title: "列出 API 标签",
  description: "只读列出 OpenAPI 中的 tag 及接口数量，便于按模块浏览。",
  inputSchema: {},
}, async () => {
  try {
    const spec = await getSpec();
    const counts = new Map();
    for (const op of spec.operations.values()) {
      const tags = op.tags.length ? op.tags : ["(untagged)"];
      for (const tag of tags) counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }
    const rows = [...counts.entries()].sort((a, b) => b[1] - a[1])
      .map(([tag, count]) => `- ${tag}: ${count}`).join("\n");
    return { content: [{ type: "text", text: `${rows}\n${provenance(spec)}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("tool_search", {
  title: "搜索平台 Agent 工具",
  description: "只读搜索平台导出的 agent-tools.json：名称/描述/工具面，返回 readOnly 与所属工具面。",
  inputSchema: {
    keyword: z.string().describe("关键词，如 deploy、git、env；可为空"),
    read_only: z.boolean().optional().describe("只返回只读工具"),
    limit: z.number().int().min(1).max(30).optional(),
  },
}, async ({ keyword, read_only: readOnlyOnly, limit }) => {
  try {
    const catalog = await getTools();
    if (!catalog) {
      return { content: [{ type: "text",
        text: "没有找到 agent-tools.json。启动一次平台会写入 data/api/agent-tools.json。" }] };
    }
    const kw = (keyword ?? "").trim().toLowerCase();
    const hits = catalog.tools.filter((tool) =>
      (!readOnlyOnly || tool.readOnly === true) &&
      (!kw || `${tool.name} ${tool.description ?? ""} ${(tool.toolkits ?? []).join(" ")}`
        .toLowerCase().includes(kw))).slice(0, limit ?? 10);
    const rows = hits.map((tool) =>
      `- ${tool.name}${tool.readOnly ? " [readOnly]" : ""}\n    ${tool.description ?? ""}\n` +
      `    toolkits: ${(tool.toolkits ?? []).join(", ")}`).join("\n");
    return { content: [{ type: "text",
      text: `${rows || "未匹配到工具"}\nsource: ${catalog.source}\ngeneratedAt: ${catalog.generatedAt}` +
        `；tools=${catalog.tools.length}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("tool_get", {
  title: "读取工具定义",
  description: "只读读取 agent-tools.json 中某个工具的参数定义。",
  inputSchema: { name: z.string().describe("工具名，如 ci_run") },
}, async ({ name }) => {
  try {
    const catalog = await getTools();
    if (!catalog) {
      return { content: [{ type: "text", text: "没有找到 agent-tools.json。" }] };
    }
    const tool = catalog.tools.find((item) => item.name === name.trim());
    if (!tool) {
      return { content: [{ type: "text", text: `未找到工具 ${name}。先用 tool_search。` }] };
    }
    return { content: [{ type: "text",
      text: `tool: ${tool.name}\nreadOnly: ${tool.readOnly}\ntoolkits: ${(tool.toolkits ?? []).join(", ")}` +
        `\ndescription:\n${tool.description ?? ""}\nparameters:\n` +
        `${JSON.stringify(tool.parameters ?? {}, null, 2)}\nsource: ${catalog.source}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("audit_frontend_api", {
  title: "审计前端请求与后端契约",
  description: "只读扫描前端 src/api 下的 apiFetch / apiBlob 调用，与 OpenAPI 逐条比对路径、方法、" +
    "路径参数、查询参数与 2xx 响应，返回带 file:line 的问题清单。",
  inputSchema: {
    frontend_dir: z.string().optional()
      .describe("前端 src/api 目录，默认本仓库 platform/web/src/api"),
    include_warnings: z.boolean().optional().describe("是否包含 warning，默认 true"),
  },
}, async ({ frontend_dir, include_warnings }) => {
  try {
    const spec = await getSpec();
    const dir = path.resolve(frontend_dir ?? frontendDir);
    if (!existsSync(dir)) {
      return { content: [{ type: "text", text: `前端目录不存在：${dir}` }] };
    }
    const withWarnings = include_warnings ?? true;
    const report = auditFrontend({
      openapi: spec.root, frontendDir: dir, includeWarnings: withWarnings,
    });
    return { content: [{ type: "text",
      text: `${formatReport(report, { includeWarnings: withWarnings })}\n${provenance(spec)}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

server.registerTool("search_frontend_api", {
  title: "检索前端请求封装",
  description: "只读索引前端 src/api 中的请求函数（函数名、HTTP 方法、路径、返回类型），" +
    "用于生成代码或契约比对前了解现有封装。",
  inputSchema: {
    keyword: z.string().describe("关键词，如 project、settings、git-issues；可为空"),
    frontend_dir: z.string().optional().describe("前端 src/api 目录"),
    limit: z.number().int().min(1).max(50).optional().describe("返回条数，默认 10"),
  },
}, async ({ keyword, frontend_dir, limit }) => {
  try {
    const spec = await getSpec();
    const dir = path.resolve(frontend_dir ?? frontendDir);
    if (!existsSync(dir)) {
      return { content: [{ type: "text", text: `前端目录不存在：${dir}` }] };
    }
    const hits = searchFrontendApi({
      frontendDir: dir, keyword: keyword ?? "", limit: limit ?? 10,
    });
    const rows = hits.map((hit) =>
      `- ${hit.name} → ${hit.method} ${hit.path}\n    ${hit.file}` +
      `${hit.returns ? ` · 返回 ${hit.returns}` : ""}`).join("\n");
    return { content: [{ type: "text",
      text: `${rows || "未匹配到前端请求函数"}\n${provenance(spec)}` }] };
  } catch (error) {
    return { isError: true, content: [{ type: "text", text: String(error.message ?? error) }] };
  }
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write(`agent-platform-api-docs-mcp 已启动` +
    `${specFile ? `，spec=${specFile}` : `，url=${args.url ?? "(未配置)"}`}` +
    `${toolsFile ? `，tools=${toolsFile}` : "，tools=(未找到)"}\n`);
}

main().catch((error) => {
  process.stderr.write(`启动失败：${error.stack ?? error}\n`);
  process.exit(1);
});
