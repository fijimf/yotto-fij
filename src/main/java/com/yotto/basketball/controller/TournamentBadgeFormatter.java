package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.TournamentType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Compact label + CSS variant for the tournament chip rendered on schedule rows
 * and the game detail header. Returns null when the game is a plain regular-season game.
 */
@Component
public class TournamentBadgeFormatter {

    private static final Map<String, String> ROUND_ABBREV = Map.ofEntries(
            Map.entry("1st Round", "1R"),
            Map.entry("2nd Round", "2R"),
            Map.entry("Sweet 16", "S16"),
            Map.entry("Elite 8", "E8"),
            Map.entry("Final Four", "FF"),
            Map.entry("National Championship", "Final"),
            Map.entry("First Four", "First Four"),
            Map.entry("Quarterfinal", "QF"),
            Map.entry("Semifinal", "SF"),
            Map.entry("Championship", "Final"),
            Map.entry("Final", "Final"),
            Map.entry("Play-In", "PI")
    );

    public Badge format(Game game) {
        if (game == null || game.getTournamentType() == null) return null;
        TournamentType type = game.getTournamentType();

        String shortName = switch (type) {
            case NCAA_TOURNAMENT -> "NCAA";
            case NIT -> "NIT";
            case CBI -> "CBI";
            case CROWN -> "Crown";
            case CONFERENCE_TOURNAMENT -> stripSuffix(game.getTournamentName());
            case IN_SEASON_TOURNAMENT -> game.getTournamentName();
            case OTHER_POSTSEASON -> game.getTournamentName() == null ? "Postseason" : game.getTournamentName();
        };

        StringBuilder label = new StringBuilder();
        if (shortName != null && !shortName.isBlank()) label.append(shortName);
        String round = abbreviateRound(game.getTournamentRound());
        if (round != null) {
            if (label.length() > 0) label.append(' ');
            label.append(round);
        }
        if (game.getTournamentRegion() != null && !game.getTournamentRegion().isBlank()) {
            label.append(" · ").append(game.getTournamentRegion());
        }

        String tooltip = buildTooltip(game);
        return new Badge(label.toString(), cssVariant(type), tooltip);
    }

    private String stripSuffix(String name) {
        if (name == null) return null;
        if (name.endsWith(" Tournament")) return name.substring(0, name.length() - " Tournament".length());
        if (name.endsWith(" Championship")) return name.substring(0, name.length() - " Championship".length());
        if (name.endsWith(" Playoffs")) return name.substring(0, name.length() - " Playoffs".length());
        return name;
    }

    private String abbreviateRound(String round) {
        if (round == null || round.isBlank()) return null;
        String abbr = ROUND_ABBREV.get(round);
        return abbr != null ? abbr : round;
    }

    private String cssVariant(TournamentType type) {
        return switch (type) {
            case NCAA_TOURNAMENT -> "ncaa";
            case NIT -> "nit";
            case CBI -> "cbi";
            case CROWN -> "crown";
            case CONFERENCE_TOURNAMENT -> "conf";
            case IN_SEASON_TOURNAMENT -> "in-season";
            case OTHER_POSTSEASON -> "postseason";
        };
    }

    private String buildTooltip(Game game) {
        StringBuilder t = new StringBuilder();
        if (game.getTournamentName() != null) t.append(game.getTournamentName());
        if (game.getTournamentRegion() != null) t.append(" — ").append(game.getTournamentRegion()).append(" Region");
        if (game.getTournamentRound() != null) {
            if (t.length() > 0) t.append(" · ");
            t.append(game.getTournamentRound());
        }
        return t.toString();
    }

    public record Badge(String label, String variant, String tooltip) {}
}
