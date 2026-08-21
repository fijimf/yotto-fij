package com.yotto.basketball.controller.dto;

/** One tab in the shared link-based tab strip ({@code fragments/tabs.html}). */
public record NavTab(String label, String url, boolean active) {
}
