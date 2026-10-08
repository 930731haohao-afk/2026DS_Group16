package edu.nccu.ai;

import edu.nccu.ai.Models.Page;
import java.util.*;

/** Inspectable scoring rules, intentionally kept separate from external model scores. */
public final class Ranking {
    private Ranking() { }
    public static double pageScore(Map<String,Integer> counts, int textLength) {
        double score = counts.values().stream().filter(n -> n > 0).mapToDouble(n -> 1 + Math.log(n)).sum();
        return round(score / Math.sqrt(1 + Math.max(0, textLength) / 1200.0));
    }
    public static double intentScore(Page page, String intent) {
        List<String> signals = switch (intent) {
            case "build" -> List.of("程式", "步驟", "安裝", "實作", "應用", "案例", "實證", "政策建議",
                "code", "pip", "python", "implementation", "application", "case study", "empirical", "policy recommendations");
            case "compare" -> List.of("比較", "差異", "取捨", "適用", "爭議", "論點", "利弊",
                "comparison", "versus", "trade-off", "difference", "debate", "arguments");
            default -> List.of("概念", "原理", "定義", "入門", "背景", "概述",
                "definition", "intuition", "overview", "beginner", "introduction", "background");
        };
        String full = page.title() + " " + page.text();
        long hits = signals.stream().filter(t -> KeywordCounter.count(full, t) > 0).count();
        long titleHits = signals.stream().filter(t -> KeywordCounter.count(page.title(), t) > 0).count();
        return Math.min(3.0, hits * 0.5 + Math.min(1.0, titleHits * 0.5));
    }
    public static String snippet(String text, List<String> keywords) {
        String clean = text.replaceAll("\\s+", " ").strip();
        if (clean.length() <= 360) return clean;
        String lower = clean.toLowerCase(Locale.ROOT);
        int first = Integer.MAX_VALUE;
        for (String keyword : keywords) {
            int at = lower.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (at >= 0) first = Math.min(first, at);
        }
        int start = first == Integer.MAX_VALUE ? 0 : Math.max(0, first - 70);
        int end = Math.min(clean.length(), start + 360);
        return (start == 0 ? "" : "…") + clean.substring(start, end) + (end == clean.length() ? "" : "…");
    }
    public static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }
}
