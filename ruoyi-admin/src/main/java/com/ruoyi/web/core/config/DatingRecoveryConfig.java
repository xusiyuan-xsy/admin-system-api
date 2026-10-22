package com.ruoyi.web.core.config;

import com.ruoyi.system.service.DatingRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="dating.recovery.enabled", havingValue="true", matchIfMissing=true)
public class DatingRecoveryConfig
{
    private final DatingRecoveryService recovery;
    public DatingRecoveryConfig(DatingRecoveryService recovery) { this.recovery = recovery; }

    @Scheduled(fixedDelayString="${dating.recovery.delay-ms:30000}", initialDelayString="${dating.recovery.delay-ms:30000}")
    public void recover() { recovery.recover(); }
}
