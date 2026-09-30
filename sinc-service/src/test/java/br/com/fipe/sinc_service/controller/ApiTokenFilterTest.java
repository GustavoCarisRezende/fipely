package br.com.fipe.sinc_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiTokenFilterTest {
    private final ApiTokenFilter filter = new ApiTokenFilter("test-secret");

    @Test
    void deniesMissingAndIncorrectTokens() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), missing, new MockFilterChain());
        assertEquals(401, missing.getStatus());

        MockHttpServletRequest incorrect = new MockHttpServletRequest();
        incorrect.addHeader("X-API-Token", "different");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(incorrect, rejected, new MockFilterChain());
        assertEquals(401, rejected.getStatus());
    }

    @Test
    void acceptsCorrectToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Token", "test-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }
}
