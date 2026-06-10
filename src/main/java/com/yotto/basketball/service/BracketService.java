package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the slot tree for the NCAA tournament bracket page. ESPN provides games, not
 * bracket positions, so placement is derived: first-round slots follow the canonical
 * seed order (1v16, 8v9, 5v12, 4v13, 6v11, 3v14, 7v10, 2v15) and later rounds inherit
 * position from each team's first-round slot. Final Four region pairing defaults to
 * canonical order (East/West left, South/Midwest right) and self-corrects once the
 * semifinal games exist.
 */
@Service
public class BracketService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    /** Seeds in canonical bracket order, top to bottom; pairs of two form the 8 first-round slots. */
    private static final int[] FIRST_ROUND_SEED_ORDER = {1, 16, 8, 9, 5, 12, 4, 13, 6, 11, 3, 14, 7, 10, 2, 15};
    private static final Map<Integer, Integer> SEED_POSITION = seedPositions();

    private static final List<String> REGION_ROUNDS = List.of("1st Round", "2nd Round", "Sweet 16", "Elite 8");
    private static final String ROUND_FIRST_FOUR = "First Four";
    private static final String ROUND_FINAL_FOUR = "Final Four";
    private static final String ROUND_CHAMPIONSHIP = "National Championship";
    private static final List<String> CANONICAL_REGIONS = List.of("East", "West", "South", "Midwest");

    private final SeasonRepository seasonRepository;
    private final GameRepository gameRepository;

    public BracketService(SeasonRepository seasonRepository, GameRepository gameRepository) {
        this.seasonRepository = seasonRepository;
        this.gameRepository = gameRepository;
    }

    @Transactional(readOnly = true)
    public Optional<BracketView> buildBracket(int year) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        if (season == null) return Optional.empty();
        List<Game> games = gameRepository.findBySeasonIdAndTournamentTypeWithDetails(
                season.getId(), Game.TournamentType.NCAA_TOURNAMENT);
        if (games.isEmpty()) return Optional.empty();
        return Optional.of(build(year, games));
    }

    @Transactional(readOnly = true)
    public Optional<Integer> latestBracketYear() {
        return Optional.ofNullable(
                gameRepository.findMaxSeasonYearByTournamentType(Game.TournamentType.NCAA_TOURNAMENT));
    }

    static BracketView build(int year, List<Game> games) {
        Map<String, List<Game>> byRound = new HashMap<>();
        for (Game g : games) {
            String round = g.getTournamentRound() == null ? "" : g.getTournamentRound().trim();
            byRound.computeIfAbsent(round, k -> new ArrayList<>()).add(g);
        }

        List<String> regionNames = games.stream()
                .map(Game::getTournamentRegion)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingInt(BracketService::canonicalRegionIndex)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        Map<String, Game[][]> placed = placeRegionGames(byRound, regionNames);
        Map<String, Map<Integer, String[]>> ffNotes = firstFourNotes(byRound);

        Map<String, BracketView.Region> regionViews = new LinkedHashMap<>();
        for (String name : regionNames) {
            Game[][] slots = placed.get(name);
            List<List<BracketView.Slot>> rounds = new ArrayList<>();
            for (int r = 0; r < slots.length; r++) {
                List<BracketView.Slot> row = new ArrayList<>();
                for (int i = 0; i < slots[r].length; i++) {
                    String[] ff = r == 0 ? ffNotes.getOrDefault(name, Map.of()).get(i) : null;
                    row.add(slotView(slots, r, i, ff));
                }
                rounds.add(row);
            }
            regionViews.put(name, new BracketView.Region(name, rounds));
        }

        Map<Long, String> teamRegion = new HashMap<>();
        for (Game g : games) {
            if (g.getTournamentRegion() == null) continue;
            teamRegion.put(g.getHomeTeam().getId(), g.getTournamentRegion());
            teamRegion.put(g.getAwayTeam().getId(), g.getTournamentRegion());
        }

        List<Game> semis = new ArrayList<>(byRound.getOrDefault(ROUND_FINAL_FOUR, List.of()));
        List<String> left = splitRegions(regionNames, semis, teamRegion, true);
        List<String> right = splitRegions(regionNames, semis, teamRegion, false);

        Game semiLeftGame = extractSemi(semis, teamRegion, left);
        Game semiRightGame = extractSemi(semis, teamRegion, right);
        if (semiLeftGame == null && !semis.isEmpty()) semiLeftGame = semis.remove(0);
        if (semiRightGame == null && !semis.isEmpty()) semiRightGame = semis.remove(0);

        BracketView.Slot semifinalLeft = semiLeftGame != null
                ? gameSlot(semiLeftGame, null, null)
                : placeholderSemi(left, placed);
        BracketView.Slot semifinalRight = semiRightGame != null
                ? gameSlot(semiRightGame, null, null)
                : placeholderSemi(right, placed);

        Game champGame = byRound.getOrDefault(ROUND_CHAMPIONSHIP, List.of()).stream().findFirst().orElse(null);
        BracketView.Slot championship = champGame != null
                ? gameSlot(champGame, null, null)
                : new BracketView.Slot(null, null, false,
                        semiWinnerLine(semiLeftGame), semiWinnerLine(semiRightGame), null, null);

        BracketView.TeamLine champion = null;
        if (champGame != null) {
            Team w = winnerOf(champGame);
            if (w != null) champion = teamLine(w, seedOf(champGame, w), null, false);
        }

        List<BracketView.Region> leftViews = left.stream().map(regionViews::get).filter(Objects::nonNull).toList();
        List<BracketView.Region> rightViews = right.stream().map(regionViews::get).filter(Objects::nonNull).toList();
        return new BracketView(year, leftViews, rightViews, semifinalLeft, semifinalRight, championship, champion);
    }

    /** Places each regional game into its derived slot: Game[round 0..3][slot]. */
    private static Map<String, Game[][]> placeRegionGames(Map<String, List<Game>> byRound, List<String> regionNames) {
        Map<String, Game[][]> placed = new LinkedHashMap<>();
        for (String region : regionNames) {
            Game[][] slots = new Game[REGION_ROUNDS.size()][];
            for (int r = 0; r < slots.length; r++) slots[r] = new Game[8 >> r];
            placed.put(region, slots);
        }
        for (int r = 0; r < REGION_ROUNDS.size(); r++) {
            List<Game> unplaced = new ArrayList<>();
            for (Game g : byRound.getOrDefault(REGION_ROUNDS.get(r), List.of())) {
                Game[][] slots = placed.get(g.getTournamentRegion());
                if (slots == null) continue;
                int idx = slotIndexFor(g, r, slots);
                if (idx >= 0 && idx < slots[r].length && slots[r][idx] == null) slots[r][idx] = g;
                else unplaced.add(g);
            }
            for (Game g : unplaced) {
                Game[] row = placed.get(g.getTournamentRegion())[r];
                for (int i = 0; i < row.length; i++) {
                    if (row[i] == null) {
                        row[i] = g;
                        break;
                    }
                }
            }
        }
        return placed;
    }

    private static int slotIndexFor(Game g, int round, Game[][] slots) {
        Integer slot = firstRoundSlot(g.getHomeSeed());
        if (slot == null) slot = firstRoundSlot(g.getAwaySeed());
        if (slot != null) return slot >> round;
        if (round > 0) {
            Game[] feeders = slots[round - 1];
            for (int i = 0; i < feeders.length; i++) {
                if (feeders[i] != null && shareTeam(feeders[i], g)) return i / 2;
            }
        }
        return -1;
    }

    private static boolean shareTeam(Game a, Game b) {
        Long h = a.getHomeTeam().getId(), w = a.getAwayTeam().getId();
        return Objects.equals(h, b.getHomeTeam().getId()) || Objects.equals(h, b.getAwayTeam().getId())
                || Objects.equals(w, b.getHomeTeam().getId()) || Objects.equals(w, b.getAwayTeam().getId());
    }

    /** region -> first-round slot -> {note, tooltip} for First Four play-in games. */
    private static Map<String, Map<Integer, String[]>> firstFourNotes(Map<String, List<Game>> byRound) {
        Map<String, Map<Integer, String[]>> notes = new HashMap<>();
        for (Game g : byRound.getOrDefault(ROUND_FIRST_FOUR, List.of())) {
            Integer seed = g.getHomeSeed() != null ? g.getHomeSeed() : g.getAwaySeed();
            Integer slot = firstRoundSlot(seed);
            if (g.getTournamentRegion() == null || slot == null) continue;
            notes.computeIfAbsent(g.getTournamentRegion(), k -> new HashMap<>())
                    .put(slot, new String[]{firstFourNote(g), firstFourTooltip(g)});
        }
        return notes;
    }

    private static BracketView.Slot slotView(Game[][] slots, int r, int i, String[] ff) {
        Game g = slots[r][i];
        if (g != null) return gameSlot(g, r, ff);
        BracketView.TeamLine top = r == 0 ? tbdLine("TBD", null) : feederLine(slots, r - 1, 2 * i);
        BracketView.TeamLine bottom = r == 0 ? tbdLine("TBD", null) : feederLine(slots, r - 1, 2 * i + 1);
        return new BracketView.Slot(null, null, false, top, bottom,
                ff == null ? null : ff[0], ff == null ? null : ff[1]);
    }

    private static BracketView.TeamLine feederLine(Game[][] slots, int r, int i) {
        Game f = slots[r][i];
        if (f == null) return tbdLine("TBD", null);
        Team w = winnerOf(f);
        if (w != null) return teamLine(w, seedOf(f, w), null, false);
        Integer s1 = f.getHomeSeed(), s2 = f.getAwaySeed();
        String label = s1 != null && s2 != null
                ? "W " + Math.min(s1, s2) + "/" + Math.max(s1, s2)
                : "TBD";
        return tbdLine(label, "Winner of " + f.getAwayTeam().getName() + " vs " + f.getHomeTeam().getName());
    }

    /** round is null for Final Four / championship slots (home team listed on top). */
    private static BracketView.Slot gameSlot(Game g, Integer round, String[] ff) {
        boolean homeTop = isHomeTop(g, round);
        Team topTeam = homeTop ? g.getHomeTeam() : g.getAwayTeam();
        Team bottomTeam = homeTop ? g.getAwayTeam() : g.getHomeTeam();
        Integer topSeed = homeTop ? g.getHomeSeed() : g.getAwaySeed();
        Integer bottomSeed = homeTop ? g.getAwaySeed() : g.getHomeSeed();
        Integer topScore = homeTop ? g.getHomeScore() : g.getAwayScore();
        Integer bottomScore = homeTop ? g.getAwayScore() : g.getHomeScore();
        Team winner = winnerOf(g);

        return new BracketView.Slot(
                g.getId(),
                metaLabel(g),
                g.getStatus() == Game.GameStatus.IN_PROGRESS,
                teamLine(topTeam, topSeed, topScore, winner == topTeam),
                teamLine(bottomTeam, bottomSeed, bottomScore, winner == bottomTeam),
                ff == null ? null : ff[0],
                ff == null ? null : ff[1]);
    }

    private static boolean isHomeTop(Game g, Integer round) {
        Integer hs = g.getHomeSeed(), as = g.getAwaySeed();
        if (round == null || hs == null || as == null) return true;
        Integer hp = SEED_POSITION.get(hs), ap = SEED_POSITION.get(as);
        if (hp == null || ap == null) return true;
        int hKey = round == 0 ? hp : (hp / 2) >> (round - 1);
        int aKey = round == 0 ? ap : (ap / 2) >> (round - 1);
        return hKey != aKey ? hKey < aKey : hs <= as;
    }

    /**
     * Region names for one side of the bracket. Defaults to canonical halves; once a
     * semifinal with teams from two known regions exists, those two regions pair up.
     */
    private static List<String> splitRegions(List<String> regionNames, List<Game> semis,
                                             Map<Long, String> teamRegion, boolean leftSide) {
        if (regionNames.size() == 4) {
            List<String> pair = semiPair(regionNames, semis, teamRegion);
            if (pair != null) {
                boolean pairIsLeft = pair.contains(regionNames.get(0));
                return regionNames.stream()
                        .filter(r -> (pairIsLeft == pair.contains(r)) == leftSide)
                        .toList();
            }
        }
        int half = (regionNames.size() + 1) / 2;
        return leftSide ? regionNames.subList(0, half) : regionNames.subList(half, regionNames.size());
    }

    private static List<String> semiPair(List<String> regionNames, List<Game> semis, Map<Long, String> teamRegion) {
        List<String> firstFound = null;
        for (Game semi : semis) {
            String r1 = teamRegion.get(semi.getHomeTeam().getId());
            String r2 = teamRegion.get(semi.getAwayTeam().getId());
            if (r1 == null || r2 == null || r1.equals(r2)) continue;
            if (r1.equals(regionNames.get(0)) || r2.equals(regionNames.get(0))) return List.of(r1, r2);
            if (firstFound == null) firstFound = List.of(r1, r2);
        }
        return firstFound;
    }

    private static Game extractSemi(List<Game> pool, Map<Long, String> teamRegion, List<String> side) {
        for (Iterator<Game> it = pool.iterator(); it.hasNext(); ) {
            Game g = it.next();
            String r1 = teamRegion.get(g.getHomeTeam().getId());
            String r2 = teamRegion.get(g.getAwayTeam().getId());
            if ((r1 != null && side.contains(r1)) || (r2 != null && side.contains(r2))) {
                it.remove();
                return g;
            }
        }
        return null;
    }

    private static BracketView.Slot placeholderSemi(List<String> side, Map<String, Game[][]> placed) {
        BracketView.TeamLine top = side.size() > 0 ? regionChampLine(side.get(0), placed.get(side.get(0))) : tbdLine("TBD", null);
        BracketView.TeamLine bottom = side.size() > 1 ? regionChampLine(side.get(1), placed.get(side.get(1))) : tbdLine("TBD", null);
        return new BracketView.Slot(null, null, false, top, bottom, null, null);
    }

    private static BracketView.TeamLine regionChampLine(String region, Game[][] slots) {
        Game e8 = slots[slots.length - 1][0];
        if (e8 != null) {
            Team w = winnerOf(e8);
            if (w != null) return teamLine(w, seedOf(e8, w), null, false);
        }
        return tbdLine(region, region + " region champion");
    }

    private static BracketView.TeamLine semiWinnerLine(Game semi) {
        if (semi == null) return tbdLine("TBD", null);
        Team w = winnerOf(semi);
        if (w != null) return teamLine(w, seedOf(semi, w), null, false);
        return tbdLine("TBD", "Winner of " + semi.getAwayTeam().getName() + " vs " + semi.getHomeTeam().getName());
    }

    private static Team winnerOf(Game g) {
        if (g.getStatus() != Game.GameStatus.FINAL || g.getHomeScore() == null || g.getAwayScore() == null) return null;
        return g.getHomeScore() >= g.getAwayScore() ? g.getHomeTeam() : g.getAwayTeam();
    }

    private static Integer seedOf(Game g, Team t) {
        return t == g.getHomeTeam() ? g.getHomeSeed() : g.getAwaySeed();
    }

    private static BracketView.TeamLine teamLine(Team t, Integer seed, Integer score, boolean winner) {
        String label = t.getNickname() != null ? t.getNickname()
                : t.getAbbreviation() != null ? t.getAbbreviation() : t.getName();
        return new BracketView.TeamLine(seed, label, t.getName(), t.getId(), t.getLogoUrl(), score, winner, false);
    }

    private static BracketView.TeamLine tbdLine(String label, String tooltip) {
        return new BracketView.TeamLine(null, label, tooltip, null, null, null, false, true);
    }

    private static String metaLabel(Game g) {
        String date = g.getGameDate() != null ? DATE_FMT.format(g.getGameDate()) : null;
        return switch (g.getStatus()) {
            case SCHEDULED -> {
                String spread = spreadLabel(g);
                if (spread == null) yield date;
                yield date != null ? date + " · " + spread : spread;
            }
            case IN_PROGRESS -> date;
            case POSTPONED -> date != null ? date + " · PPD" : "PPD";
            case CANCELLED -> "Cancelled";
            case FINAL -> null;
        };
    }

    private static String spreadLabel(Game g) {
        BettingOdds odds = g.getBettingOdds();
        if (odds == null || odds.getSpread() == null) return null;
        double s = odds.getSpread().doubleValue();
        if (s == 0) return "PK";
        Team fav = s < 0 ? g.getHomeTeam() : g.getAwayTeam();
        return shortAbbr(fav) + " " + String.format(Locale.US, "%.1f", -Math.abs(s));
    }

    private static String firstFourNote(Game g) {
        Team w = winnerOf(g);
        if (w != null) {
            Team l = w == g.getHomeTeam() ? g.getAwayTeam() : g.getHomeTeam();
            int wScore = Math.max(g.getHomeScore(), g.getAwayScore());
            int lScore = Math.min(g.getHomeScore(), g.getAwayScore());
            return "FF: " + shortAbbr(w) + " " + wScore + ", " + shortAbbr(l) + " " + lScore;
        }
        String date = g.getGameDate() != null ? " · " + DATE_FMT.format(g.getGameDate()) : "";
        return "FF: " + shortAbbr(g.getAwayTeam()) + " vs " + shortAbbr(g.getHomeTeam()) + date;
    }

    private static String firstFourTooltip(Game g) {
        return "First Four — " + g.getAwayTeam().getName() + " vs " + g.getHomeTeam().getName();
    }

    private static String shortAbbr(Team t) {
        return t.getAbbreviation() != null ? t.getAbbreviation()
                : t.getNickname() != null ? t.getNickname() : t.getName();
    }

    private static Integer firstRoundSlot(Integer seed) {
        Integer p = seed == null ? null : SEED_POSITION.get(seed);
        return p == null ? null : p / 2;
    }

    private static int canonicalRegionIndex(String region) {
        int idx = CANONICAL_REGIONS.indexOf(region);
        return idx >= 0 ? idx : CANONICAL_REGIONS.size();
    }

    private static Map<Integer, Integer> seedPositions() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < FIRST_ROUND_SEED_ORDER.length; i++) m.put(FIRST_ROUND_SEED_ORDER[i], i);
        return Map.copyOf(m);
    }
}
