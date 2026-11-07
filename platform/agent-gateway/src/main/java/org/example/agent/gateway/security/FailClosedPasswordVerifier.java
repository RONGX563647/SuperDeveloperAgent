package org.example.agent.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 未接入账号体系时的失败关闭实现：高危确认一律拒绝，并留下告警日志。 */
@Component
public class FailClosedPasswordVerifier implements PasswordVerifier {

    private static final Logger log = LoggerFactory.getLogger(FailClosedPasswordVerifier.class);

    @Override
    public boolean verify(String userId, String rawPassword) {
        log.warn("账号体系未接入，高危确认被拒绝: userId={}", userId);
        return false;
    }
}
