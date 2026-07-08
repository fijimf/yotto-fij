package com.yotto.basketball.config;

import com.yotto.basketball.security.PasswordChangeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PasswordChangeInterceptor passwordChangeInterceptor;

    public WebConfig(PasswordChangeInterceptor passwordChangeInterceptor) {
        this.passwordChangeInterceptor = passwordChangeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Site-wide: any authenticated user flagged must-change is redirected
        // to /account/password (the interceptor itself skips auth/static paths)
        registry.addInterceptor(passwordChangeInterceptor)
                .addPathPatterns("/**");
    }
}
