package com.yotto.basketball.service;

import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link BracketView} as a monospace text bracket — the {@code /bracket.txt}
 * easter egg. Pure function over the same view model the HTML bracket uses.
 *
 * <p>Layout: each region is a 4-column grid (1st Round → Elite 8) with the classic
 * binary-tree vertical spacing; team lines carry seed, name, score, and a {@code *}
 * on the winner. Final Four and championship follow, centered.
 */
public final class BracketTextRenderer {

    private static final int COL_WIDTH = 30;
    private static final int LABEL_WIDTH = 16;
    private static final String[] ROUND_HEADERS = {"1st Round", "2nd Round", "Sweet 16", "Elite 8"};

    private BracketTextRenderer() {}

    public static String render(BracketView b) {
        StringBuilder sb = new StringBuilder();
        String title = b.year() + " NCAA TOURNAMENT";
        sb.append(center(title, COL_WIDTH * 4)).append('\n');
        sb.append(center("═".repeat(title.length() + 4), COL_WIDTH * 4)).append("\n\n");

        for (BracketView.Region region : b.regions()) {
            renderRegion(sb, region);
            sb.append('\n');
        }

        sb.append(center("FINAL FOUR", COL_WIDTH * 4)).append('\n');
        appendCenteredGame(sb, b.semifinalLeft());
        appendCenteredGame(sb, b.semifinalRight());
        sb.append('\n');
        sb.append(center("CHAMPIONSHIP", COL_WIDTH * 4)).append('\n');
        appendCenteredGame(sb, b.championship());
        if (b.champion() != null && !b.champion().tbd()) {
            sb.append('\n');
            sb.append(center("★ " + b.champion().label().toUpperCase(Locale.US)
                    + " — NATIONAL CHAMPIONS ★", COL_WIDTH * 4)).append('\n');
        }
        sb.append('\n');
        sb.append(center("deepfij — college basketball, quantified", COL_WIDTH * 4)).append('\n');
        sb.append(center("this is not betting advice", COL_WIDTH * 4)).append('\n');
        return sb.toString();
    }

    private static void renderRegion(StringBuilder sb, BracketView.Region region) {
        sb.append("──── ").append(region.name().toUpperCase(Locale.US)).append(' ')
                .append("─".repeat(Math.max(1, COL_WIDTH * 4 - region.name().length() - 6))).append('\n');
        StringBuilder headers = new StringBuilder();
        for (String h : ROUND_HEADERS) {
            headers.append(pad(h, COL_WIDTH));
        }
        sb.append(headers).append('\n');

        String[][] grid = new String[31][4];
        List<List<BracketView.Slot>> rounds = region.rounds();
        for (int r = 0; r < Math.min(4, rounds.size()); r++) {
            List<BracketView.Slot> slots = rounds.get(r);
            int lineIdx = 0;
            for (BracketView.Slot slot : slots) {
                placeLine(grid, r, lineIdx++, slot.top());
                placeLine(grid, r, lineIdx++, slot.bottom());
            }
        }
        for (String[] row : grid) {
            StringBuilder line = new StringBuilder();
            for (String cell : row) {
                line.append(pad(cell == null ? "" : cell, COL_WIDTH));
            }
            // trim trailing spaces so curl output stays tidy
            sb.append(stripTrailing(line)).append('\n');
        }
    }

    /** Row placement: R0 lines at 2i, R1 at 4i+1, R2 at 8i+3, R3 at 16i+7 (binary-tree midpoints). */
    private static void placeLine(String[][] grid, int round, int i, BracketView.TeamLine line) {
        int row = switch (round) {
            case 0 -> 2 * i;
            case 1 -> 4 * i + 1;
            case 2 -> 8 * i + 3;
            default -> 16 * i + 7;
        };
        if (row >= grid.length) return;
        String connector = round < 3 ? (i % 2 == 0 ? " ─┐" : " ─┘") : "";
        grid[row][round] = teamLine(line) + connector;
    }

    private static String teamLine(BracketView.TeamLine l) {
        if (l == null) return "";
        String seed = l.seed() != null ? String.format("(%2d)", l.seed()) : "    ";
        String label = l.label() == null ? "TBD" : l.label();
        if (label.length() > LABEL_WIDTH) label = label.substring(0, LABEL_WIDTH);
        String score = l.score() != null ? String.valueOf(l.score()) : "";
        String win = l.winner() ? "*" : " "; // always one column so connectors align
        return String.format("%s %-" + LABEL_WIDTH + "s %3s%s", seed, label, score, win);
    }

    private static void appendCenteredGame(StringBuilder sb, BracketView.Slot slot) {
        if (slot == null) return;
        String top = stripTrailing(new StringBuilder(teamLine(slot.top())));
        String bottom = stripTrailing(new StringBuilder(teamLine(slot.bottom())));
        sb.append(center(top + "  vs  " + bottom, COL_WIDTH * 4)).append('\n');
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s.substring(0, width) : s + " ".repeat(width - s.length());
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat((width - s.length()) / 2) + s;
    }

    private static String stripTrailing(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }
}
