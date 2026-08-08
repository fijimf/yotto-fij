package com.yotto.basketball.news;

import com.yotto.basketball.config.NewsProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side for public news listings (docs/NEWS_MODULE.md §5.10): the display
 * score is static authority × an exponential recency decay computed in SQL at
 * read time, so nothing goes stale and nothing needs recomputing. Listings
 * only ever show cluster representatives that aren't hidden.
 */
@Service
public class NewsQueryService {

    /**
     * @param sourceName publisher name, or the discovering feed's name as fallback
     */
    public record NewsCard(Long id, String title, String subtitle, String url,
                           String sourceName, LocalDateTime publishedAt, boolean hasThumbnail) {
    }

    private static final RowMapper<CardRow> CARD_ROW_MAPPER = (rs, i) -> new CardRow(mapCard(rs),
            rs.getObject("source_id") != null ? rs.getLong("source_id") : null);

    private record CardRow(NewsCard card, Long sourceId) {
    }

    private static NewsCard mapCard(ResultSet rs) throws SQLException {
        return new NewsCard(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("url_canonical"),
                rs.getString("source_name"),
                rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getString("thumbnail_path") != null);
    }

    private static final String BASE_SELECT = """
            SELECT a.id, a.title, a.subtitle, a.url_canonical, a.published_at, a.thumbnail_path,
                   a.source_id,
                   COALESCE(s.name, d.name) AS source_name,
                   a.static_score * exp(-EXTRACT(EPOCH FROM (now() - a.published_at)) / ?) AS display_score
            FROM news_articles a
            LEFT JOIN news_sources s ON a.source_id = s.id
            LEFT JOIN news_sources d ON a.discovered_via_source_id = d.id
            WHERE a.duplicate_of_article_id IS NULL AND NOT a.hidden
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NewsProperties properties;

    public NewsQueryService(JdbcTemplate jdbcTemplate, NewsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    private double halfLifeToLambdaSeconds() {
        // decay uses exp(-age/τ); convert the configured half-life to τ = t½ / ln 2
        return halfLifeToLambdaSeconds(properties.getRanking().getHalfLifeHours());
    }

    private static double halfLifeToLambdaSeconds(double halfLifeHours) {
        return halfLifeHours * 3600.0 / Math.log(2);
    }

    /** Front-page panel: top N with a per-source cap so one outlet can't own it (§5.10). */
    public List<NewsCard> frontPage() {
        return frontPage(properties.getRanking().getFrontPageCount(),
                properties.getRanking().getHalfLifeHours());
    }

    /**
     * Front-page panel with explicit count and decay half-life — the off-season front page widens
     * both so the panel stays full when news volume drops ~10× (§5.10 staleness note).
     */
    public List<NewsCard> frontPage(int count, double halfLifeHours) {
        int want = count;
        int perSourceCap = properties.getRanking().getFrontPagePerSourceCap();
        List<CardRow> rows = jdbcTemplate.query(
                BASE_SELECT + " ORDER BY display_score DESC, a.published_at DESC LIMIT ?",
                CARD_ROW_MAPPER, halfLifeToLambdaSeconds(halfLifeHours), want * 5);

        Map<Long, Integer> perSource = new HashMap<>();
        List<NewsCard> out = new ArrayList<>();
        for (CardRow row : rows) {
            Long sourceKey = row.sourceId() == null ? -1L : row.sourceId();
            int used = perSource.getOrDefault(sourceKey, 0);
            if (used >= perSourceCap) {
                continue;
            }
            perSource.put(sourceKey, used + 1);
            out.add(row.card());
            if (out.size() >= want) {
                break;
            }
        }
        return out;
    }

    /** /news page with optional team/conference filters. */
    public List<NewsCard> newsPage(Long teamId, Long conferenceId, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(halfLifeToLambdaSeconds());
        if (teamId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM news_article_teams t WHERE t.article_id = a.id"
                    + " AND t.team_id = ? AND t.confidence >= ?)");
            args.add(teamId);
            args.add(properties.getRanking().getTeamPageMinConfidence());
        }
        if (conferenceId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM news_article_conferences c WHERE c.article_id = a.id"
                    + " AND c.conference_id = ? AND c.confidence >= ?)");
            args.add(conferenceId);
            args.add(properties.getRanking().getTeamPageMinConfidence() / 2);
        }
        sql.append(" ORDER BY display_score DESC, a.published_at DESC LIMIT ? OFFSET ?");
        args.add(pageSize + 1); // one extra row = "has next page"
        args.add((long) page * pageSize);
        return jdbcTemplate.query(sql.toString(), CARD_ROW_MAPPER, args.toArray()).stream()
                .map(CardRow::card).toList();
    }

    public List<NewsCard> teamNews(Long teamId, int limit) {
        return jdbcTemplate.query(BASE_SELECT + """
                         AND EXISTS (SELECT 1 FROM news_article_teams t WHERE t.article_id = a.id
                                     AND t.team_id = ? AND t.confidence >= ?)
                        ORDER BY display_score DESC, a.published_at DESC LIMIT ?
                        """,
                        CARD_ROW_MAPPER, halfLifeToLambdaSeconds(), teamId,
                        properties.getRanking().getTeamPageMinConfidence(), limit)
                .stream().map(CardRow::card).toList();
    }

    public List<NewsCard> conferenceNews(Long conferenceId, int limit) {
        return jdbcTemplate.query(BASE_SELECT + """
                         AND EXISTS (SELECT 1 FROM news_article_conferences c WHERE c.article_id = a.id
                                     AND c.conference_id = ? AND c.confidence >= ?)
                        ORDER BY display_score DESC, a.published_at DESC LIMIT ?
                        """,
                        CARD_ROW_MAPPER, halfLifeToLambdaSeconds(), conferenceId,
                        properties.getRanking().getTeamPageMinConfidence() / 2, limit)
                .stream().map(CardRow::card).toList();
    }
}
