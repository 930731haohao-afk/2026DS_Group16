package edu.nccu.ai;

import edu.nccu.ai.Models.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;

/** Behavior tests with authored, offline data. Run through the root TestSuite. */
public final class CoreTests {
    private static int checks;
    private CoreTests() { }
    public static int run() throws Exception {
        checks = 0;
        keywordTests(); htmlTests(); crawlTests(); expansionTests(); rankingTests(); corpusTests();
        return checks;
    }
    public static void main(String[] args) throws Exception { System.out.println("CoreTests: " + run() + " checks passed"); }

    private static void keywordTests() {
        equal(3, KeywordCounter.count("RAG paragraph rag RAG-based drag ragged", "rag"), "ASCII boundaries and case");
        equal(0, KeywordCounter.count("RAG1 _rag rag_ ragged", "rag"), "identifier boundaries");
        equal(2, KeywordCounter.count("人人人", "人人"), "Chinese overlapping occurrences");
        equal(0, KeywordCounter.count("abc", ""), "empty pattern");
        equal(0, KeywordCounter.count("ab", "abc"), "pattern longer than text");
        check(KeywordCounter.keywords("什麼是RAG").contains("檢索增強生成"), "bilingual RAG aliases");
        check(KeywordCounter.keywords("檢索增強生成怎麼實作").contains("rag"), "Chinese query segmentation");
        check(!KeywordCounter.keywords("paragraph").contains("rag"), "no synonym activation inside an English word");
        check(KeywordCounter.keywords("如何").isEmpty(), "stop-only query");
        check(KeywordCounter.keywords("LLM").equals(KeywordCounter.keywords("LLM")), "stable term order");
        equal(List.of("RAG", "tool calling", "中文"), KeywordCounter.literalKeywords("RAG, tool calling；中文,RAG"), "literal phrase list does not expand aliases");
        check(KeywordCounter.keywords("AI").contains("artificial intelligence"), "AI alone is a searchable topic");
        List<String> rsi = KeywordCounter.keywords("RSI 是什麼");
        check(rsi.contains("recursive self-improvement") && rsi.contains("遞迴自我改進"), "RSI resolves to AI self-improvement");
        check(rsi.stream().noneMatch(k -> k.contains("strength") || k.contains("強弱")), "RSI does not inject finance meaning");
        List<String> social = KeywordCounter.keywords("人工智慧地緣政治與經濟");
        check(social.containsAll(List.of("人工智慧地緣政治與經濟", "地緣政治", "經濟", "geopolitics", "economics")),
            "Chinese compound preserves political and economic topics alongside AI");
        check(KeywordCounter.keywords("人工智慧新興概念甲乙").contains("新興概念甲乙"), "unknown Chinese remainder survives known alias");
        check(KeywordCounter.keywords("AI unknownFutureConcept").contains("unknownfutureconcept"), "unknown English concept needs no whitelist");
        equal("AI 未收錄的新議題", KeywordCounter.webQuery(" AI   未收錄的新議題 "), "web query retains full unknown topic");
        check(KeywordCounter.webQuery("RSI").contains("recursive self-improvement")
            && KeywordCounter.webQuery("RSI").contains("AI"), "web RSI query adds AI disambiguation");
        equal("人工智慧地緣政治與經濟", KeywordCounter.webQuery("人工智慧地緣政治與經濟"), "web Chinese compound remains intact");
        equal("哲學與意識 人工智慧 AI", KeywordCounter.webQuery("哲學與意識"), "nontechnical topic receives AI context");
        check(!KeywordCounter.webQuery("adversity").contains("self-improvement"), "RSI substring is not expanded");
        equal("", KeywordCounter.webQuery(null), "null web query is empty");
        Random random = new Random(7316);
        String alphabet = "abAB 中人人_-";
        for (int test = 0; test < 200; test++) {
            StringBuilder text = new StringBuilder(), word = new StringBuilder();
            for (int i = 0, n = random.nextInt(100); i < n; i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            for (int i = 0, n = 1 + random.nextInt(6); i < n; i++) word.append(alphabet.charAt(random.nextInt(alphabet.length())));
            equal(naiveCount(text.toString(), word.toString()), KeywordCounter.count(text.toString(), word.toString()), "Boyer-Moore versus reference " + test);
        }
    }

    private static void htmlTests() {
        Page page = HtmlParser.parse("https://example.test/docs/index", "<html><head><title>RAG &amp; AI</title><style>styleSecret{}</style></head><body>"
            + "<nav>navSecret<a href='/private'>Ignore</a></nav><h1>RAG 入門</h1><script>scriptSecret()</script>"
            + "<p>中文 &amp; English&nbsp;text</p><a href='child#section'>Child</a><a href='javascript:alert(1)'>Bad</a>"
            + "<a href='https://elsewhere.test/a'>External</a></body></html>");
        equal("RAG & AI", page.title(), "title and entity decode");
        check(page.text().contains("中文 & English text"), "Unicode content and entity decode");
        check(!page.text().contains("scriptSecret") && !page.text().contains("styleSecret"), "script and style excluded");
        check(!page.text().contains("navSecret"), "HTML5 navigation excluded");
        check(page.links().contains("https://example.test/docs/child"), "relative links resolved and fragments stripped");
        check(page.links().stream().noneMatch(u -> u.contains("javascript") || u.endsWith("/private")), "unsafe and navigation links removed");
        check(!page.synthetic(), "parsed real page is not synthetic");
        equal("https://example.test/a", SearchEngine.canonical("HTTPS://EXAMPLE.TEST:443/x/../a#part"), "canonical URL");
        check(SearchEngine.canonical("https://user:pass@example.test/a") == null, "userinfo rejected");
        check(SearchEngine.canonical("file:///secret") == null, "non HTTP URL rejected");
    }

    private static void crawlTests() {
        String root = "https://example.test/docs/index", a = "https://example.test/docs/a", b = "https://example.test/docs/b";
        Map<String,Page> pages = new HashMap<>();
        pages.put(root, page(root, "RAG", a, a + "#same", "https://other.test/docs/a", "https://example.test/outside", "https://example.test/docs/%2e%2e/outside", b));
        pages.put(a, page(a, "RAG", root, b)); pages.put(b, page(b, "RAG", a));
        LinkedHashMap<String,Integer> fetched = new LinkedHashMap<>();
        SearchEngine engine = new SearchEngine((query,limit) -> List.of(root), url -> {
            fetched.merge(url, 1, Integer::sum); return pages.get(url);
        });
        SearchReport report = engine.search(new SearchOptions("RAG", "concept", 2, 10, 5, false));
        equal(3, report.pagesFetched(), "BFS unique visits");
        equal(Set.of(root,a,b), fetched.keySet(), "same-host directory scope");
        check(fetched.values().stream().allMatch(n -> n == 1), "duplicate and cycle fetched once");
        equal(2, report.results().getFirst().tree().children().size(), "first-parent tree structure");
        equal(3, report.results().getFirst().counts().get("rag"), "website counts aggregated");
        double score = report.results().getFirst().tree().pageScore();
        near(Ranking.round(score * 1.5), report.results().getFirst().ruleScore(), "root plus half depth average");

        fetched.clear();
        SearchReport bounded = engine.search(new SearchOptions("RAG", "concept", 2, 2, 5, false));
        check(fetched.size() <= 2 && bounded.pagesFetched() <= 2, "global page limit");
        check(bounded.truncated() && !bounded.warnings().isEmpty(), "budget truncation visible");
        fetched.clear();
        SearchReport shallow = engine.search(new SearchOptions("RAG", "concept", 0, 10, 5, false));
        equal(1, shallow.pagesFetched(), "max depth zero"); check(shallow.truncated(), "depth limit coverage visible");

        AtomicInteger attempts = new AtomicInteger();
        SearchEngine failing = new SearchEngine((query,limit) -> List.of(root, a, b), url -> {
            attempts.incrementAndGet(); if (url.equals(root)) throw new IOException("secret must not leak"); return pages.get(url);
        });
        SearchReport failures = failing.search(new SearchOptions("RAG", "concept", 0, 2, 5, false));
        equal(2, attempts.get(), "failed attempt consumes global budget"); equal(1, failures.pagesFetched(), "only successes counted as fetched");
        equal(1, failures.results().size(), "failed root not a zero-score result");
        check(failures.warnings().stream().noneMatch(w -> w.contains("secret")), "raw exception messages never leaked");
        check(failures.truncated(), "pending root skipped when budget exhausted");

        AtomicInteger sharedCount = new AtomicInteger();
        SearchEngine shared = new SearchEngine((query,limit) -> List.of(root, a), url -> { sharedCount.incrementAndGet(); return pages.get(url); });
        SearchReport sharedReport = shared.search(new SearchOptions("RAG", "concept", 2, 20, 5, false));
        equal(3, sharedCount.get(), "global cache shared across candidate roots");
        equal(2, sharedReport.results().size(), "shared cached page still appears under valid roots");
        expectIllegal(() -> engine.search(new SearchOptions("RAG", "concept", 3, 10, 5, false)), "depth validation");
        expectIllegal(() -> engine.search(new SearchOptions("", "concept", 1, 10, 5, false)), "empty query validation");
        equal(0, engine.search(new SearchOptions("如何", "concept", 1, 10, 5, false)).results().size(), "empty analyzed query");
        expectIllegal(() -> engine.search(new SearchOptions("RAG", null, 1, 10, 5, false)), "null intent validation");
        SearchEngine interrupted = new SearchEngine((query,limit) -> { throw new InterruptedException(); }, pages::get);
        boolean cancelled = false;
        try { interrupted.search(new SearchOptions("RAG", "concept", 1, 10, 5, false)); }
        catch (CancellationException e) { cancelled = true; }
        finally { check(Thread.interrupted(), "interrupted flag preserved for caller"); }
        check(cancelled, "backend interruption cancels search");
        SearchEngine interruptedSource = new SearchEngine((query,limit) -> List.of(root), url -> { throw new InterruptedException(); });
        cancelled = false;
        try { interruptedSource.search(new SearchOptions("RAG", "concept", 1, 10, 5, false)); }
        catch (CancellationException e) { cancelled = true; }
        finally { check(Thread.interrupted(), "source interruption flag preserved"); }
        check(cancelled, "source interruption cancels search");
    }

    private static void expansionTests() {
        String root = "https://example.test/docs/index", second = "https://example.test/docs/second";
        List<String> queries = new ArrayList<>();
        Map<String,Page> pages = Map.of(root, page(root, "RAG embedding retrieval"), second, page(second, "RAG embedding additional material"));
        SearchEngine engine = new SearchEngine((query,limit) -> { queries.add(query); return queries.size() == 1 ? List.of(root) : List.of(root, second); }, pages::get);
        SearchReport report = engine.search(new SearchOptions("RAG", "concept", 0, 8, 5, true));
        equal(2, queries.size(), "only one additional expansion query");
        check(!report.expandedKeywords().isEmpty(), "evidence-derived expansion generated");
        check(report.expandedKeywords().stream().allMatch(word -> KeywordCounter.count(pages.get(root).text(), word) > 0), "every added term occurs in fetched text");
        equal(2, report.pagesFetched(), "expanded candidate fetched within same budget");
        check(!report.keywords().contains("embedding"), "original scoring terms preserved after expansion");

        queries.clear();
        SearchReport bounded = engine.search(new SearchOptions("RAG", "concept", 0, 1, 5, true));
        equal(1, queries.size(), "expansion skipped at global budget");
        check(bounded.expandedKeywords().isEmpty(), "unexecuted expansion not reported as applied");
    }

    private static void rankingTests() {
        String conceptUrl = "https://example.test/concept", buildUrl = "https://example.test/build";
        Page concept = new Page(conceptUrl, "RAG", "RAG 概念 原理 定義 入門 overview beginner", List.of(), "test", true);
        Page build = new Page(buildUrl, "RAG", "RAG 程式 步驟 安裝 實作 code pip python implementation", List.of(), "test", true);
        Map<String,Page> pages = Map.of(conceptUrl, concept, buildUrl, build);
        SearchEngine engine = new SearchEngine((query,limit) -> List.of(conceptUrl, buildUrl), pages::get);
        equal(conceptUrl, engine.search(new SearchOptions("RAG", "concept", 0, 10, 1, false)).results().getFirst().url(), "concept intent changes top result");
        equal(buildUrl, engine.search(new SearchOptions("RAG", "build", 0, 10, 1, false)).results().getFirst().url(), "build intent changes top result");
        SearchReport report = engine.search(new SearchOptions("RAG", "build", 0, 10, 5, false));
        check(report.results().get(0).finalScore() >= report.results().get(1).finalScore(), "top-k descending order");
        check(report.results().stream().allMatch(r -> r.jevScore() == null), "core never invents model scores");
        String snippet = Ranking.snippet("x".repeat(700) + " RAG " + "z".repeat(700), List.of("rag"));
        check(snippet.contains("RAG") && snippet.length() <= 362, "bounded relevant snippet");
        Page policy = page("https://example.test/policy", "人工智慧就業 政策建議 實證 案例 應用");
        check(Ranking.intentScore(policy, "build") >= 2, "application intent includes policy and empirical work");
        Page philosophy = page("https://example.test/philosophy", "AI 意識 哲學 爭議 論點 利弊");
        check(Ranking.intentScore(philosophy, "compare") >= 1.5, "comparison intent includes philosophical arguments");
    }

    private static void corpusTests() throws Exception {
        DemoCorpus corpus = DemoCorpus.load(Path.of("data/demo-corpus.json"));
        check(corpus.pages().size() >= 18 && corpus.pages().size() <= 24, "fixture size range");
        check(corpus.pages().stream().allMatch(p -> p.synthetic() && p.source().equals("教學示範資料（自編）")), "all demo content labeled synthetic");
        check(corpus.candidates("RAG", 5).size() == 5, "demo candidates capped");
        equal(0, corpus.candidates("quantumchromodynamics", 5).size(), "unknown topic does not invent demo results");
        equal(0, KeywordCounter.count(corpus.fetch("https://demo.local/other/hiking").text(), "rag"), "paragraph distractor not RAG");
        for (Page page : corpus.pages()) for (String link : page.links()) {
            check(corpus.fetch(link) != null, "fixture internal link resolves");
        }
        SearchReport report = new SearchEngine(corpus::candidates, corpus::fetch)
            .search(new SearchOptions("RAG 入門", "concept", 1, 20, 5, true));
        check(!report.results().isEmpty() && report.results().size() <= 5, "end-to-end demo search");
        check(report.pagesFetched() <= 20, "end-to-end budget maintained");
    }

    private static Page page(String url, String text, String... links) { return new Page(url, "t", text, List.of(links), "test", true); }
    private static boolean ascii(char c) { return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_'; }
    private static int naiveCount(String text, String pattern) {
        String source = text.toLowerCase(Locale.ROOT), target = pattern.toLowerCase(Locale.ROOT); int count = 0;
        for (int i = 0; i <= source.length() - target.length(); i++) {
            if (!source.startsWith(target, i)) continue;
            if (ascii(target.charAt(0)) && i > 0 && ascii(source.charAt(i-1))) continue;
            int end = i + target.length();
            if (ascii(target.charAt(target.length()-1)) && end < source.length() && ascii(source.charAt(end))) continue;
            count++;
        }
        return count;
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message + " expected=" + expected + " actual=" + actual); }
    private static void near(double expected, double actual, String message) { check(Math.abs(expected-actual) < 0.002, message + " expected=" + expected + " actual=" + actual); }
    private static void expectIllegal(Runnable action, String message) { boolean thrown = false; try { action.run(); } catch (IllegalArgumentException e) { thrown = true; } check(thrown, message); }
}
