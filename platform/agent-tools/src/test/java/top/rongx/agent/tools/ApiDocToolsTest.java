package top.rongx.agent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api_search / api_get / api_schema 的解析与渲染（不启动 Spring）。
 */
class ApiDocToolsTest {

    private static final String SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "测试平台", "version": "v1"},
              "paths": {
                "/api/v1/deploy/{projectId}": {
                  "post": {
                    "tags": ["deploy"],
                    "summary": "触发部署",
                    "operationId": "deploy",
                    "parameters": [
                      {"name": "projectId", "in": "path", "required": true, "schema": {"type": "string"}}
                    ],
                    "requestBody": {
                      "required": true,
                      "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DeployRequest"}}}
                    },
                    "responses": {"200": {"description": "ok",
                      "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DeployResult"}}}}}
                  }
                },
                "/api/v1/projects": {
                  "get": {"tags": ["project"], "summary": "列出项目", "responses": {"200": {"description": "ok"}}}
                }
              },
              "components": {"schemas": {
                "DeployRequest": {"type": "object", "properties": {"env": {"type": "string"}}},
                "DeployResult": {"type": "object", "properties": {"ok": {"type": "boolean"}}}
              }}
            }
            """;

    @Test
    @DisplayName("按关键词搜索并给出 provenance")
    void searchFindsOperations() throws IOException {
        ApiDocTools tools = tools();
        String result = tools.search("deploy", null, 5);
        assertTrue(result.contains("POST /api/v1/deploy/{projectId}"), result);
        assertTrue(result.contains("触发部署"), result);
        assertTrue(result.contains("operations=2"), result);
        assertTrue(result.contains("source: "), result);
    }

    @Test
    @DisplayName("读取接口参数、请求体与响应")
    void getRendersSignature() throws IOException {
        ApiDocTools tools = tools();
        String result = tools.get("post", "/api/v1/deploy/{projectId}");
        assertTrue(result.contains("POST /api/v1/deploy/{projectId}"), result);
        assertTrue(result.contains("projectId (path, required)"), result);
        assertTrue(result.contains("DeployRequest"), result);
        assertTrue(result.contains("DeployResult"), result);
    }

    @Test
    @DisplayName("读取模型字段，找不到时给出可用列表")
    void schemaLookup() throws IOException {
        ApiDocTools tools = tools();
        assertTrue(tools.schema("DeployRequest").contains("\"env\""), "应返回字段定义");
        assertTrue(tools.schema("#/components/schemas/DeployResult").contains("\"ok\""), "应接受带前缀的名称");
        assertTrue(tools.schema("Nope").contains("可用模型"), "未知模型应给出候选");
    }

    @Test
    @DisplayName("没有快照时给出可操作提示而不是抛异常")
    void missingSnapshotIsGraceful() {
        ApiDocTools tools = new ApiDocTools("/tmp/does-not-exist-" + System.nanoTime() + ".json");
        String result = tools.search("deploy", null, 5);
        assertTrue(result.contains("没有可用的 OpenAPI 快照"), result);
    }

    private ApiDocTools tools() throws IOException {
        Path file = Files.createTempFile("openapi-test", ".json");
        Files.writeString(file, SPEC, StandardCharsets.UTF_8);
        return new ApiDocTools(file.toString());
    }
}
