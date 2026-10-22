package com.ruoyi.web;

import com.ruoyi.system.domain.DatingServiceOption;
import com.ruoyi.system.service.DatingBillingService;
import com.ruoyi.web.controller.dating.DatingBillingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DatingServiceOptionControllerTest
{
    private DatingBillingService service;
    private MockMvc mvc;

    @BeforeEach void setup()
    {
        service = mock(DatingBillingService.class);
        var controller = new DatingBillingController(service) {
            @Override public String getUsername() { return "alice"; }
        };
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void acceptsCreationWithoutCodeAndDiscardsClientId() throws Exception
    {
        mvc.perform(post("/dating/service-options").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"optionId":123,"name":"Follow-up","completionStandard":"Confirmed","status":"0"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(service).saveOption(argThat((DatingServiceOption option) ->
            option.getOptionId() == null && option.getCode() == null && "Follow-up".equals(option.getName())), eq("alice"));
    }

    @Test void emptyLegacyCodeDoesNotBlockAutomaticGeneration() throws Exception
    {
        mvc.perform(post("/dating/service-options").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code":"","name":"Follow-up","completionStandard":"Confirmed","status":"0"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(service).saveOption(any(DatingServiceOption.class), eq("alice"));
    }

    @Test void missingRequiredBusinessFieldsStillFailValidation() throws Exception
    {
        mvc.perform(post("/dating/service-options").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"","completionStandard":"Confirmed","status":"0"}
                """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
