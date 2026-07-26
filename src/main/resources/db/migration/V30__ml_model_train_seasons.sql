-- Records which seasons each ML bundle was actually trained on (comma-separated years)
-- and which season was held out as the test set. Used to badge in-sample seasons on the
-- public model-performance page: evaluation rows for a trained-on season flatter the
-- model and must not be read as out-of-sample accuracy.
ALTER TABLE ml_models ADD COLUMN train_seasons VARCHAR(200);
ALTER TABLE ml_models ADD COLUMN test_season INTEGER;
