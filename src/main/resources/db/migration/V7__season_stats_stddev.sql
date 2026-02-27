-- Sample standard deviations of per-game points, computed from game results
ALTER TABLE season_statistics
    ADD COLUMN calc_stddev_pts_for     DOUBLE PRECISION,
    ADD COLUMN calc_stddev_pts_against DOUBLE PRECISION,
    ADD COLUMN calc_stddev_margin      DOUBLE PRECISION;
