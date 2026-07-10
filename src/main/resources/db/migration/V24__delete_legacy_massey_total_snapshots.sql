-- The Massey totals model was renamed from MASSEY_TOTAL to MASSEY_TOTALS on 2026-03-15.
-- calculateAndStoreForSeason() only deletes rows under the new name, so snapshots written
-- before the rename were orphaned and could be silently picked up by the ML training
-- script (which queried the old name until it was fixed alongside this migration).
DELETE FROM team_power_rating_snapshots WHERE model_type = 'MASSEY_TOTAL';
DELETE FROM power_model_param_snapshots WHERE model_type = 'MASSEY_TOTAL';
