#!/usr/bin/env node
/**
 * 前端契约审计：把 platform/web/src/api/*.ts 里的请求调用与 OpenAPI 快照逐条比对。
 *
 * 设计边界：
 * - 只读：读取 OpenAPI JSON 与前端源码，不修改任何文件、不调用业务接口；
 * - 确定性：解析 TS 语法树，不依赖模型判断；
 * - 可解释：每条问题都带 file/line/HTTP 方法/路径与错误码，便于 Agent 直接修复。
 *
 * 用法：
 *   node audit.mjs [--openapi <file>] [--frontend <dir>] [--json] [--strict] [--no-warnings]
 *   node audit.mjs --strict   # 有 error 级问题时退出码 1，可用于 CI
 */
import ts from "typescript";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../..");
export const DEFAULT_OPENAPI = path.resolve(
  REPO_ROOT,
  "platform/agent-gateway/src/main/resources/api/openapi.json",
);
export const DEFAULT_FRONTEND = path.resolve(REPO_ROOT, "platform/web/src/api");

const METHODS = ["get", "post", "put", "patch", "delete", "head", "options", "trace"];
const FETCH_CALLS = new Set(["apiFetch", "apiBlob"]);
/** 这些变量名按查询串处理（前端约定：qs/query 拼在路径尾部）。 */
const QUERY_VARS = new Set(["qs", "q", "query", "querystring", "search"]);

function skeleton(route) {
  const trimmed = route.replace(/\/+$/, "") || "/";
  return trimmed.replace(/\{[^}]*\}/g, "{}");
}

function listFiles(dir) {
  return readdirSync(dir)
    .filter((name) => name.endsWith(".ts") && !name.endsWith(".d.ts"))
    .sort()
    .map((name) => path.join(dir, name));
}

/** 把 OpenAPI paths 建成「骨架 → 各方法操作」索引，骨架忽略路径参数名。 */
export function indexOpenApi(openapi) {
  const index = new Map();
  for (const [route, item] of Object.entries(openapi?.paths ?? {})) {
    const key = skeleton(route);
    const paramNames = [...route.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
    const entry = index.get(key) ?? { route, paramNames, operations: new Map() };
    for (const method of METHODS) {
      const node = item?.[method];
      if (node && typeof node === "object") entry.operations.set(method.toUpperCase(), node);
    }
    index.set(key, entry);
  }
  return index;
}

/** 简单插值：标识符或 encodeURIComponent(标识符)。 */
function simpleName(expr) {
  if (ts.isIdentifier(expr)) return expr.text;
  if (
    ts.isCallExpression(expr) &&
    ts.isIdentifier(expr.expression) &&
    expr.expression.text === "encodeURIComponent" &&
    expr.arguments.length === 1 &&
    ts.isIdentifier(expr.arguments[0])
  ) {
    return expr.arguments[0].text;
  }
  return null;
}

/** 条件插值（`${cond ? '?a=1' : ''}`）视为查询串后缀，路径在此之前结束。 */
function isConditionalQuery(expr) {
  if (!ts.isConditionalExpression(expr)) return false;
  return [expr.whenTrue, expr.whenFalse].some((branch) => {
    const text = branch.getText();
    return text.includes("?");
  });
}

function parsePathExpression(node, sf) {
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
    return { path: node.text, unsupported: null, queryExpression: "" };
  }
  if (!ts.isTemplateExpression(node)) {
    return { path: null, unsupported: `无法解析的路径表达式：${node.getText(sf)}`, queryExpression: "" };
  }
  let out = node.head.text;
  for (const span of node.templateSpans) {
    const expr = span.expression;
    const name = simpleName(expr);
    if (name) {
      // `/runs${qs}` 这类尾部变量按查询串处理，避免误判为路径参数
      if (QUERY_VARS.has(name.toLowerCase())) {
        return { path: out, unsupported: null, queryExpression: expr.getText(sf) };
      }
      out += `{${name}}`;
      out += span.literal.text;
      continue;
    }
    if (isConditionalQuery(expr)) {
      return { path: out, unsupported: null, queryExpression: expr.getText(sf) };
    }
    return {
      path: null,
      unsupported: `不支持的路径插值：${expr.getText(sf)}（只支持变量或 encodeURIComponent(变量)）`,
      queryExpression: "",
    };
  }
  return { path: out, unsupported: null, queryExpression: "" };
}

