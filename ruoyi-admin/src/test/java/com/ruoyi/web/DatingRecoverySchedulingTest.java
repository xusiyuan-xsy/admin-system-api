package com.ruoyi.web;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.ruoyi.system.service.DatingRecoveryService;
import com.ruoyi.web.core.config.DatingRecoveryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatingRecoverySchedulingTest
{
    @Configuration
    @Import(DatingRecoveryConfig.class)
    static class Config
    {
        @Bean DatingRecoveryService recovery() { return mock(DatingRecoveryService.class); }
    }

    AnnotationConfigApplicationContext context(boolean enabled)
    {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
            Map.of("dating.recovery.enabled", String.valueOf(enabled), "dating.recovery.delay-ms", "100")));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test void actualScheduledTriggerContinuesAfterAnException() throws Exception
    {
        try (var context = context(true))
        {
            var calls = new AtomicInteger();
            var recovered = new CountDownLatch(1);
            doAnswer(invocation -> {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("Injected scheduler failure");
                recovered.countDown();
                return null;
            }).when(context.getBean(DatingRecoveryService.class)).recover();
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2);
        }
    }

    @Test void disabledConfigurationRegistersNoScheduledRecovery() throws Exception
    {
        try (var context = context(false))
        {
            assertTrue(context.getBeansOfType(DatingRecoveryConfig.class).isEmpty());
            Thread.sleep(250);
            verifyNoInteractions(context.getBean(DatingRecoveryService.class));
        }
    }
}
