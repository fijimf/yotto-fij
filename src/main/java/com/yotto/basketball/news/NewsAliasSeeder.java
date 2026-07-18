package com.yotto.basketball.news;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceNameHistory;
import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceNameHistoryRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.NewsAliasRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Regenerates AUTO gazetteer aliases from the teams/conferences tables
 * (docs/NEWS_MODULE.md §5.6). MANUAL and BLOCK rows are never touched; an
 * AUTO row whose (alias, target) pair also exists as MANUAL/BLOCK is skipped.
 */
@Service
public class NewsAliasSeeder {

    private static final Logger log = LoggerFactory.getLogger(NewsAliasSeeder.class);

    /**
     * Bare location names that collide with states, big cities, common words, or
     * wire-dateline usage — seeded ambiguous so they only tag when corroborated.
     * Locations shared by multiple teams (Miami, ...) are detected automatically.
     */
    static final Set<String> AMBIGUOUS_LOCATIONS = Set.of(
            // states
            "alabama", "alaska", "arizona", "arkansas", "california", "colorado",
            "connecticut", "delaware", "florida", "georgia", "hawaii", "idaho",
            "illinois", "indiana", "iowa", "kansas", "kentucky", "louisiana", "maine",
            "maryland", "massachusetts", "michigan", "minnesota", "mississippi",
            "missouri", "montana", "nebraska", "nevada", "new hampshire", "new jersey",
            "new mexico", "new york", "north carolina", "north dakota", "ohio",
            "oklahoma", "oregon", "pennsylvania", "rhode island", "south carolina",
            "south dakota", "tennessee", "texas", "utah", "vermont", "virginia",
            "washington", "west virginia", "wisconsin", "wyoming",
            // cities and common words
            "houston", "memphis", "cincinnati", "charlotte", "buffalo", "toledo",
            "akron", "dayton", "richmond", "providence", "temple", "butler",
            "pacific", "portland", "denver", "tulsa", "army", "navy", "air force",
            "brown", "rice", "penn", "marshall", "howard", "american", "auburn",
            "columbia", "princeton", "harvard", "yale", "san francisco", "detroit",
            "milwaukee", "omaha", "belmont", "bradley", "drake", "furman", "mercer",
            "samford", "campbell", "davidson", "elon", "hampton", "liberty",
            "longwood", "radford", "stetson", "winthrop", "wofford");

    static final Set<String> AMBIGUOUS_CONFERENCE_NAMES = Set.of(
            "american", "southern", "ivy", "horizon", "summit", "colonial",
            "patriot", "big south", "big sky", "atlantic 10");

    private final TeamRepository teamRepository;
    private final ConferenceRepository conferenceRepository;
    private final ConferenceNameHistoryRepository conferenceNameHistoryRepository;
    private final NewsAliasRepository aliasRepository;
    private final TeamTagger teamTagger;

    public NewsAliasSeeder(TeamRepository teamRepository,
                           ConferenceRepository conferenceRepository,
                           ConferenceNameHistoryRepository conferenceNameHistoryRepository,
                           NewsAliasRepository aliasRepository,
                           TeamTagger teamTagger) {
        this.teamRepository = teamRepository;
        this.conferenceRepository = conferenceRepository;
        this.conferenceNameHistoryRepository = conferenceNameHistoryRepository;
        this.aliasRepository = aliasRepository;
        this.teamTagger = teamTagger;
    }

