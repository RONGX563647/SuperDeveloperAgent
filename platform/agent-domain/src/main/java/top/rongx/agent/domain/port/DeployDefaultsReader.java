package top.rongx.agent.domain.port;

import top.rongx.agent.domain.deploy.DeployDefaults;

/** 读取项目平台侧流水线的 deploy 段；无定义或解析失败返回 {@code null}。 */
public interface DeployDefaultsReader {

    DeployDefaults read(String projectId);
}
