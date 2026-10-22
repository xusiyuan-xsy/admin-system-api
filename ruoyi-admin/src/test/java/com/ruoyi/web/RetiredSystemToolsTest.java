package com.ruoyi.web;

import com.ruoyi.web.controller.dating.DatingPhotoController;
import com.ruoyi.web.controller.dating.DatingRecoveryController;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;

class RetiredSystemToolsTest
{
    @Test
    void retiredControllersAndLibrariesAreNotPackaged()
    {
        for (String type : new String[] {
            "com.ruoyi.generator.controller.GenController",
            "com.ruoyi.web.controller.tool.TestController",
            "com.ruoyi.web.core.config.SwaggerConfig",
            "com.ruoyi.common.constant.GenConstants",
            "org.springdoc.core.configuration.SpringDocConfiguration",
            "org.apache.velocity.app.Velocity"
        })
        {
            assertFalse(ClassUtils.isPresent(type, getClass().getClassLoader()), type);
        }
    }

    @Test
    void businessRoutesRemainRegisteredWithoutOpenApi()
    {
        try (var context = new StaticWebApplicationContext())
        {
            context.setServletContext(new MockServletContext());
            // Only inspect mappings; no controller method or business service is invoked.
            context.getBeanFactory().registerSingleton("photos", new DatingPhotoController(null, null, null));
            context.getBeanFactory().registerSingleton("recovery", new DatingRecoveryController(null, null, null, null));
            context.refresh();
            var mappings = new RequestMappingHandlerMapping();
            mappings.setApplicationContext(context);
            mappings.afterPropertiesSet();
            Set<String> actual = new HashSet<>();
            mappings.getHandlerMethods().keySet().forEach(mapping ->
                mapping.getPatternValues().forEach(path ->
                    mapping.getMethodsCondition().getMethods().forEach(method ->
                        actual.add(method.name() + " " + path))));
            assertEquals(Set.of(
                "POST /dating/archives/{archiveId}/photos",
                "GET /dating/archives/{archiveId}/photos/{fileName}",
                "GET /dating/recovery/overview",
                "GET /dating/recovery/runs",
                "GET /dating/deduction-exceptions",
                "GET /dating/deduction-tasks/{id}/history",
                "GET /dating/deduction-tasks/{id}/follow-up",
                "GET /dating/deduction-tasks/{id}/follow-up/history",
                "POST /dating/deduction-tasks/{id}/follow-up"
            ), actual);
        }
    }
}
