package com.yotto.basketball.config;

import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Slice test for {@link GlobalExceptionHandler}. Uses @WebMvcTest with a stub
 * controller ({@link ExceptionThrowingTestController}) that throws each exception
 * type the handler covers — keeps the test focused on the handler's contract,
 * not on whichever real controller happens to throw that exception today.
 *
 * <p>No database, no Testcontainers — only the MVC stack, the @ControllerAdvice,
 * and the stub controller are loaded.
 */
@WebMvcTest(controllers = ExceptionThrowingTestController.class)
@Import(GlobalExceptionHandler.class)
@WithMockUser
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    // WebConfig (loaded by @WebMvcTest) instantiates PasswordChangeInterceptor,
    // which needs UserRepository. The QuoteModelAdvice/SeasonPhaseModelAdvice
    // @ControllerAdvice beans need their services, and the slice's Filter scan pulls
    // in LoginRateLimitFilter, which needs RateLimitService. We don't exercise any
    // of them — stub them out.
    @MockBean UserRepository userRepository;
    @MockBean QuoteService quoteService;
    @MockBean com.yotto.basketball.service.SeasonPhaseService seasonPhaseService;
    @MockBean com.yotto.basketball.security.RateLimitService rateLimitService;
    @MockBean com.yotto.basketball.service.PublicModelService publicModelService; // NavModelsAdvice

    @Test
    void entityNotFoundException_returns404WithFullErrorBody() throws Exception {
        mockMvc.perform(get("/stub/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("ghost entity"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void illegalArgumentException_returns400WithBadRequestBody() throws Exception {
        mockMvc.perform(get("/stub/bad-arg").param("value", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("invalid value: bad"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void methodArgumentNotValid_returns400WithFieldErrors() throws Exception {
        String body = """
                {"name": "", "age": -1}
                """;

        mockMvc.perform(post("/stub/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.age").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unmatchedPath_returns404NotInternalServerError() throws Exception {
        mockMvc.perform(get("/no/such/endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void pathVariableTypeMismatch_returns400NotInternalServerError() throws Exception {
        mockMvc.perform(get("/stub/typed/idaho-vandals"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // ── HTML mode: browser requests get the branded error views, same statuses ──

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Test
    void entityNotFound_browserAccept_rendersHtml404View() throws Exception {
        mockMvc.perform(get("/stub/not-found").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(model().attribute("message", "ghost entity"));
    }

    @Test
    void unmatchedPath_browserAccept_rendersHtml404View() throws Exception {
        mockMvc.perform(get("/no/such/endpoint").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void typeMismatch_browserAccept_rendersHtml400WithoutJavaTypeDetail() throws Exception {
        // The raw conversion message names parameters and Java types — HTML mode
        // must not surface it.
        mockMvc.perform(get("/stub/typed/idaho-vandals").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error/400"))
                .andExpect(model().attribute("message", org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java.lang"))));
    }

    @Test
    void uncaughtException_browserAccept_rendersHtml500() throws Exception {
        mockMvc.perform(get("/stub/boom").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error/500"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("kaboom"))));
    }

    @Test
    void apiPath_browserAccept_staysJson() throws Exception {
        // /api/** always speaks JSON regardless of what the client accepts.
        mockMvc.perform(get("/api/no/such/endpoint").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void uncaughtException_returns500WithGenericMessage_notLeakingCause() throws Exception {
        // The catch-all handler must NOT echo the raw exception message to the client
        // (it could leak SQL/class/internal detail on the public API). It returns a
        // fixed generic message; the real cause is only logged server-side.
        mockMvc.perform(get("/stub/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("kaboom"))))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
