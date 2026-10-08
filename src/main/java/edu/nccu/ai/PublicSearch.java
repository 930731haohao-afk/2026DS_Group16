package edu.nccu.ai;

import edu.nccu.ai.Models.WebHit;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Public encyclopedia and scholarly indexes; these snippets are not fetched webpage full text. */
public final class PublicSearch {
    private static final int MAX_BYTES = 1_048_576;
    private static final int TIMEOUT_SECONDS = 10;
    private static final String WIKI = "Wikipedia 公開百科摘錄";
    private static final String CROSSREF = "Crossref 論文書目";
    private final Providers.Transport transport;

    public record Batch(String query, List<WebHit> hits, List<String> warnings, String provider) { }
    private record Outcome(List<WebHit> hits, String warning, boolean succeeded) { }

    public PublicSearch(Config config) { this(config, new JdkTransport()); }
    /** Reuses the existing provider transport contract so fixture tests never require network access. */
    public PublicSearch(Config config, Providers.Transport transport) {
        Objects.requireNonNull(config);
        this.transport = Objects.requireNonNull(transport);
    }

    public Batch search(String query, int limit) throws Exception {
        String original = Objects.requireNonNullElse(query, "").strip().replaceAll("\\s+", " ");
        if (original.isEmpty() || original.length() > 500) throw new IllegalArgumentException("搜尋詞需為 1 至 500 字元。");
        int count = Math.max(1, Math.min(10, limit));
        // Both independent services share one deadline; a slow source must not double waiting time.
        var executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "public-search"); thread.setDaemon(true); return thread;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS + 1);
        Future<List<WebHit>> wiki = executor.submit(() -> wikipedia(original, count));
        Future<List<WebHit>> papers = executor.submit(() -> crossref(original, count));
        try {
            Outcome first = await(wiki, WIKI, deadline), second = await(papers, CROSSREF, deadline);
            List<String> warnings = new ArrayList<>();
            if (!first.warning().isEmpty()) warnings.add(first.warning());
            if (!second.warning().isEmpty()) warnings.add(second.warning());
            if (!first.succeeded() && !second.succeeded()) throw new IOException(String.join(" ", warnings));
            LinkedHashMap<String,WebHit> merged = new LinkedHashMap<>();
            // Interleave rather than let one index consume the entire result window.
            for (int i = 0; merged.size() < count && (i < first.hits().size() || i < second.hits().size()); i++) {
                if (i < first.hits().size()) merged.putIfAbsent(first.hits().get(i).url(), first.hits().get(i));
                if (merged.size() < count && i < second.hits().size())
                    merged.putIfAbsent(second.hits().get(i).url(), second.hits().get(i));
            }
            String provider = first.succeeded() && second.succeeded() ? "Wikipedia + Crossref 公開索引"
                    : first.succeeded() ? WIKI : CROSSREF;
            return new Batch(original, List.copyOf(merged.values()), List.copyOf(warnings), provider);
        } finally { wiki.cancel(true); papers.cancel(true); executor.shutdownNow(); }
    }

    private static Outcome await(Future<List<WebHit>> future, String provider, long deadline) throws InterruptedException {
        try {
            long remaining = deadline - System.nanoTime();
            List<WebHit> hits = future.isDone() ? future.get() : future.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            return new Outcome(hits, "", true);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Outcome(List.of(), provider + " 讀取逾時；本次未取得此來源結果。", false);
        } catch (ExecutionException e) {
            String message = e.getCause() instanceof IOException ? e.getCause().getMessage()
                    : provider + " 回應格式不符；本次未取得此來源結果。";
            return new Outcome(List.of(), message, false);
        }
    }

    private List<WebHit> wikipedia(String query, int count) throws Exception {
        String language = containsHan(query) ? "zh" : "en";
        URI uri = URI.create("https://" + language + ".wikipedia.org/w/api.php?action=query&generator=search&gsrsearch="
                + encode(wikiQuery(query)) + "&gsrlimit=" + count
                + "&prop=info%7Cextracts&inprop=url&exintro=1&explaintext=1&exsentences=3&format=json&formatversion=2");
        Map<String,Object> response = exchange(uri, WIKI);
        if (response.containsKey("error") || response.containsKey("errors")) throw invalid(WIKI);
        // MediaWiki omits query when a valid search has no matching pages.
        if (!response.containsKey("query") && response.containsKey("batchcomplete")) return List.of();
        List<Object> rows = Json.array(Json.object(response.get("query")).get("pages"));
        List<Map<String,Object>> pages = new ArrayList<>();
        for (Object row : rows) pages.add(Json.object(row));
        pages.sort(Comparator.comparingDouble(page -> Json.number(page.get("index"), Double.MAX_VALUE)));
        List<WebHit> hits = new ArrayList<>();
        for (Map<String,Object> page : pages) {
            String url = Json.string(page.get("fullurl"), ""), title = Json.string(page.get("title"), "").strip();
            if (!safeUrl(url) || title.isEmpty()) continue;
            String extract = bound(Json.string(page.get("extract"), "").strip(), 1000);
            String snippet = WIKI + "：" + (extract.isEmpty() ? "此條目未提供摘錄，請開啟來源閱讀。" : extract);
            hits.add(new WebHit(url, bound(title, 300), snippet, WIKI));
        }
        return List.copyOf(hits);
    }

    private List<WebHit> crossref(String query, int count) throws Exception {
        URI uri = URI.create("https://api.crossref.org/works?query.bibliographic=" + encode(paperQuery(query))
                + "&rows=" + count + "&select=title,URL,DOI,publisher,published,type");
        Map<String,Object> response = exchange(uri, CROSSREF);
        if (!"ok".equals(response.get("status"))) throw invalid(CROSSREF);
        List<Object> rows = Json.array(Json.object(response.get("message")).get("items"));
        List<WebHit> hits = new ArrayList<>();
        for (Object raw : rows) {
            Map<String,Object> row = Json.object(raw);
            List<Object> titles = Json.array(row.get("title"));
            if (titles.isEmpty() || !(titles.getFirst() instanceof String rawTitle) || rawTitle.isBlank()) continue;
            String title = plain(rawTitle, 300), url = Json.string(row.get("URL"), "");
            String doi = Json.string(row.get("DOI"), "");
            if (!safeUrl(url) && doi.matches("10\\.[0-9]{4,9}/[^\\s?#]+")) url = "https://doi.org/" + doi;
            if (!safeUrl(url) || title.isBlank()) continue;
            String publisher = plain(Json.string(row.get("publisher"), ""), 180);
            String year = year(row.get("published"));
            // Bibliographic metadata only: do not reproduce publisher abstracts or imply a full-text fetch.
            String snippet = CROSSREF + "：" + title + (publisher.isEmpty() ? "" : "。出版來源：" + publisher)
                    + (year.isEmpty() ? "" : "。出版年份：" + year)
                    + "。此結果僅提供書目資料，請開啟原文；部分文章可能需要訂閱。";
            hits.add(new WebHit(url, title, snippet, CROSSREF));
        }
        return List.copyOf(hits);
    }

    private Map<String,Object> exchange(URI uri, String provider) throws Exception {
        Providers.Request request = new Providers.Request(uri, "GET", Map.of("Accept", "application/json",
                "User-Agent", "AIKnowledgeSearch-Group16/1.0 (student project; public API metadata search)"),
                "", TIMEOUT_SECONDS, MAX_BYTES);
        Providers.Reply reply;
        try { reply = transport.send(request); }
        catch (InterruptedException e) { throw e; }
        catch (Exception e) { throw new IOException(provider + " 連線失敗；請稍後重試。"); }
        if (reply == null || reply.body() == null) throw invalid(provider);
        if (reply.status() == 429) throw new IOException(provider + " 已達使用限制；請稍後重試。");
        if (reply.status() < 200 || reply.status() >= 300)
            throw new IOException(provider + " 未成功回應（HTTP " + reply.status() + "）；請稍後重試。");
        if (reply.body().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid(provider);
        try { return Json.object(Json.parse(reply.body())); }
        catch (IllegalArgumentException e) { throw invalid(provider); }
    }

    static String wikiQuery(String query) {
        if (containsHan(query)) {
            String normalized = query.replaceAll("(?i)\\bAI\\b", "人工智能").replace("人工智慧", "人工智能");
            return normalized.contains("人工智能") ? normalized : normalized + " 人工智能";
        }
        String normalized = query.replaceAll("(?i)\\bAI\\b", "artificial intelligence");
        return normalized.toLowerCase(java.util.Locale.ROOT).contains("artificial intelligence")
                ? normalized : normalized + " artificial intelligence";
    }

    static String paperQuery(String query) {
        String result = query;
        String[][] translations = {{"地緣政治", "geopolitics"}, {"經濟學", "economics"}, {"經濟", "economics"},
                {"哲學", "philosophy"}, {"意識", "consciousness"}, {"就業", "employment"}, {"勞動", "labor"},
                {"出口管制", "export controls"}, {"生產力", "productivity"}, {"倫理", "ethics"},
                {"人工智慧", "artificial intelligence"}, {"人工智能", "artificial intelligence"}};
        for (String[] pair : translations) result = result.replace(pair[0], " " + pair[1] + " ");
        result = result.replaceAll("(?i)\\bRSI\\b", "recursive self-improvement")
                .replaceAll("(?i)\\bAI\\b", "artificial intelligence").replaceAll("\\s+", " ").strip();
        return result.toLowerCase(java.util.Locale.ROOT).contains("artificial intelligence")
                ? result : result + " artificial intelligence";
    }

    private static String year(Object raw) {
        if (!(raw instanceof Map<?,?> map) || !(map.get("date-parts") instanceof List<?> parts) || parts.isEmpty()
                || !(parts.getFirst() instanceof List<?> date) || date.isEmpty() || !(date.getFirst() instanceof Number number)) return "";
        int year = number.intValue(); return year >= 1000 && year <= 3000 ? Integer.toString(year) : "";
    }
    private static String plain(String value, int max) { return bound(HtmlParser.parse("https://api.crossref.org/", value).text(), max); }
    private static String bound(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
    private static boolean containsHan(String value) { return value.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static IOException invalid(String provider) { return new IOException(provider + " 回應格式不符；本次未取得此來源結果。"); }
    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 2048 && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static final class JdkTransport implements Providers.Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Providers.Reply send(Providers.Request request) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET();
            request.headers().forEach(builder::header);
            var response = BoundedHttp.send(client, builder.build(), MAX_BYTES);
            return new Providers.Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
    }
}
