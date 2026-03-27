package com.yotto.basketball.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "espn.scraping")
public class ScrapingProperties {

    private int baseDelayMs = 200;
    private int jitterMs = 100;
    private int seasonStartMonth = 11;
    private int seasonStartDay = 1;
    private int seasonEndMonth = 4;
    private int seasonEndDay = 30;

    public int getBaseDelayMs() {
        return baseDelayMs;
    }

    public void setBaseDelayMs(int baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public int getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(int jitterMs) {
        this.jitterMs = jitterMs;
    }

    public int getSeasonStartMonth() {
        return seasonStartMonth;
    }

    public void setSeasonStartMonth(int seasonStartMonth) {
        this.seasonStartMonth = seasonStartMonth;
    }

    public int getSeasonStartDay() {
        return seasonStartDay;
    }

    public void setSeasonStartDay(int seasonStartDay) {
        this.seasonStartDay = seasonStartDay;
    }

    public int getSeasonEndMonth() {
        return seasonEndMonth;
    }

    public void setSeasonEndMonth(int seasonEndMonth) {
        this.seasonEndMonth = seasonEndMonth;
    }

    public int getSeasonEndDay() {
        return seasonEndDay;
    }

    public void setSeasonEndDay(int seasonEndDay) {
        this.seasonEndDay = seasonEndDay;
    }
}
