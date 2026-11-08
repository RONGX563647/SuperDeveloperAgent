package top.rongx.agent.domain.settings;

/**
 * 部署服务器配置（设置页管理；替代 application.yml 里的 agent.deploy.server.*）。
 *
 * @param id         主键
 * @param name       展示名
 * @param host       主机
 * @param port       SSH 端口
 * @param sshUser    SSH 用户
 * @param authType   KEY（私钥路径）| PASSWORD（密码，密文落库）
 * @param keyPath    私钥路径（authType=KEY）
 * @param remoteDir  远端部署目录
 * @param enabled    是否启用
 * @param isDefault  是否默认（同表唯一）
 * @param hasSecret  是否已保存密码/口令（接口回显用，不返回明文）
 * @param secretMasked 密码掩码（仅列表展示）
 * @param lastTestAt 最近一次连通性测试时间（epoch ms）
 * @param lastTestOk 最近一次测试是否成功
 * @param lastTestMsg 最近一次测试结果说明
 * @param updatedBy  最后修改人
 * @param updatedAt  最后修改时间
 */
public record DeployServer(
        String id,
        String name,
        String host,
        int port,
        String sshUser,
        String authType,
        String keyPath,
        String remoteDir,
        boolean enabled,
        boolean isDefault,
        boolean hasSecret,
        String secretMasked,
        Long lastTestAt,
        Boolean lastTestOk,
        String lastTestMsg,
        String updatedBy,
        long updatedAt) {
}
