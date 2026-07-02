-- Low-privilege monitoring role for the Netdata postgres collector.
-- pg_monitor grants read access to pg_stat_* views without any access to application tables.
-- The password comes from the NETDATA_DB_PASSWORD env var via a Flyway placeholder
-- (spring.flyway.placeholders.netdata_password). Roles are cluster-wide, so guard for reruns.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'netdata') THEN
        EXECUTE format('CREATE ROLE netdata LOGIN PASSWORD %L', '${netdata_password}');
    ELSE
        EXECUTE format('ALTER ROLE netdata WITH LOGIN PASSWORD %L', '${netdata_password}');
    END IF;
    GRANT pg_monitor TO netdata;
END
$$;
