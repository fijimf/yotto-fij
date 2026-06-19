-- ESPN exposes some postseason events (notably the College Basketball Crown, group 104) in its
-- conferences/standings feeds with the exact shape of a real conference. ConferenceScraper created
-- a bogus "College Basketball Crown" conference, and StandingsScraper then re-pointed each event
-- participant's (team, season) membership at it, clobbering their real conference.
--
-- The scrapers now filter these group ids (see EspnGroups). This migration removes the leaked rows.
-- The affected teams' correct memberships/season statistics are restored on the next standings
-- scrape (idempotent upsert), which now skips the offending groups.

DELETE FROM season_population_stats
WHERE conference_id IN (SELECT id FROM conferences WHERE espn_id IN ('104'));

DELETE FROM season_statistics
WHERE conference_id IN (SELECT id FROM conferences WHERE espn_id IN ('104'));

DELETE FROM conference_memberships
WHERE conference_id IN (SELECT id FROM conferences WHERE espn_id IN ('104'));

DELETE FROM conferences
WHERE espn_id IN ('104');
