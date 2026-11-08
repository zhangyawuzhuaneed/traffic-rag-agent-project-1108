package com.example.tunnel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动即校验凭证，避免「配置错了但一直跑到某个远程调用才 401」这种静默失败。
 *
 * <p>这是把你熟悉的后端习惯搬到 AI 项目里最典型的一处：
 * 普通数据库连接池会在启动时校验连通性，LLM 客户端默认不会。
 * 没有这道守卫，一个没配 Key 的服务能正常启动、健康检查全绿，
 * 直到第一个用户提问才炸，排查成本高得多。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KeyGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeyGuard.class);

    private final Environment environment;

    public KeyGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String key = environment.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank() || key.startsWith("__MISSING_") || key.startsWith("your_")) {
            throw new IllegalStateException("""
                    未检测到有效的 ZHIPU_API_KEY。

                    修复方式（任选其一）：
                      1) PowerShell:  $env:ZHIPU_API_KEY="你的key"
                      2) Git Bash  :  export ZHIPU_API_KEY="你的key"
                      3) 把 .env.example 复制为 .env 后填入，再用 IDE 的 EnvFile 插件加载

                    Key 申请：https://bigmodel.cn/usercenter/proj-mgmt/apikeys
                    注意：.env 已在 .gitignore 中，任何时候都不要提交真实 Key。
                    """);
        }
        log.info("凭证校验通过，模型端点：{}",
                environment.getProperty("spring.ai.openai.base-url", "未配置"));
    }
}
