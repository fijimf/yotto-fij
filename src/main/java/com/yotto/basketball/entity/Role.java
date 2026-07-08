package com.yotto.basketball.entity;

/**
 * The only stored roles. Anonymous visitors carry Spring Security's implicit
 * ROLE_ANONYMOUS and are never persisted.
 */
public enum Role {
    ADMIN,
    USER
}
