package edu.nccu.ai;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Authored fixtures only: normal test runs do not contact any external service. */
public final class PublicSearchTests {
    private static int checks;
    private PublicSearchTests() { }
    public static void main(String[] args) throws Exception { System.out.println("PublicSearchTests: " + run() + " checks"); }
    public static int run() throws Exception {
        checks = 0;
        noKeyAndParsing(); queries(); failures(); emptyAndBounds();
        return checks;
    }
    private static PublicSearch service(Providers.Transport transport) { return new PublicSearch(new Config(Map.of()), transport); }
    private static Providers.Reply reply(Object body) { return new Providers.Reply(200, Json.stringify(body)); }
    private static boolean wiki(Providers.Request request) { return request.uri().getHost().endsWith(".wikipedia.org"); }
    private static Map<String,Object> page(String title, String url, int index) {
        return Map.of("title", title, "fullurl", url, "index", index, "extract", "AI recursive self-improvement and society.");
    }
    private static Map<String,Object> book(String title, String url) {
        return Map.of("title", List.of(title), "URL", url, "publisher", "Fixture University Press",
                "published", Map.of("date-parts", List.of(List.of(2026, 9))), "abstract", "PRIVATE ABSTRACT MUST NOT BE REPRODUCED");
    }
    private static Providers.Reply wikiReply(List<?> pages) { return reply(Map.of("batchcomplete", true, "query", Map.of("pages", pages))); }
    private static Providers.Reply crossReply(List<?> rows) { return reply(Map.of("status", "ok", "message", Map.of("items", rows))); }

    private static void noKeyAndParsing() throws Exception {
        List<Providers.Request> requests = new CopyOnWriteArrayList<>();
        PublicSearch.Batch batch = service(request -> {
            requests.add(request);
            return wiki(request) ? wikiReply(List.of(
                    page("Second page", "https://en.wikipedia.org/wiki/Second", 2),
                    page("Recursive self-improvement", "https://en.wikipedia.org/wiki/Recursive_self-improvement", 1),
                    page("unsafe", "javascript:alert(1)", 3),
                    page("credentials", "https://user:password@example.org/", 4)))
                    : crossReply(List.of(book("AI &amp; <i>society</i>", "https://doi.org/10.1234/ai"),
                    book("bad", "file:///bad"), Map.of("title", List.of("DOI fallback"), "DOI", "10.1234/fallback"),
                    book("duplicate", "https://en.wikipedia.org/wiki/Second")));
        }).search("RSI", 10);
        check(requests.size() == 2, "Exactly two public requests; no credentials required");
        for (Providers.Request request : requests) {
            check(request.method().equals("GET") && request.body().isEmpty(), "Read-only GET request");
            check(request.headers().keySet().stream().noneMatch(key -> key.toLowerCase().contains("token") || key.equalsIgnoreCase("Authorization")), "No authentication headers");
            check(request.timeoutSeconds() == 10 && request.maxResponseBytes() == 1_048_576, "Strict time and byte limits");
            check(request.uri().getScheme().equals("https") && List.of("en.wikipedia.org", "api.crossref.org").contains(request.uri().getHost()), "Fixed official API hosts");
        }
        check(batch.hits().size() == 4, "Unsafe URLs excluded and duplicates merged");
        check(batch.hits().get(0).title().equals("Recursive self-improvement"), "Wiki index order retained");
        check(batch.hits().get(1).title().equals("AI & society"), "Crossref source title decoded and interleaved");
        check(batch.hits().get(1).snippet().contains("2026") && batch.hits().get(1).snippet().contains("僅提供書目"), "Metadata truthfully labeled");
        check(batch.hits().stream().noneMatch(hit -> hit.snippet().contains("PRIVATE ABSTRACT")), "No copyrighted abstract copied");
        check(batch.hits().get(0).provider().equals("Wikipedia 公開百科摘錄"), "Wiki provenance preserved");
        check(batch.hits().get(3).url().equals("https://doi.org/10.1234/fallback"), "DOI fallback yields real source URL");
        check(batch.warnings().isEmpty() && batch.query().equals("RSI"), "Query retained, success without warning");
    }

