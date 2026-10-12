package com.nytryx.burnai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.repo.DecisionConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecisionConfigAdminApiTest {
    @Test
    void diseaseListExposesAdminEnvelopeForFrontend() throws Exception {
        DecisionConfigRepository repository = mock(DecisionConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class))).thenReturn(java.util.List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DecisionConfigAdminApiController(jdbc, repository, new ObjectMapper(), null, null)).build();

        mvc.perform(get("/api/admin/diseases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}
