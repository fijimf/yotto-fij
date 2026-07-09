-- Season-scoped conference naming (see docs/CONFERENCE_RENAME_PROPOSAL.md).
-- The conferences row always carries the CURRENT branding; superseded brandings
-- live here with the last Season.year (inclusive) they applied to. Display code
-- resolves (conference, season) -> name via ConferenceNamingService.
CREATE TABLE conference_name_history (
    id BIGSERIAL PRIMARY KEY,
    conference_id BIGINT NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    abbreviation VARCHAR(50),
    logo_url VARCHAR(500),
    last_season_year INT NOT NULL,
    CONSTRAINT uq_conference_name_history UNIQUE (conference_id, last_season_year)
);

-- The WAC rebranded to the United Athletic Conference starting with the
-- 2026-27 season (Season.year 2027); seasons through 2026 keep the WAC
-- branding. Values are hardcoded (not copied from the row) so the seed stays
-- correct even if a conferences scrape has already applied the new branding.
INSERT INTO conference_name_history (conference_id, name, abbreviation, logo_url, last_season_year)
SELECT id, 'Western Athletic Conference', 'WAC',
       'https://a.espncdn.com/i/teamlogos/ncaa_conf/sml/trans/wac.gif', 2026
FROM conferences
WHERE espn_id = '30';