/** 在调用所在函数（或文件）里查找同名变量声明的初始化表达式。 */
function findVariableInitializer(identifier, sf) {
  let scope = identifier.parent;
  while (scope && !ts.isFunctionLike(scope) && !ts.isSourceFile(scope)) scope = scope.parent;
  if (!scope) return null;
  let found = null;
  const visit = (node) => {
    if (found) return;
    if (
      ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) &&
      node.name.text === identifier.text && node.initializer
    ) {
      found = node.initializer;
      return;
    }
    ts.forEachChild(node, visit);
  };
  visit(scope);
  return found;
}

/** 支持三元表达式（如 `id ? 详情路径 : 集合路径`）的路径解析，返回所有静态分支。 */
function parsePathAlternatives(node, sf, depth = 0) {
  if (ts.isParenthesizedExpression(node)) return parsePathAlternatives(node.expression, sf, depth);
  if (ts.isIdentifier(node) && depth < 3) {
    const initializer = findVariableInitializer(node, sf);
    if (initializer) return parsePathAlternatives(initializer, sf, depth + 1);
  }
  if (ts.isConditionalExpression(node) && depth < 3) {
    const left = parsePathAlternatives(node.whenTrue, sf, depth + 1);
    const right = parsePathAlternatives(node.whenFalse, sf, depth + 1);
    if (left.unsupported) return left;
    if (right.unsupported) return right;
    return {
      alternatives: [...left.alternatives, ...right.alternatives],
      unsupported: null,
    };
  }
  const parsed = parsePathExpression(node, sf);
  if (parsed.unsupported) return { alternatives: [], unsupported: parsed.unsupported };
  return {
    alternatives: [{ path: parsed.path, queryExpression: parsed.queryExpression }],
    unsupported: null,
  };
}

/** 解析请求方法；三元表达式按分支顺序返回，便于与路径分支配对。 */
function parseMethodAlternatives(node, sf) {
  const init = node.arguments[1];
  if (!init) return { methods: ["GET"], known: true };
  if (!ts.isObjectLiteralExpression(init)) return { methods: ["GET"], known: false };
  const prop = init.properties.find(
    (item) =>
      ts.isPropertyAssignment(item) &&
      (ts.isIdentifier(item.name) ? item.name.text : item.name.getText(sf)) === "method",
  );
  if (!prop) return { methods: ["GET"], known: true };
  const collect = (expr, depth = 0) => {
    if (ts.isStringLiteralLike(expr)) return [expr.text.toUpperCase()];
    if (ts.isParenthesizedExpression(expr)) return collect(expr.expression, depth);
    if (ts.isConditionalExpression(expr) && depth < 3) {
      const left = collect(expr.whenTrue, depth + 1);
      const right = collect(expr.whenFalse, depth + 1);
      return left && right ? [...left, ...right] : null;
    }
    if (ts.isIdentifier(expr) && depth < 3) {
      const initializer = findVariableInitializer(expr, sf);
      return initializer ? collect(initializer, depth + 1) : null;
    }
    return null;
  };
  const methods = collect(prop.initializer);
  return methods ? { methods, known: true } : { methods: ["GET"], known: false };
}

