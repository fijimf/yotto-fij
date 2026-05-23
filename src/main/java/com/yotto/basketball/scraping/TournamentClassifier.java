package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game.TournamentType;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class TournamentClassifier {

    private static final Logger log = LoggerFactory.getLogger(TournamentClassifier.class);

    private static final String NCAA_PREFIX = "Men's Basketball Championship";
    private static final Pattern NIT_PREFIX = Pattern.compile("^NIT( -|$).*");
    private static final Pattern NCAA_REGION = Pattern.compile("^(East|West|South|Midwest) Region$");

    // Sponsor prefixes that appear before the canonical tournament name. Stripping these
    // gives stable names year-to-year ("ACC Tournament" instead of "T. Rowe Price ACC Tournament").
    private static final List<String> SPONSOR_PREFIXES = List.of(
            "T. Rowe Price ",
            "Phillips 66 ",
            "Bad Boy Mowers ",
            "State Farm ",
            "Acrisure ",
            "The "
    );

    // Trailing sponsor phrases of the form " pres. by X" / " Presented by X" / " presented by X".
    private static final Pattern TRAILING_SPONSOR = Pattern.compile(
            "\\s+(pres\\.?\\s+by|Presented\\s+by|presented\\s+by)\\s+.*$");

    private final ConferenceMembershipRepository membershipRepository;

    public TournamentClassifier(ConferenceMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public Result classify(String espnNote, String seasonType, LocalDate gameDate,
                           Team homeTeam, Team awayTeam, Season season) {
        if (espnNote == null || espnNote.isBlank()) {
            return Result.regularSeason();
        }

        String note = espnNote.trim();
        // ESPN sometimes prefixes the NCAA tournament label with "NCAA " — strip it so the
        // classifier sees the canonical "Men's Basketball Championship - ..." prefix.
        String noteForNcaa = note.startsWith("NCAA ") ? note.substring("NCAA ".length()) : note;

        if (noteForNcaa.startsWith(NCAA_PREFIX)) {
            return classifyNcaa(noteForNcaa);
        }

        if (NIT_PREFIX.matcher(note).matches()) {
            String round = afterFirstDash(note);
            return new Result(TournamentType.NIT, "NIT", round, null);
        }

        if (note.contains("CBI")) {
            String round = afterFirstDash(note);
            return new Result(TournamentType.CBI, "CBI", round, null);
        }

        if (note.contains("College Basketball Crown")) {
            String round = afterFirstDash(note);
            return new Result(TournamentType.CROWN, "College Basketball Crown", round, null);
        }

        if ("3".equals(seasonType)) {
            String name = stripSponsors(headOf(note));
            String round = afterFirstDash(note);
            return new Result(TournamentType.OTHER_POSTSEASON, name, round, null);
        }

        // seasonType "2" with a populated note: conference tournament vs in-season exempt.
        String name = stripSponsors(headOf(note));
        String round = afterFirstDash(note);

        if (isConferenceTournament(gameDate, homeTeam, awayTeam, season)) {
            return new Result(TournamentType.CONFERENCE_TOURNAMENT, name, round, null);
        }
        return new Result(TournamentType.IN_SEASON_TOURNAMENT, name, round, null);
    }

    private Result classifyNcaa(String note) {
        String[] segments = note.split(" - ");
        String region = null;
        String round = null;
        if (segments.length == 2) {
            round = segments[1].trim();
        } else if (segments.length >= 3) {
            String middle = segments[1].trim();
            var m = NCAA_REGION.matcher(middle);
            if (m.matches()) {
                region = m.group(1);
                round = segments[segments.length - 1].trim();
            } else {
                round = segments[segments.length - 1].trim();
            }
        }
        return new Result(TournamentType.NCAA_TOURNAMENT, "NCAA Tournament", round, region);
    }

    private boolean isConferenceTournament(LocalDate gameDate, Team homeTeam, Team awayTeam, Season season) {
        if (gameDate == null || season == null || homeTeam == null || awayTeam == null) {
            return false;
        }
        // Conference tournaments run Feb-Apr in our data window. November in-season exempts are clamped out.
        int month = gameDate.getMonthValue();
        if (month < 2 || month > 4) {
            return false;
        }
        Optional<ConferenceMembership> homeMembership =
                membershipRepository.findByTeamIdAndSeasonId(homeTeam.getId(), season.getId());
        Optional<ConferenceMembership> awayMembership =
                membershipRepository.findByTeamIdAndSeasonId(awayTeam.getId(), season.getId());
        if (homeMembership.isEmpty() || awayMembership.isEmpty()) {
            return false;
        }
        Long homeConf = homeMembership.get().getConference().getId();
        Long awayConf = awayMembership.get().getConference().getId();
        return homeConf != null && homeConf.equals(awayConf);
    }

    private String headOf(String note) {
        int dash = note.indexOf(" - ");
        return dash < 0 ? note : note.substring(0, dash);
    }

    private String afterFirstDash(String note) {
        int dash = note.indexOf(" - ");
        return dash < 0 ? null : note.substring(dash + 3).trim();
    }

    private String stripSponsors(String name) {
        if (name == null) return null;
        String stripped = name;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : SPONSOR_PREFIXES) {
                if (stripped.startsWith(prefix) && stripped.length() > prefix.length()) {
                    stripped = stripped.substring(prefix.length());
                    changed = true;
                }
            }
        }
        stripped = TRAILING_SPONSOR.matcher(stripped).replaceFirst("");
        return stripped.trim();
    }

    public record Result(TournamentType type, String name, String round, String region) {
        public static Result regularSeason() {
            return new Result(null, null, null, null);
        }
    }

    // Convenience for tests / debugging.
    public static Set<TournamentType> postseasonTypes() {
        return Set.of(TournamentType.NCAA_TOURNAMENT, TournamentType.NIT, TournamentType.CBI,
                TournamentType.CROWN, TournamentType.CONFERENCE_TOURNAMENT,
                TournamentType.OTHER_POSTSEASON);
    }
}
