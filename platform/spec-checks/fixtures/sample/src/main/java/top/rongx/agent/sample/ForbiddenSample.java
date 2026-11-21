package top.rongx.agent.sample;

import java.util.Date;

/**
 * 反例样例：每一行都故意违反一条禁令规则，用来验证规则本身有效。
 *
 * <p>本文件只被 {@code RuleFixtureSelfTest} 扫描；生产扫描（root = platform/）
 * 会跳过整个 {@code spec-checks/}，因此不会污染真实门禁结论。
 * 新增规则时必须同步在这里补一行样例，否则自测会红。
 */
class ForbiddenSample {

    @Autowired
    private Object injected;

    void r01() throws Exception {
        Runtime.getRuntime().exec("whoami");
    }

    void r02() {
        new ProcessBuilder("sh", "-c", "echo hi");
    }

    void r04() {
        new Thread(() -> { }).start();
    }

    void r06(double feeAmount) {
        double costTotal = feeAmount;
    }

    void r07(String password) {
        log.info("login password=" + password);
    }

    String r08() {
        return "SELECT * FROM users";
    }

    Date r09() {
        return new Date();
    }

    void r10() {
        try {
            r01();
        } catch (Exception e) {
        }
    }

    void r11() {
        System.out.println("debug");
    }

    void r12() {
        eval("1+1");
    }

    boolean r13() {
        return is_deleted;
    }

    String r14() {
        return "E-123";
    }

    /** R15：waitFor 之前的无界读流（CLI 不退出 = 超时不存在）。 */
    String r15() throws java.io.IOException {
        Process process = new ProcessBuilder("docker", "info").start();
        byte[] output = process.getInputStream().readAllBytes();
        process.waitFor();
        return new String(output);
    }
}
