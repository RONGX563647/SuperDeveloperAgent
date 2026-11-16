/**
 * 冒烟测试：通过 stdio 连上本进程，验证 initialize / tools.list / tools.call。
 * 运行：node smoke-test.mjs   （需先 npm install）
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import path from "node:path";
import assert from "node:assert/strict";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const spec = path.resolve(HERE, "../../platform/agent-gateway/src/main/resources/api/openapi.json");
const tools = path.resolve(HERE, "../../platform/data/api/agent-tools.json");
const frontend = path.resolve(HERE, "../../platform/web/src/api");

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["server.js", "--file", spec, "--tools", tools, "--frontend", frontend],
  cwd: HERE,
  stderr: "pipe",
});

const client = new Client({ name: "api-docs-mcp-smoke", version: "0.1.0" });
try {
  await client.connect(transport);
  const listed = await client.listTools();
  const names = listed.tools.map((tool) => tool.name).sort();
  console.log(`tools(${names.length}): ${names.join(", ")}`);
  for (const required of ["audit_frontend_api", "search_frontend_api", "search_api", "get_api"]) {
    assert.ok(names.includes(required), `缺少工具 ${required}`);
  }

  const search = await client.callTool({
    name: "search_api",
    arguments: { keyword: "project", limit: 3 },
  });
  console.log("--- search_api ---");
  console.log(search.content?.[0]?.text ?? search);

  const tags = await client.callTool({ name: "list_tags", arguments: {} });
  console.log("--- list_tags ---");
  console.log(tags.content?.[0]?.text?.split("\n").slice(0, 6).join("\n") ?? tags);

  const toolSearch = await client.callTool({
    name: "tool_search",
    arguments: { keyword: "deploy", limit: 3 },
  });
  console.log("--- tool_search ---");
  console.log(toolSearch.content?.[0]?.text ?? toolSearch);

  const audit = await client.callTool({ name: "audit_frontend_api", arguments: {} });
  console.log("--- audit_frontend_api ---");
  console.log(audit.content?.[0]?.text ?? audit);

  const frontendSearch = await client.callTool({
    name: "search_frontend_api",
    arguments: { keyword: "project", limit: 3 },
  });
  console.log("--- search_frontend_api ---");
  console.log(frontendSearch.content?.[0]?.text ?? frontendSearch);
} finally {
  await client.close();
}
