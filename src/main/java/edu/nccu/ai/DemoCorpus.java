package edu.nccu.ai;

import edu.nccu.ai.Models.Page;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Authored educational fixtures; none of these texts claim to have been fetched from real sites. */
public final class DemoCorpus {
    private final LinkedHashMap<String,Page> pages;
    private DemoCorpus(LinkedHashMap<String,Page> pages) { this.pages = pages; }

    public static DemoCorpus load(Path path) throws IOException {
        LinkedHashMap<String,Page> loaded = new LinkedHashMap<>();
        try {
            for (Object item : Json.array(Json.parse(Files.readString(path, StandardCharsets.UTF_8)))) {
                Map<String,Object> value = Json.object(item);
                String url = SearchEngine.canonical(Json.string(value.get("url"), ""));
                if (url == null || !URIHost.isDemo(url)) throw new IllegalArgumentException("invalid fixture URL");
                List<String> links = Json.array(value.get("links")).stream().map(v -> Json.string(v, "")).toList();
                Page page = new Page(url, Json.string(value.get("title"), ""), Json.string(value.get("text"), ""),
                    links, "教學示範資料（自編）", true);
                if (page.title().isBlank() || page.text().isBlank() || loaded.putIfAbsent(url, page) != null)
                    throw new IllegalArgumentException("empty or duplicate fixture");
            }
        } catch (RuntimeException e) { throw new IOException("示範資料格式錯誤", e); }
        return new DemoCorpus(loaded);
    }

    public List<Page> pages() { return List.copyOf(pages.values()); }

    /** Simple corpus baseline uses number of distinct matching terms; engine applies its own ranking afterward. */
    public List<String> candidates(String query, int limit) {
        List<String> terms = KeywordCounter.keywords(query);
        return pages.values().stream().map(page -> new Candidate(page,
                KeywordCounter.counts(page.title() + " " + page.text(), terms).values().stream().filter(n -> n > 0).count()))
            .filter(candidate -> candidate.matches > 0)
            .sorted(Comparator.comparingLong(Candidate::matches).reversed().thenComparing(c -> c.page.url()))
            .limit(Math.max(0, Math.min(10, limit))).map(c -> c.page.url()).toList();
    }

    public Page fetch(String url) throws IOException {
        Page page = pages.get(SearchEngine.canonical(url));
        if (page == null) throw new FileNotFoundException("示範資料中沒有此頁面");
        return page;
    }
    private record Candidate(Page page, long matches) { }
    private static final class URIHost {
        static boolean isDemo(String url) { return "demo.local".equals(java.net.URI.create(url).getHost()); }
    }
}
