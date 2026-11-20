package com.yotto.basketball.controller.dto;

import com.yotto.basketball.entity.Game;

import java.util.List;

/**
 * A team's season record as it stood going into a game, derived purely from the
 * FINAL games it played before that game's tip. The game page used to read these
 * rows from {@code SeasonStatistics}, which always holds today's totals, so a
 * November game showed March records. Everything here is as-of, like the ratings
 * and stat snapshots on the same page.
 *
 * @param streak        +n for n straight wins, -n for n straight losses, null with no games
 * @param avgPointsFor  null with no games
 */
public record PreGameRecordDto(
        int wins, int losses,
        int confWins, int confLosses,
        int homeWins, int homeLosses,
        int awayWins, int awayLosses,
        int neutralWins, int neutralLosses,
        int last5Wins, int last5Losses,
        Integer streak,
        Double avgPointsFor, Double avgPointsAgainst
) {
    public static final PreGameRecordDto EMPTY =
            new PreGameRecordDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null);

    /** @param priorGames the team's FINAL games before tip, oldest first, scores non-null */
    public static PreGameRecordDto from(List<Game> priorGames, Long teamId) {
        if (priorGames == null || priorGames.isEmpty()) return EMPTY;
        int w = 0, l = 0, cw = 0, cl = 0, hw = 0, hl = 0, aw = 0, al = 0, nw = 0, nl = 0;
        long pf = 0, pa = 0;
        for (Game g : priorGames) {
            boolean isHome = teamId.equals(g.getHomeTeam().getId());
            int scored = isHome ? g.getHomeScore() : g.getAwayScore();
            int allowed = isHome ? g.getAwayScore() : g.getHomeScore();
            boolean won = scored > allowed;
            pf += scored;
            pa += allowed;
            if (won) w++; else l++;
            if (Boolean.TRUE.equals(g.getConferenceGame())) { if (won) cw++; else cl++; }
            if (Boolean.TRUE.equals(g.getNeutralSite())) { if (won) nw++; else nl++; }
            else if (isHome) { if (won) hw++; else hl++; }
            else { if (won) aw++; else al++; }
        }
        int n = priorGames.size();
        List<Game> last5 = priorGames.subList(Math.max(0, n - 5), n);
        int l5w = (int) last5.stream().filter(g -> won(g, teamId)).count();
        // Streak: walk back from the most recent game while the result matches it.
        boolean lastWon = won(priorGames.get(n - 1), teamId);
        int run = 0;
        for (int i = n - 1; i >= 0 && won(priorGames.get(i), teamId) == lastWon; i--) run++;
        return new PreGameRecordDto(w, l, cw, cl, hw, hl, aw, al, nw, nl,
                l5w, last5.size() - l5w,
                lastWon ? run : -run,
                (double) pf / n, (double) pa / n);
    }

    private static boolean won(Game g, Long teamId) {
        boolean isHome = teamId.equals(g.getHomeTeam().getId());
        return isHome ? g.getHomeScore() > g.getAwayScore() : g.getAwayScore() > g.getHomeScore();
    }
}
