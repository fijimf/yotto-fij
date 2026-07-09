package com.yotto.basketball.service;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceNameHistory;
import com.yotto.basketball.repository.ConferenceNameHistoryRepository;
import com.yotto.basketball.service.ConferenceNamingService.ConferenceIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for season-scoped conference name resolution (no Spring
 * context). Canonical row = current branding; history rows = superseded
 * brandings with the last season year (inclusive) each applied to.
 */
class ConferenceNamingServiceTest {

    private final ConferenceNameHistoryRepository repository = mock(ConferenceNameHistoryRepository.class);
    private final ConferenceNamingService service = new ConferenceNamingService(repository);

    private final Conference uac = conf(1L, "United Athletic Conference", "UAC", "https://x/uac.gif");

    @Test
    void noHistory_returnsCanonicalBranding() {
        when(repository.findAll()).thenReturn(List.of());

        ConferenceIdentity identity = service.resolve(uac, 2026);

        assertThat(identity.name()).isEqualTo("United Athletic Conference");
        assertThat(identity.abbreviation()).isEqualTo("UAC");
        assertThat(identity.logoUrl()).isEqualTo("https://x/uac.gif");
    }

    @Test
    void seasonWithinEra_returnsEraBranding() {
        when(repository.findAll()).thenReturn(List.of(
                era(uac, "Western Athletic Conference", "WAC", "https://x/wac.gif", 2026)));

        ConferenceIdentity identity = service.resolve(uac, 2026);

        assertThat(identity.name()).isEqualTo("Western Athletic Conference");
        assertThat(identity.abbreviation()).isEqualTo("WAC");
        assertThat(identity.logoUrl()).isEqualTo("https://x/wac.gif");
    }

    @Test
    void seasonAfterEra_returnsCanonicalBranding() {
        when(repository.findAll()).thenReturn(List.of(
                era(uac, "Western Athletic Conference", "WAC", "https://x/wac.gif", 2026)));

        assertThat(service.resolve(uac, 2027).name()).isEqualTo("United Athletic Conference");
    }

    @Test
    void multipleRenames_picksTightestCoveringEra() {
        // WAC through 2026, then a hypothetical second rename through 2035
        when(repository.findAll()).thenReturn(List.of(
                era(uac, "Second Name Conference", "SNC", null, 2035),
                era(uac, "Western Athletic Conference", "WAC", null, 2026)));

        assertThat(service.resolve(uac, 2020).name()).isEqualTo("Western Athletic Conference");
        assertThat(service.resolve(uac, 2026).name()).isEqualTo("Western Athletic Conference");
        assertThat(service.resolve(uac, 2030).name()).isEqualTo("Second Name Conference");
        assertThat(service.resolve(uac, 2035).name()).isEqualTo("Second Name Conference");
        assertThat(service.resolve(uac, 2036).name()).isEqualTo("United Athletic Conference");
    }

    @Test
    void nullEraAbbreviationAndLogo_fallBackToCanonical() {
        when(repository.findAll()).thenReturn(List.of(
                era(uac, "Western Athletic Conference", null, null, 2026)));

        ConferenceIdentity identity = service.resolve(uac, 2025);

        assertThat(identity.name()).isEqualTo("Western Athletic Conference");
        assertThat(identity.abbreviation()).isEqualTo("UAC");
        assertThat(identity.logoUrl()).isEqualTo("https://x/uac.gif");
    }

    @Test
    void historyOfOtherConference_doesNotLeak() {
        Conference sec = conf(2L, "Southeastern Conference", "SEC", null);
        when(repository.findAll()).thenReturn(List.of(
                era(uac, "Western Athletic Conference", "WAC", null, 2026)));

        assertThat(service.resolve(sec, 2020).name()).isEqualTo("Southeastern Conference");
    }

    // ── helpers ──

    private static Conference conf(Long id, String name, String abbr, String logo) {
        Conference c = new Conference();
        c.setId(id);
        c.setName(name);
        c.setAbbreviation(abbr);
        c.setLogoUrl(logo);
        return c;
    }

    private static ConferenceNameHistory era(Conference c, String name, String abbr, String logo, int lastYear) {
        return new ConferenceNameHistory(c, name, abbr, logo, lastYear);
    }
}
