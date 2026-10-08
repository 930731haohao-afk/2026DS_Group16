package edu.nccu.ai;

import java.util.List;
import java.util.Map;

/** Shared immutable data exchanged by the crawler, ranking engine and providers. */
public final class Models {
    private Models() { }
    public record Page(String url, String title, String text, List<String> links,
                       String source, boolean synthetic) { }
    public record WebHit(String url, String title, String snippet, String provider) { }
    public record SearchOptions(String query, String intent, int maxDepth, int maxPages,
                                int limit, boolean expand) { }
    public record TreeNode(String url, String title, int depth, Map<String,Integer> counts,
                           double pageScore, List<TreeNode> children, String status, String warning) { }
    public record SearchResult(String url, String title, String snippet, String source,
                               int originalRank, Map<String,Integer> counts, double ruleScore,
                               double intentScore, double finalScore, Double jevScore, TreeNode tree) { }
    public record SearchReport(String query, String intent, List<String> keywords,
                               List<String> expandedKeywords, List<SearchResult> results,
                               List<String> warnings, int pagesFetched, boolean truncated) { }
    @FunctionalInterface public interface SearchBackend {
        List<String> candidates(String query, int limit) throws Exception;
    }
    @FunctionalInterface public interface PageSource {
        Page fetch(String url) throws Exception;
    }
}
