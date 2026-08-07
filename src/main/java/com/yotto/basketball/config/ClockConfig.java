package com.yotto.basketball.config;

import com.yotto.basketball.util.EasternDates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application clock, zoned Eastern — the basketball calendar's canonical zone
 * (see {@link EasternDates}). Inject {@link Clock} instead of calling {@code now()}
 * statics so tests can pin time with {@code Clock.fixed}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(EasternDates.EASTERN);
    }
}
