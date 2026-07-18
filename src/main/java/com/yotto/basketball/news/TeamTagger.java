package com.yotto.basketball.news;

import com.yotto.basketball.entity.NewsAlias;
import com.yotto.basketball.repository.NewsAliasRepository;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gazetteer matcher (docs/NEWS_MODULE.md §5.6–5.7): Aho-Corasick over
 * title + body with whole-word boundaries, longest-match-wins, mixed case
 * sensitivity (two automata), and corroboration-gated ambiguous aliases.
 *
 * The automaton is rebuilt from the news_aliases table on first use and after
 * any alias mutation ({@link #invalidate()}); it is a few thousand strings, so
 * a rebuild is milliseconds.
 */
@Component
public class TeamTagger {

    private static final Logger log = LoggerFactory.getLogger(TeamTagger.class);

    static final double TITLE_HIT_WEIGHT = 3.0;
    static final double BODY_HIT_WEIGHT = 1.0;
    static final int BODY_HIT_CAP = 4;
    static final double AMBIGUOUS_BONUS = 1.5;
    static final double CONFIDENCE_DIVISOR = 5.0;

    private final NewsAliasRepository aliasRepository;
    private volatile Gazetteer gazetteer;

    public TeamTagger(NewsAliasRepository aliasRepository) {
        this.aliasRepository = aliasRepository;
    }

    /** Drops the cached automaton; the next tag() call rebuilds from the DB. */
    public void invalidate() {
        gazetteer = null;
    }

    public TagResult tag(String title, String bodyText) {
        Gazetteer g = gazetteer;
        if (g == null) {
            synchronized (this) {
                g = gazetteer;
                if (g == null) {
                    long start = System.currentTimeMillis();
                    g = Gazetteer.build(aliasRepository.findByEnabledTrue());
                    gazetteer = g;
                    log.debug("Rebuilt news gazetteer in {} ms", System.currentTimeMillis() - start);
                }
            }
        }
        return g.tag(title, bodyText);
    }

    /** Test hook: tag against an explicit alias list without touching the DB. */
    static TagResult tagWith(List<NewsAlias> aliases, String title, String bodyText) {
        return Gazetteer.build(aliases).tag(title, bodyText);
    }

    private record Row(boolean isTeam, Long targetId, boolean ambiguous, String display) {
    }

    private static final class Gazetteer {
        private final Trie caseInsensitiveTrie;
        private final Trie caseSensitiveTrie;
        /** Alias key (lowercased for CI, exact for CS) → target rows. */
        private final Map<String, List<Row>> rowsByKey;

        private Gazetteer(Trie ci, Trie cs, Map<String, List<Row>> rowsByKey) {
            this.caseInsensitiveTrie = ci;
            this.caseSensitiveTrie = cs;
            this.rowsByKey = rowsByKey;
        }

        static Gazetteer build(List<NewsAlias> aliases) {
            // BLOCK rows suppress the same (alias text, target) from any other kind
            Set<String> blocked = new HashSet<>();
            for (NewsAlias a : aliases) {
                if (a.getKind() == NewsAlias.Kind.BLOCK) {
                    blocked.add(blockKey(a));
                }
            }

            Map<String, List<Row>> rowsByKey = new HashMap<>();
            Set<String> ciKeywords = new HashSet<>();
            Set<String> csKeywords = new HashSet<>();
            for (NewsAlias a : aliases) {
                if (a.getKind() == NewsAlias.Kind.BLOCK || blocked.contains(blockKey(a))) {
                    continue;
                }
                String text = a.getAlias().strip();
                if (text.length() < 3) {
                    continue;
                }
                boolean caseSensitive = Boolean.TRUE.equals(a.getCaseSensitive()) || text.length() <= 4;
                String key = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
                boolean isTeam = a.getTeam() != null;
                Long targetId = isTeam ? a.getTeam().getId() : a.getConference().getId();
                rowsByKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Row(isTeam, targetId, Boolean.TRUE.equals(a.getAmbiguous()), text));
                if (caseSensitive) {
                    csKeywords.add(key);
                } else {
                    ciKeywords.add(key);
                }
            }

            Trie ci = ciKeywords.isEmpty() ? null : Trie.builder()
                    .ignoreCase().onlyWholeWords().ignoreOverlaps()
                    .addKeywords(ciKeywords).build();
            Trie cs = csKeywords.isEmpty() ? null : Trie.builder()
                    .onlyWholeWords().ignoreOverlaps()
                    .addKeywords(csKeywords).build();
            return new Gazetteer(ci, cs, rowsByKey);
        }

        private static String blockKey(NewsAlias a) {
            String target = a.getTeam() != null ? "T" + a.getTeam().getId() : "C" + a.getConference().getId();
            return a.getAlias().strip().toLowerCase(Locale.ROOT) + "|" + target;
        }

        TagResult tag(String title, String bodyText) {
            Map<String, Integer> titleHits = match(title);
            Map<String, Integer> bodyHits = match(bodyText);
            if (titleHits.isEmpty() && bodyHits.isEmpty()) {
                return TagResult.empty();
            }

            Map<String, Accum> accums = new HashMap<>();
            Map<String, Set<String>> textsHitByTarget = new HashMap<>();
            // pass 1: unambiguous hits score directly; every hit (ambiguous too)
            // records which alias texts touched which target, for corroboration
            Set<String> allKeys = new HashSet<>(titleHits.keySet());
            allKeys.addAll(bodyHits.keySet());
            for (String key : allKeys) {
                int inTitle = titleHits.getOrDefault(key, 0);
                int inBody = bodyHits.getOrDefault(key, 0);
                for (Row row : rowsByKey.getOrDefault(key, List.of())) {
                    textsHitByTarget.computeIfAbsent(accumKey(row), k -> new HashSet<>()).add(key);
                    if (row.ambiguous()) {
                        continue;
                    }
                    Accum accum = accums.computeIfAbsent(accumKey(row), k -> new Accum());
                    accum.titleHits += inTitle;
                    accum.bodyHits += inBody;
                    accum.aliases.add(row.display());
                }
            }
            // pass 2 (§5.7): an ambiguous alias credits its candidate only when
            // exactly one candidate is corroborated by some OTHER alias hit —
            // "Kansas" + "Jayhawks" corroborate each other; a bare "Miami" (two
            // candidates, no other hits) credits no one.
            for (String key : allKeys) {
                List<Row> ambiguousRows = rowsByKey.getOrDefault(key, List.of()).stream()
                        .filter(Row::ambiguous).toList();
                if (ambiguousRows.isEmpty()) {
                    continue;
                }
                List<Row> corroborated = ambiguousRows.stream()
                        .filter(r -> textsHitByTarget.getOrDefault(accumKey(r), Set.of()).stream()
                                .anyMatch(text -> !text.equals(key)))
                        .toList();
                if (corroborated.size() == 1) {
                    Row row = corroborated.get(0);
                    Accum accum = accums.computeIfAbsent(accumKey(row), k -> new Accum());
                    accum.ambiguousBonus += AMBIGUOUS_BONUS;
                    accum.aliases.add(row.display());
                }
            }

            Map<Long, TagResult.TargetScore> teams = new HashMap<>();
            Map<Long, TagResult.TargetScore> conferences = new HashMap<>();
            for (Map.Entry<String, Accum> e : accums.entrySet()) {
                Accum a = e.getValue();
                double score = a.score();
                if (score <= 0) {
                    continue;
                }
                double confidence = Math.min(1.0, score / CONFIDENCE_DIVISOR);
                String via = String.join(", ", a.aliases.stream().limit(3).toList());
                TagResult.TargetScore ts = new TagResult.TargetScore(score, confidence, via);
                Long id = Long.valueOf(e.getKey().substring(1));
                if (e.getKey().charAt(0) == 'T') {
                    teams.put(id, ts);
                } else {
                    conferences.put(id, ts);
                }
            }
            return new TagResult(teams, conferences);
        }

        private static String accumKey(Row row) {
            return (row.isTeam() ? "T" : "C") + row.targetId();
        }

        private Map<String, Integer> match(String text) {
            if (text == null || text.isBlank()) {
                return Map.of();
            }
            Map<String, Integer> counts = new HashMap<>();
            if (caseInsensitiveTrie != null) {
                for (Emit emit : caseInsensitiveTrie.parseText(text)) {
                    counts.merge(emit.getKeyword().toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
            if (caseSensitiveTrie != null) {
                for (Emit emit : caseSensitiveTrie.parseText(text)) {
                    counts.merge(emit.getKeyword(), 1, Integer::sum);
                }
            }
            return counts;
        }

        private static final class Accum {
            int titleHits;
            int bodyHits;
            double ambiguousBonus;
            final Set<String> aliases = new LinkedHashSet<>();

            double unambiguousScore() {
                return TITLE_HIT_WEIGHT * titleHits + BODY_HIT_WEIGHT * Math.min(bodyHits, BODY_HIT_CAP);
            }

            double score() {
                return unambiguousScore() + ambiguousBonus;
            }
        }
    }
}