/** 在调用所在函数（或文件）里收集 URLSearchParams 声明的查询参数名。 */
function scopeQueryKeys(call, sf) {
  let scope = call.parent;
  while (scope && !ts.isFunctionLike(scope) && !ts.isSourceFile(scope)) scope = scope.parent;
  if (!scope) return [];
  const keys = new Set();
  const visit = (node) => {
    if (
      ts.isNewExpression(node) &&
      node.expression.getText(sf) === "URLSearchParams" &&
      node.arguments?.length === 1 &&
      ts.isObjectLiteralExpression(node.arguments[0])
    ) {
      for (const prop of node.arguments[0].properties) {
        if (ts.isPropertyAssignment(prop) || ts.isShorthandPropertyAssignment(prop)) {
          keys.add(prop.name.getText(sf));
        }
      }
    }
    if (
      ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(node.expression) &&
      ["set", "append"].includes(node.expression.name.text) &&
      node.arguments.length >= 1 &&
      ts.isStringLiteralLike(node.arguments[0])
    ) {
      keys.add(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  };
  visit(scope);
  return [...keys];
}

function splitQuery(rawPath, queryExpression) {
  const index = rawPath.indexOf("?");
  const pathPart = index >= 0 ? rawPath.slice(0, index) : rawPath;
  const queryPart = index >= 0 ? rawPath.slice(index + 1) : "";
  const queryVarNames = [];
  const queryLiteralNames = new Set();

  for (const match of queryPart.matchAll(/\{([^}]+)\}/g)) {
    const name = match[1];
    // `${qs}` 这类变量形态无法直接确定参数名，交给作用域解析
    if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) queryVarNames.push(name);
  }
  // 字面量形态：`?a=1&b=`
  for (const match of (`?${queryPart}${queryExpression}`)
    .matchAll(/[?&]([A-Za-z_][A-Za-z0-9_]*)=/g)) {
    queryLiteralNames.add(match[1]);
  }
  return { path: pathPart, queryVarNames, queryLiteralNames: [...queryLiteralNames] };
}

