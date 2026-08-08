package com.yotto.basketball.service;

/** Well-known preference keys. Keys are dot-namespaced lowercase. */
public final class PreferenceKeys {

    /** Opt-in daily update email ("true"/"false"). Default: false. */
    public static final String DAILY_UPDATE_EMAIL = "email.daily-update";

    /** Followed teams: CSV of team ids in follow order, capped by {@code FavoriteTeamService}. */
    public static final String FAVORITE_TEAM_IDS = "favorite.team-ids";

    private PreferenceKeys() {
    }
}
