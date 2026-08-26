package com.award.log.integration;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

final class IntegrationTestSupport {

    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "XMLHttpRequest";

    private IntegrationTestSupport() {
    }

    static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder, MockHttpSession session) {
        return builder.session(session).header(CSRF_HEADER, CSRF_VALUE);
    }

    static MockHttpSession registerAndLogin(MockMvc mockMvc, String username) throws Exception {
        return registerAndLogin(mockMvc, username, null);
    }

    static MockHttpSession registerAndLogin(MockMvc mockMvc, String username, JdbcTemplate jdbc) throws Exception {
        String uniqueUser = "it_" + UUID.randomUUID().toString().replace("-", "");
        String password = "TestPass123!";
        String email = uniqueUser + "@test.local";

        if (jdbc != null) {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            jdbc.update(
                    "INSERT INTO sys_user (username, password, role, email, create_time, update_time) "
                            + "VALUES (?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    uniqueUser, encoder.encode(password), email);
        } else {
            mockMvc.perform(post("/admin/user/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + uniqueUser + "\",\"password\":\"" + password + "\",\"email\":\""
                                    + email + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        MockHttpSession session = new MockHttpSession();
        MvcResult login = mockMvc.perform(post("/admin/user/login")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uniqueUser + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