function pathParamNames(pathPart) {
  return [...pathPart.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
}

/** 解析单个文件里的 apiFetch / apiBlob 调用。 */
export function extractCalls(sourceText, fileName) {
  const sf = ts.createSourceFile(fileName, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const calls = [];
  const visit = (node) => {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      FETCH_CALLS.has(node.expression.text)
    ) {
      const fn = node.expression.text;
      const parsed = parsePathAlternatives(node.arguments[0], sf);
      const { line, character } = sf.getLineAndCharacterOfPosition(node.getStart(sf));
      const methods = fn === "apiBlob"
        ? { methods: ["GET"], known: true }
        : parseMethodAlternatives(node, sf);
      const location = { file: fileName, line: line + 1, column: character + 1 };
      const responseType = node.typeArguments?.[0]?.getText(sf) ?? null;
      if (parsed.unsupported) {
        calls.push({
          ...location, fn, method: methods.methods[0], methodKnown: methods.known,
          path: null, pathParams: [], queryVarNames: [], queryLiteralNames: [], scopeKeys: [],
          responseType, unsupported: parsed.unsupported,
        });
      } else {
        const paired = methods.methods.length === parsed.alternatives.length;
        const scopeKeys = scopeQueryKeys(node, sf);
        for (const [index, alternative] of parsed.alternatives.entries()) {
          const { path: pathPart, queryVarNames, queryLiteralNames } = splitQuery(
            alternative.path,
            alternative.queryExpression,
          );
          calls.push({
            ...location, fn,
            method: paired ? methods.methods[index] : methods.methods[0],
            methodKnown: methods.known && (paired || methods.methods.length === 1),
            path: pathPart,
            pathParams: pathParamNames(pathPart),
            queryVarNames, queryLiteralNames, scopeKeys,
            responseType, unsupported: null,
          });
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return calls;
}

function hasSuccessResponse(operation) {
  return Object.keys(operation?.responses ?? {}).some((code) => /^2\d\d$/.test(code));
}

/** 审计一个前端目录，返回结构化报告。 */
export function auditFrontend({ openapi, frontendDir = DEFAULT_FRONTEND, includeWarnings = true }) {
  const index = indexOpenApi(openapi);
  const files = listFiles(frontendDir);
  const calls = [];
  for (const file of files) {
    const relative = path.relative(REPO_ROOT, file) || file;
    calls.push(...extractCalls(readFileSync(file, "utf8"), relative));
  }

  const issues = [];
  const push = (severity, code, call, message) => {
    if (severity === "warning" && !includeWarnings) return;
    issues.push({
      severity, code, file: call.file, line: call.line, column: call.column,
      method: call.method, path: call.path, message,
    });
  };

  for (const call of calls) {
    if (call.unsupported) {
      push("warning", "UNSUPPORTED_PATH", call, call.unsupported);
      continue;
    }
    if (!call.path || !call.path.startsWith("/")) {
      push("warning", "UNSUPPORTED_PATH", call, `路径不是以 / 开头的静态字符串：${call.path}`);
      continue;
    }
    if (!call.methodKnown) {
      push("warning", "METHOD_UNKNOWN", call, "method 不是字符串字面量，无法静态确定 HTTP 方法");
    }
    const skeletonKey = skeleton(call.path);
    const entry = index.get(skeletonKey);
    if (!entry) {
      push("error", "PATH_NOT_FOUND", call, `OpenAPI 中不存在路径 ${call.path}`);
      continue;
    }
    const operation = entry.operations.get(call.method);
    if (!operation) {
      push("error", "METHOD_NOT_FOUND", call, `${call.method} 未在 ${entry.route} 中声明`);
      continue;
    }

    const declaredPath = (operation.parameters ?? [])
      .filter((param) => param.in === "path").map((param) => param.name);
    if (declaredPath.length !== call.pathParams.length) {
      push(
        "error", "PATH_PARAM_MISMATCH", call,
        `路径参数数量不一致：前端 ${call.pathParams.length} 个（${call.pathParams.join(", ") || "无"}），` +
        `OpenAPI ${declaredPath.length} 个（${declaredPath.join(", ") || "无"}）`,
      );
    }

    const declaredQuery = (operation.parameters ?? []).filter((param) => param.in === "query");
    const declaredQueryNames = declaredQuery.map((param) => param.name);
    const resolvedQuery = new Set([...call.queryLiteralNames, ...call.scopeKeys]);
    if (call.queryVarNames.length === 0 || resolvedQuery.size > 0) {
      for (const name of resolvedQuery) {
        if (!declaredQueryNames.includes(name)) {
          push(
            "warning", "QUERY_PARAM_UNKNOWN", call,
            `查询参数 ${name} 未在 OpenAPI 中声明（已声明：${declaredQueryNames.join(", ") || "无"}）`,
          );
        }
      }
    }
    for (const param of declaredQuery.filter((item) => item.required)) {
      if (!resolvedQuery.has(param.name)) {
        push("warning", "QUERY_PARAM_MISSING", call, `缺少必填查询参数 ${param.name}`);
      }
    }

    if (call.fn !== "apiBlob" && !hasSuccessResponse(operation)) {
      push("warning", "NO_SUCCESS_RESPONSE", call, "OpenAPI 未定义任何 2xx 响应");
    }
  }

  issues.sort((a, b) =>
    a.file.localeCompare(b.file) || a.line - b.line || a.column - b.column);
  const errors = issues.filter((issue) => issue.severity === "error").length;
  return {
    generatedAt: new Date().toISOString(),
    frontendDir,
    spec: {
      title: openapi?.info?.title ?? "",
      version: openapi?.info?.version ?? "",
      paths: Object.keys(openapi?.paths ?? {}).length,
    },
    files: files.length,
    calls: calls.length,
    errors,
    warnings: issues.length - errors,
    issues,
  };
}

/** 索引前端请求函数（供 Agent 检索前端封装）。 */
export function searchFrontendApi({ frontendDir = DEFAULT_FRONTEND, keyword = "", limit = 10 }) {
  const entries = [];
  for (const file of listFiles(frontendDir)) {
    const relative = path.relative(REPO_ROOT, file) || file;
    const text = readFileSync(file, "utf8");
    const sf = ts.createSourceFile(relative, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    for (const statement of sf.statements) {
      let name = null;
      let body = null;
      let returns = null;
      if (ts.isFunctionDeclaration(statement) && statement.name) {
        name = statement.name.text;
        body = statement.body;
        returns = statement.type?.getText(sf).replace(/\s+/g, " ").trim() ?? null;
      } else if (ts.isVariableStatement(statement)) {
        for (const declaration of statement.declarationList.declarations) {
          if (!ts.isIdentifier(declaration.name)) continue;
          const initializer = declaration.initializer;
          if (
            initializer &&
            (ts.isArrowFunction(initializer) || ts.isFunctionExpression(initializer))
          ) {
            name = declaration.name.text;
            body = initializer.body;
            returns = initializer.type?.getText(sf).replace(/\s+/g, " ").trim() ?? null;
            break;
          }
        }
      }
      if (!name || !body) continue;
      const calls = extractCalls(body.getText(sf), relative);
      if (calls.length === 0) continue;
      const first = calls[0];
      entries.push({
        file: relative, name, method: first.method, path: first.path,
        returns, calls: calls.length,
      });
    }
  }
  const kw = keyword.trim().toLowerCase();
  return entries
    .filter((entry) =>
      !kw || `${entry.name} ${entry.method} ${entry.path} ${entry.returns ?? ""}`
        .toLowerCase().includes(kw))
    .slice(0, limit);
}

export function formatReport(report, { includeWarnings = true } = {}) {
  const lines = [
    `前端契约审计：${report.files} 个文件，${report.calls} 处请求调用`,
    `规范：${report.spec.title} ${report.spec.version}（${report.spec.paths} paths）`,
    `结果：${report.errors} error / ${report.warnings} warning`,
  ];
  const issues = includeWarnings
    ? report.issues
    : report.issues.filter((issue) => issue.severity === "error");
  for (const issue of issues) {
    lines.push(
      `- [${issue.severity.toUpperCase()}] ${issue.code} ${issue.file}:${issue.line} ` +
      `${issue.method} ${issue.path ?? "(动态路径)"}\n    ${issue.message}`,
    );
  }
  if (issues.length === 0) lines.push("未发现契约不一致。");
  return lines.join("\n");
}

function parseArgs(argv) {
  const options = {
    openapi: DEFAULT_OPENAPI,
    frontend: DEFAULT_FRONTEND,
    json: false,
    strict: false,
    warnings: true,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (key === "--openapi") options.openapi = path.resolve(argv[++i]);
    else if (key === "--frontend") options.frontend = path.resolve(argv[++i]);
    else if (key === "--json") options.json = true;
    else if (key === "--strict") options.strict = true;
    else if (key === "--no-warnings") options.warnings = false;
    else if (key === "--help" || key === "-h") options.help = true;
    else throw new Error(`未知参数：${key}`);
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(
      "用法：node audit.mjs [--openapi <file>] [--frontend <dir>] [--json] [--strict] [--no-warnings]\n",
    );
    return;
  }
  if (!existsSync(options.openapi)) {
    throw new Error(`OpenAPI 快照不存在：${options.openapi}`);
  }
  if (!statSync(options.frontend).isDirectory()) {
    throw new Error(`前端目录不存在：${options.frontend}`);
  }
  const openapi = JSON.parse(readFileSync(options.openapi, "utf8"));
  const report = auditFrontend({
    openapi, frontendDir: options.frontend, includeWarnings: options.warnings,
  });
  process.stdout.write(
    `${options.json ? JSON.stringify(report, null, 2) : formatReport(report, { includeWarnings: options.warnings })}\n`,
  );
  if (options.strict && report.errors > 0) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`审计失败：${error.message ?? error}\n`);
    process.exitCode = 1;
  });
}