    /** @return number of AUTO aliases after reseeding */
    @Transactional
    public int reseed() {
        List<Team> teams = teamRepository.findAll().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getActive()))
                .toList();
        List<Conference> conferences = conferenceRepository.findAll();
        List<ConferenceNameHistory> history = conferenceNameHistoryRepository.findAll();

        List<NewsAlias> desired = new ArrayList<>();
        Set<String> duplicateLocations = findDuplicateLocations(teams);
        for (Team team : teams) {
            desired.addAll(teamAliases(team, duplicateLocations));
        }
        for (Conference conference : conferences) {
            desired.addAll(conferenceAliases(conference));
        }
        for (ConferenceNameHistory h : history) {
            if (h.getName() != null && !h.getName().isBlank()) {
                desired.add(NewsAlias.forConference(h.getName().strip(), h.getConference(),
                        NewsAlias.Kind.AUTO, isAmbiguousConferenceName(h.getName()), false));
            }
            if (h.getAbbreviation() != null && h.getAbbreviation().strip().length() >= 3) {
                desired.add(NewsAlias.forConference(h.getAbbreviation().strip(), h.getConference(),
                        NewsAlias.Kind.AUTO, false, true));
            }
        }

        // Existing rows by (alias-lower, target); MANUAL/BLOCK pin their pair.
        Map<String, NewsAlias> existingAuto = new HashMap<>();
        Set<String> pinned = new HashSet<>();
        for (NewsAlias existing : aliasRepository.findAll()) {
            String key = pairKey(existing);
            if (existing.getKind() == NewsAlias.Kind.AUTO) {
                existingAuto.put(key, existing);
            } else {
                pinned.add(key);
            }
        }

        Set<String> desiredKeys = new HashSet<>();
        List<NewsAlias> toSave = new ArrayList<>();
        for (NewsAlias alias : desired) {
            String key = pairKey(alias);
            if (pinned.contains(key) || !desiredKeys.add(key)) {
                continue;
            }
            NewsAlias existing = existingAuto.get(key);
            if (existing != null) {
                if (!existing.getAmbiguous().equals(alias.getAmbiguous())
                        || !existing.getCaseSensitive().equals(alias.getCaseSensitive())
                        || !existing.getAlias().equals(alias.getAlias())) {
                    existing.setAmbiguous(alias.getAmbiguous());
                    existing.setCaseSensitive(alias.getCaseSensitive());
                    existing.setAlias(alias.getAlias());
                    toSave.add(existing);
                }
            } else {
                alias.setCreatedBy("system");
                toSave.add(alias);
            }
        }
        List<NewsAlias> toDelete = existingAuto.entrySet().stream()
                .filter(e -> !desiredKeys.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        aliasRepository.saveAll(toSave);
        aliasRepository.deleteAll(toDelete);
        teamTagger.invalidate();
        log.info("Reseeded news aliases: {} desired, {} saved/updated, {} deleted",
                desiredKeys.size(), toSave.size(), toDelete.size());
        return desiredKeys.size();
    }

    private static Set<String> findDuplicateLocations(List<Team> teams) {
        Map<String, Integer> counts = new HashMap<>();
        for (Team team : teams) {
            if (team.getName() != null) {
                counts.merge(team.getName().strip().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        Set<String> duplicates = new HashSet<>();
        counts.forEach((name, count) -> {
            if (count > 1) {
                duplicates.add(name);
            }
        });
        return duplicates;
    }

    private static List<NewsAlias> teamAliases(Team team, Set<String> duplicateLocations) {
        List<NewsAlias> aliases = new ArrayList<>();
        String location = strippedOrNull(team.getName());
        String mascot = strippedOrNull(team.getMascot());
        String nickname = strippedOrNull(team.getNickname());
        String abbreviation = strippedOrNull(team.getAbbreviation());

        if (location != null && location.length() >= 3) {
            boolean ambiguous = duplicateLocations.contains(location.toLowerCase(Locale.ROOT))
                    || AMBIGUOUS_LOCATIONS.contains(location.toLowerCase(Locale.ROOT));
            aliases.add(NewsAlias.forTeam(location, team, NewsAlias.Kind.AUTO, ambiguous, false));
        }
        if (location != null && mascot != null) {
            aliases.add(NewsAlias.forTeam(location + " " + mascot, team, NewsAlias.Kind.AUTO, false, false));
        }
        // Mascots are the main collision source (Wildcats ×5...): always ambiguous.
        if (mascot != null && mascot.length() >= 3) {
            aliases.add(NewsAlias.forTeam(mascot, team, NewsAlias.Kind.AUTO, true, false));
        }
        if (nickname != null && nickname.length() >= 4
                && (location == null || !nickname.equalsIgnoreCase(location))) {
            boolean ambiguous = AMBIGUOUS_LOCATIONS.contains(nickname.toLowerCase(Locale.ROOT));
            aliases.add(NewsAlias.forTeam(nickname, team, NewsAlias.Kind.AUTO, ambiguous, false));
        }
        if (abbreviation != null && abbreviation.length() >= 3) {
            aliases.add(NewsAlias.forTeam(abbreviation, team, NewsAlias.Kind.AUTO, false, true));
        }
        return aliases;
    }

    private static List<NewsAlias> conferenceAliases(Conference conference) {
        List<NewsAlias> aliases = new ArrayList<>();
        String name = strippedOrNull(conference.getName());
        String abbreviation = strippedOrNull(conference.getAbbreviation());
        if (name != null && name.length() >= 3) {
            aliases.add(NewsAlias.forConference(name, conference, NewsAlias.Kind.AUTO,
                    isAmbiguousConferenceName(name), false));
        }
        if (abbreviation != null && abbreviation.length() >= 3
                && (name == null || !abbreviation.equalsIgnoreCase(name))) {
            aliases.add(NewsAlias.forConference(abbreviation, conference, NewsAlias.Kind.AUTO, false, true));
        }
        return aliases;
    }

    private static boolean isAmbiguousConferenceName(String name) {
        return AMBIGUOUS_CONFERENCE_NAMES.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    private static String strippedOrNull(String s) {
        if (s == null) {
            return null;
        }
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String pairKey(NewsAlias alias) {
        String target = alias.getTeam() != null ? "T" + alias.getTeam().getId()
                : "C" + alias.getConference().getId();
        return alias.getAlias().strip().toLowerCase(Locale.ROOT) + "|" + target;
    }
}