    private static void queries() throws Exception {
        check(PublicSearch.wikiQuery("RSI").equals("RSI artificial intelligence"), "RSI scoped to AI without restrictive aliases");
        check(PublicSearch.wikiQuery("AI 哲學").equals("人工智能 哲學"), "Chinese AI phrase normalized");
        check(PublicSearch.wikiQuery("人工智慧 地緣政治").equals("人工智能 地緣政治"), "Taiwan terminology searchable");
        check(PublicSearch.paperQuery("AI 地緣政治").equals("artificial intelligence geopolitics"), "Crossref translated query");
        check(PublicSearch.paperQuery("RSI").contains("recursive self-improvement"), "Paper RSI disambiguated");
        for (String raw : List.of("unexpected-new-topic-987", "AI sovereignty & chips", "未收錄的新概念")) {
            List<Providers.Request> requests = new CopyOnWriteArrayList<>();
            service(request -> { requests.add(request); return wiki(request) ? wikiReply(List.of()) : crossReply(List.of()); }).search(raw, 5);
            check(requests.size() == 2, "Unknown topics never blocked by a vocabulary whitelist");
            String expected = raw.equals("AI sovereignty & chips") ? "sovereignty & chips" : raw;
            check(requests.stream().allMatch(request -> URLDecoder.decode(request.uri().getRawQuery(), StandardCharsets.UTF_8).contains(expected)), "Arbitrary user terms preserved and encoded");
        }
    }

    private static void failures() throws Exception {
        PublicSearch.Batch partial = service(request -> wiki(request)
                ? wikiReply(List.of(page("Good page", "https://en.wikipedia.org/wiki/AI", 1)))
                : new Providers.Reply(429, "not included in diagnostics")).search("AI", 5);
        check(partial.hits().size() == 1 && partial.warnings().size() == 1, "One provider failure preserves other results");
        check(partial.warnings().getFirst().contains("Crossref") && partial.warnings().getFirst().contains("使用限制"), "Partial failure visible");
        check(!partial.provider().contains("Crossref"), "Active source label is accurate after partial failure");
        rejects(() -> service(request -> { throw new IOException("private server detail"); }).search("AI", 5), "連線失敗");
        rejects(() -> service(request -> new Providers.Reply(200, "{bad")).search("AI", 5), "格式不符");
        rejects(() -> service(request -> reply(Map.of())).search("AI", 5), "格式不符");
        rejects(() -> service(request -> wiki(request) ? reply(Map.of("error", Map.of("code", "failed")))
                : reply(Map.of("status", "error", "message", "bad"))).search("AI", 5), "格式不符");
        PublicSearch.Batch malformedOne = service(request -> wiki(request) ? reply(Map.of("query", Map.of("pages", "wrong")))
                : crossReply(List.of(book("Good paper", "https://doi.org/10.1234/good")))).search("AI", 5);
        check(malformedOne.hits().size() == 1 && malformedOne.warnings().getFirst().contains("Wikipedia"), "Malformed provider not disguised as zero matches");
        rejects(() -> service(request -> new Providers.Reply(200, " ".repeat(1_048_577))).search("AI", 5), "格式不符");
    }

    private static void emptyAndBounds() throws Exception {
        PublicSearch.Batch empty = service(request -> wiki(request) ? reply(Map.of("batchcomplete", true))
                : crossReply(List.of())).search("no-result", 5);
        check(empty.hits().isEmpty() && empty.warnings().isEmpty(), "Genuine empty result is not an error");
        List<Object> pages = new ArrayList<>(), books = new ArrayList<>();
        for (int i = 0; i < 12; i++) { pages.add(page("Wiki " + i, "https://en.wikipedia.org/wiki/AI_" + i, i)); books.add(book("Paper " + i, "https://doi.org/10.1234/" + i)); }
        PublicSearch many = service(request -> wiki(request) ? wikiReply(pages) : crossReply(books));
        check(many.search("AI", 500).hits().size() == 10, "At most ten merged results");
        check(many.search("AI", 1).hits().size() == 1, "Requested small limit honored");
        PublicSearch untouched = service(request -> { throw new AssertionError("Invalid input must not send network requests"); });
        rejects(() -> untouched.search(" ", 5), "搜尋詞");
        rejects(() -> untouched.search("x".repeat(501), 5), "搜尋詞");
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String expected) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException | IllegalArgumentException error) {
            if (!error.getMessage().contains(expected) || error.getMessage().contains("private server detail")) throw new AssertionError("Unexpected diagnostic: " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected safe failure");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
