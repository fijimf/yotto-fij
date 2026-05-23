package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.TournamentType;
import org.springframework.stereotype.Component;

/**
 * Tournament chip rendered on schedule rows and the game detail header. The label is
 * tournament-only (no round, no region — that detail lives in the tooltip). Three color
 * buckets: NCAA Tournament, conference tournaments, and everything else (NIT / CBI /
 * Crown / other postseason). In-season exempts stay neutral.
 */
@Component
public class TournamentBadgeFormatter {

    public Badge format(Game game) {
        if (game == null || game.getTournamentType() == null) return null;
        TournamentType type = game.getTournamentType();

        String label = switch (type) {
            case NCAA_TOURNAMENT -> "NCAA Tournament";
            case NIT -> "NIT";
            case CBI -> "CBI";
            case CROWN -> "College Basketball Crown";
            case CONFERENCE_TOURNAMENT -> stripSuffix(game.getTournamentName());
            case IN_SEASON_TOURNAMENT -> game.getTournamentName();
            case OTHER_POSTSEASON -> game.getTournamentName() == null ? "Postseason" : game.getTournamentName();
        };

        return new Badge(label == null ? "" : label, cssVariant(type), buildTooltip(game));
    }

    private String stripSuffix(String name) {
        if (name == null) return null;
        if (name.endsWith(" Tournament")) return name.substring(0, name.length() - " Tournament".length());
        if (name.endsWith(" Championship")) return name.substring(0, name.length() - " Championship".length());
        if (name.endsWith(" Playoffs")) return name.substring(0, name.length() - " Playoffs".length());
        return name;
    }

    private String cssVariant(TournamentType type) {
        return switch (type) {
            case NCAA_TOURNAMENT -> "ncaa";
            case CONFERENCE_TOURNAMENT -> "conf";
            case NIT, CBI, CROWN, OTHER_POSTSEASON -> "postseason";
            case IN_SEASON_TOURNAMENT -> "in-season";
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
