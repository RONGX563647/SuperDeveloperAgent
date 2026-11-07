package org.example.agent.domain.project;

/**
 * 一个被托管项目（规约 §1 / 多项目托管 v4）。
 *
 * @param id          项目标识，如 {@code RY}
 * @param name        显示名
 * @param repoUrl     代码仓库地址
 * @param repoType    仓库类型：gitee / gitlab / local
 * @param type        项目类型：ruoyi-plus / generic-java / vue-only
 * @param portRangeStart 端口段起点（含）
 * @param portRangeEnd   端口段终点（含）
 */
public record Project(
        String id,
        String name,
        String repoUrl,
        String repoType,
        String type,
        int portRangeStart,
        int portRangeEnd) {

    public Project {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("project id 不能为空");
        }
        if (portRangeStart > portRangeEnd) {
            throw new IllegalArgumentException("端口段不合法: " + portRangeStart + ">" + portRangeEnd);
        }
    }

    /** 端口是否落在本项目段内（规约：控制器只允许段内操作）。 */
    public boolean allowsPort(int port) {
        return port >= portRangeStart && port <= portRangeEnd;
    }
}
