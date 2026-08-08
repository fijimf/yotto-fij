package com.yotto.basketball.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "news")
public class NewsProperties {

    /** Master switch — the scheduler and pollers do nothing when false. */
    private boolean enabled = false;

    private String schedule = "0 */30 * * * *";
    // Same-host spacing: generous by default — CBS's WAF 429-blocked the server
    // IP after bursts of ~500ms-spaced requests during launch testing
    private int baseDelayMs = 1000;
    private int jitterMs = 500;
    private int timeoutMs = 10000;
    private int maxItemAgeDays = 14;
    private int pollCapPerSource = 50;
    private int defaultAuthorityWeight = 30;
    private int autoDisableAfterFailures = 10;
    private int retryProbeDays = 7;
    /** Off by default (a published feed is an invitation to fetch); flip on if sources start blocking us. */
    private boolean respectRobots = false;
    /** SSRF-guard escape hatch for local development ONLY — never enable in production. */
    private boolean allowPrivateAddresses = false;
    /**
     * ESPN's Akamai edge started 403ing unrecognized User-Agents (~2026-07-27): custom bot UAs
     * are blocked on every HTTP stack while recognized product tokens (curl/*, Java/*, real
     * browsers) pass. "Java/21" is the platform-truthful token the game scrapers have always
     * sent implicitly; the comment suffix keeps our contact URL — verified to pass.
     */
    private String userAgent = "Java/21 (+https://fijimf.com/about)";
    private int maxResponseBytes = 2 * 1024 * 1024;
    private int maxRedirects = 5;
    private int subtitleMaxChars = 300;

    private final Dedup dedup = new Dedup();
    private final Tagging tagging = new Tagging();
    private final Ranking ranking = new Ranking();
    private final Images images = new Images();

    public static class Dedup {
        private int minBodyTokens = 80;
        // For 300-800-token article bodies, a verbatim republish with different site
        // chrome measures hamming 4-8; genuinely different stories measure ~25-32.
        // (The classic threshold of 3 assumes full-page-length inputs.)
        private int hammingThreshold = 10;
        private int windowDays = 10;

        public int getMinBodyTokens() {
            return minBodyTokens;
        }

        public void setMinBodyTokens(int minBodyTokens) {
            this.minBodyTokens = minBodyTokens;
        }

        public int getHammingThreshold() {
            return hammingThreshold;
        }

        public void setHammingThreshold(int hammingThreshold) {
            this.hammingThreshold = hammingThreshold;
        }

        public int getWindowDays() {
            return windowDays;
        }

        public void setWindowDays(int windowDays) {
            this.windowDays = windowDays;
        }
    }

    public static class Tagging {
        /** Minimum match score for a tag to be written (§5.7). */
        private double tagThreshold = 2.0;
        /** Score below threshold but at or above this is recorded nowhere but shown as a near-miss. */
        private double nearMissThreshold = 1.0;

        public double getTagThreshold() {
            return tagThreshold;
        }

        public void setTagThreshold(double tagThreshold) {
            this.tagThreshold = tagThreshold;
        }

        public double getNearMissThreshold() {
            return nearMissThreshold;
        }

        public void setNearMissThreshold(double nearMissThreshold) {
            this.nearMissThreshold = nearMissThreshold;
        }
    }

    public static class Ranking {
        private double halfLifeHours = 36.0;
        private int frontPageCount = 6;
        private int frontPagePerSourceCap = 2;
        private double teamPageMinConfidence = 0.4;

        public double getHalfLifeHours() {
            return halfLifeHours;
        }

        public void setHalfLifeHours(double halfLifeHours) {
            this.halfLifeHours = halfLifeHours;
        }

        public int getFrontPageCount() {
            return frontPageCount;
        }

        public void setFrontPageCount(int frontPageCount) {
            this.frontPageCount = frontPageCount;
        }

        public int getFrontPagePerSourceCap() {
            return frontPagePerSourceCap;
        }

        public void setFrontPagePerSourceCap(int frontPagePerSourceCap) {
            this.frontPagePerSourceCap = frontPagePerSourceCap;
        }

        public double getTeamPageMinConfidence() {
            return teamPageMinConfidence;
        }

        public void setTeamPageMinConfidence(double teamPageMinConfidence) {
            this.teamPageMinConfidence = teamPageMinConfidence;
        }
    }

    public static class Images {
        private boolean enabled = true;
        private String thumbnailDir = "data/news-thumbnails";
        private int thumbnailWidth = 320;
        private int maxDownloadBytes = 5 * 1024 * 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getThumbnailDir() {
            return thumbnailDir;
        }

        public void setThumbnailDir(String thumbnailDir) {
            this.thumbnailDir = thumbnailDir;
        }

        public int getThumbnailWidth() {
            return thumbnailWidth;
        }

        public void setThumbnailWidth(int thumbnailWidth) {
            this.thumbnailWidth = thumbnailWidth;
        }

        public int getMaxDownloadBytes() {
            return maxDownloadBytes;
        }

        public void setMaxDownloadBytes(int maxDownloadBytes) {
            this.maxDownloadBytes = maxDownloadBytes;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

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

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxItemAgeDays() {
        return maxItemAgeDays;
    }

    public void setMaxItemAgeDays(int maxItemAgeDays) {
        this.maxItemAgeDays = maxItemAgeDays;
    }

    public int getPollCapPerSource() {
        return pollCapPerSource;
    }

    public void setPollCapPerSource(int pollCapPerSource) {
        this.pollCapPerSource = pollCapPerSource;
    }

    public int getDefaultAuthorityWeight() {
        return defaultAuthorityWeight;
    }

    public void setDefaultAuthorityWeight(int defaultAuthorityWeight) {
        this.defaultAuthorityWeight = defaultAuthorityWeight;
    }

    public int getAutoDisableAfterFailures() {
        return autoDisableAfterFailures;
    }

    public void setAutoDisableAfterFailures(int autoDisableAfterFailures) {
        this.autoDisableAfterFailures = autoDisableAfterFailures;
    }

    public int getRetryProbeDays() {
        return retryProbeDays;
    }

    public void setRetryProbeDays(int retryProbeDays) {
        this.retryProbeDays = retryProbeDays;
    }

    public boolean isRespectRobots() {
        return respectRobots;
    }

    public void setRespectRobots(boolean respectRobots) {
        this.respectRobots = respectRobots;
    }

    public boolean isAllowPrivateAddresses() {
        return allowPrivateAddresses;
    }

    public void setAllowPrivateAddresses(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public int getSubtitleMaxChars() {
        return subtitleMaxChars;
    }

    public void setSubtitleMaxChars(int subtitleMaxChars) {
        this.subtitleMaxChars = subtitleMaxChars;
    }

    public Dedup getDedup() {
        return dedup;
    }

    public Tagging getTagging() {
        return tagging;
    }

    public Ranking getRanking() {
        return ranking;
    }

    public Images getImages() {
        return images;
    }
}
