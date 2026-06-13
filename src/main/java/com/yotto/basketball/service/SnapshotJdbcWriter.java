package com.yotto.basketball.service;

import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.SeasonPopulationStat;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * Batched INSERT path for the snapshot tables.
 *
 * <p>The snapshot entities use IDENTITY generation, which prevents Hibernate from
 * batching inserts at all — {@code saveAll} on a season's snapshots executes one
 * round-trip per row (~250k per full recalc). Nothing ever reads the generated IDs
 * back on the write path, so these writes bypass JPA entirely: services build
 * unmanaged entity instances as in-memory carriers and this writer persists them
 * with {@code JdbcTemplate.batchUpdate} in chunks of {@link #BATCH_SIZE}.
 *
 * <p>Runs inside the caller's transaction (the JPA transaction binds the JDBC
 * connection for the same DataSource), so delete + batch-insert remains one atomic
 * swap. Column lists must match the Flyway DDL; {@code SnapshotJdbcWriterTest}
 * round-trips every field through the JPA entities to guard against drift.
 */
@Component
public class SnapshotJdbcWriter {

    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public SnapshotJdbcWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_TEAM_SEASON_STAT_SNAPSHOT = """
            INSERT INTO team_season_stat_snapshots (
                team_id, season_id, snapshot_date, games_played, wins, losses,
                win_pct, mean_pts_for, stddev_pts_for, mean_pts_against, stddev_pts_against,
                correlation_pts, mean_margin, stddev_margin,
                rolling_wins, rolling_losses, rolling_mean_pts_for, rolling_mean_pts_against,
                zscore_win_pct, zscore_mean_pts_for, zscore_mean_pts_against,
                zscore_mean_margin, zscore_correlation_pts,
                conf_zscore_win_pct, conf_zscore_mean_pts_for, conf_zscore_mean_pts_against,
                conf_zscore_mean_margin,
                rpi, rpi_wp, rpi_owp, rpi_oowp
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    public void writeTeamSeasonStatSnapshots(List<TeamSeasonStatSnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_TEAM_SEASON_STAT_SNAPSHOT, rows, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.getTeam().getId());
            ps.setLong(2, s.getSeason().getId());
            ps.setDate(3, Date.valueOf(s.getSnapshotDate()));
            ps.setInt(4, s.getGamesPlayed());
            ps.setInt(5, s.getWins());
            ps.setInt(6, s.getLosses());
            setNullableDouble(ps, 7, s.getWinPct());
            setNullableDouble(ps, 8, s.getMeanPtsFor());
            setNullableDouble(ps, 9, s.getStddevPtsFor());
            setNullableDouble(ps, 10, s.getMeanPtsAgainst());
            setNullableDouble(ps, 11, s.getStddevPtsAgainst());
            setNullableDouble(ps, 12, s.getCorrelationPts());
            setNullableDouble(ps, 13, s.getMeanMargin());
            setNullableDouble(ps, 14, s.getStddevMargin());
            setNullableInt(ps, 15, s.getRollingWins());
            setNullableInt(ps, 16, s.getRollingLosses());
            setNullableDouble(ps, 17, s.getRollingMeanPtsFor());
            setNullableDouble(ps, 18, s.getRollingMeanPtsAgainst());
            setNullableDouble(ps, 19, s.getZscoreWinPct());
            setNullableDouble(ps, 20, s.getZscoreMeanPtsFor());
            setNullableDouble(ps, 21, s.getZscoreMeanPtsAgainst());
            setNullableDouble(ps, 22, s.getZscoreMeanMargin());
            setNullableDouble(ps, 23, s.getZscoreCorrelationPts());
            setNullableDouble(ps, 24, s.getConfZscoreWinPct());
            setNullableDouble(ps, 25, s.getConfZscoreMeanPtsFor());
            setNullableDouble(ps, 26, s.getConfZscoreMeanPtsAgainst());
            setNullableDouble(ps, 27, s.getConfZscoreMeanMargin());
            setNullableDouble(ps, 28, s.getRpi());
            setNullableDouble(ps, 29, s.getRpiWp());
            setNullableDouble(ps, 30, s.getRpiOwp());
            setNullableDouble(ps, 31, s.getRpiOowp());
        });
    }

    private static final String INSERT_SEASON_POPULATION_STAT = """
            INSERT INTO season_population_stats (
                season_id, conference_id, stat_date, stat_name,
                pop_mean, pop_stddev, pop_min, pop_max, team_count
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """;

    public void writeSeasonPopulationStats(List<SeasonPopulationStat> rows) {
        jdbcTemplate.batchUpdate(INSERT_SEASON_POPULATION_STAT, rows, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.getSeason().getId());
            if (s.getConference() != null) {
                ps.setLong(2, s.getConference().getId());
            } else {
                ps.setNull(2, Types.BIGINT);
            }
            ps.setDate(3, Date.valueOf(s.getStatDate()));
            ps.setString(4, s.getStatName());
            setNullableDouble(ps, 5, s.getPopMean());
            setNullableDouble(ps, 6, s.getPopStddev());
            setNullableDouble(ps, 7, s.getPopMin());
            setNullableDouble(ps, 8, s.getPopMax());
            ps.setInt(9, s.getTeamCount());
        });
    }

    private static final String INSERT_TEAM_POWER_RATING_SNAPSHOT = """
            INSERT INTO team_power_rating_snapshots (
                team_id, season_id, model_type, snapshot_date,
                rating, rank, games_played, calculated_at
            ) VALUES (?,?,?,?,?,?,?,?)
            """;

    public void writeTeamPowerRatingSnapshots(List<TeamPowerRatingSnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_TEAM_POWER_RATING_SNAPSHOT, rows, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.getTeam().getId());
            ps.setLong(2, s.getSeason().getId());
            ps.setString(3, s.getModelType());
            ps.setDate(4, Date.valueOf(s.getSnapshotDate()));
            ps.setDouble(5, s.getRating());
            setNullableInt(ps, 6, s.getRank());
            ps.setInt(7, s.getGamesPlayed());
            ps.setTimestamp(8, Timestamp.valueOf(s.getCalculatedAt()));
        });
    }

    private static final String INSERT_POWER_MODEL_PARAM_SNAPSHOT = """
            INSERT INTO power_model_param_snapshots (
                season_id, model_type, snapshot_date, param_name, param_value, calculated_at
            ) VALUES (?,?,?,?,?,?)
            """;

    public void writePowerModelParamSnapshots(List<PowerModelParamSnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_POWER_MODEL_PARAM_SNAPSHOT, rows, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.getSeason().getId());
            ps.setString(2, s.getModelType());
            ps.setDate(3, Date.valueOf(s.getSnapshotDate()));
            ps.setString(4, s.getParamName());
            ps.setDouble(5, s.getParamValue());
            ps.setTimestamp(6, Timestamp.valueOf(s.getCalculatedAt()));
        });
    }

    private static final String INSERT_TEAM_STAT_SNAPSHOT = """
            INSERT INTO team_stat_snapshots (
                team_id, season_id, snapshot_date, stat_name, value,
                games_played, rank, zscore, conf_zscore
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """;

    public void writeTeamStatSnapshots(List<TeamStatSnapshot> rows) {
        jdbcTemplate.batchUpdate(INSERT_TEAM_STAT_SNAPSHOT, rows, BATCH_SIZE, (ps, s) -> {
            ps.setLong(1, s.getTeam().getId());
            ps.setLong(2, s.getSeason().getId());
            ps.setDate(3, Date.valueOf(s.getSnapshotDate()));
            ps.setString(4, s.getStatName());
            ps.setDouble(5, s.getValue());
            ps.setInt(6, s.getGamesPlayed());
            setNullableInt(ps, 7, s.getRank());
            setNullableDouble(ps, 8, s.getZscore());
            setNullableDouble(ps, 9, s.getConfZscore());
        });
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double value) throws SQLException {
        if (value != null) {
            ps.setDouble(idx, value);
        } else {
            ps.setNull(idx, Types.DOUBLE);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(idx, value);
        } else {
            ps.setNull(idx, Types.INTEGER);
        }
    }
}
