package edu.nccu.ai;

import edu.nccu.ai.Models.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Bounded breadth-first crawling, inspectable rule scoring and one evidence-derived expansion. */
public final class SearchEngine {
    private static final int MAX_CANDIDATES = 10, MAX_LINKS_PER_PAGE = 30;
    private final SearchBackend backend;
    private final PageSource source;

    public SearchEngine(SearchBackend backend, PageSource source) {
        this.backend = Objects.requireNonNull(backend);
        this.source = Objects.requireNonNull(source);
    }

    public SearchReport search(SearchOptions options) {
        validate(options);
        State state = new State(options, KeywordCounter.keywords(options.query()));
        if (state.keywords.isEmpty()) {
            return new SearchReport(options.query(), options.intent(), List.of(), List.of(), List.of(),
                List.of("請加入明確主題，例如 RAG、LLM 或工具呼叫。"), 0, false);
        }
        int initialLimit = Math.min(MAX_CANDIDATES, Math.max(3, options.limit() + 1));
        addRoots(state, candidates(state, options.query(), initialLimit), initialLimit);
        crawl(state);
        List<String> expansion = List.of();
        if (options.expand()) {
            List<String> proposed = expansionTerms(state);
            if (!proposed.isEmpty() && state.attempts < options.maxPages() && state.roots.size() < MAX_CANDIDATES) {
                expansion = proposed;
                int remaining = MAX_CANDIDATES - state.roots.size();
                List<String> second = candidates(state, options.query() + " " + String.join(" ", expansion), remaining);
                addRoots(state, second, remaining);
                crawl(state);
                state.warn("已用已讀取內容中的相關詞擴展一次查詢；評分仍依原始查詢，避免改變使用者需求。");
            } else if (!proposed.isEmpty()) {
                state.warn("本次已達抓取或候選數上限，未執行額外的關鍵字擴展查詢。");
            }
        }
        Comparator<SearchResult> best = Comparator.comparingDouble(SearchResult::finalScore).reversed()
            .thenComparingInt(SearchResult::originalRank).thenComparing(SearchResult::url);
        PriorityQueue<SearchResult> top = new PriorityQueue<>(best.reversed());
        for (Root root : state.roots) {
            if (!root.node.status.equals("ok")) continue;
            SearchResult result = result(root, state);
            if (result.ruleScore() <= 0) continue;
            top.offer(result);
            if (top.size() > options.limit()) top.poll();
        }
        ArrayList<SearchResult> results = new ArrayList<>(top);
        results.sort(best);
        if (results.isEmpty() && !state.roots.isEmpty()) state.warn("已讀取的候選內容沒有符合查詢的關鍵字；請改用較明確的主題。");
        return new SearchReport(options.query(), options.intent(), state.keywords, expansion,
            List.copyOf(results), List.copyOf(state.warnings), state.cache.size(), state.truncated);
    }

    private List<String> candidates(State state, String query, int limit) {
        checkCancelled();
        try {
            List<String> urls = backend.candidates(query, limit);
            return urls == null ? List.of() : urls;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("搜尋已取消");
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            checkCancelled();
            state.warn("搜尋來源暫時無法取得候選網址（" + e.getClass().getSimpleName() + "）。");
            return List.of();
        }
    }

    private void addRoots(State state, List<String> candidates, int limit) {
        int added = 0, examined = 0;
        for (String candidate : candidates) {
            checkCancelled();
            if (++examined > 100 || added >= limit || state.roots.size() >= MAX_CANDIDATES) break;
            String url = canonical(candidate);
            if (url == null) { state.warn("已略過格式無效或非 HTTP(S) 的候選網址。"); continue; }
            if (!state.rootUrls.add(url)) continue;
            Root root = new Root(url, state.roots.size() + 1);
            state.roots.add(root);
            state.queue.add(new Visit(root, root.node));
            added++;
        }
    }

    private void crawl(State state) {
        while (!state.queue.isEmpty()) {
            checkCancelled();
            Visit visit = state.queue.removeFirst();
            Node node = visit.node;
            Page page = state.cache.get(node.url);
            if (page == null && state.failed.contains(node.url)) {
                node.status = "error"; node.warning = "此網址在本次搜尋中讀取失敗，未重試。"; continue;
            }
            if (page == null) {
                if (state.attempts >= state.options.maxPages()) {
                    node.status = "skipped"; node.warning = "本次搜尋的總抓取次數已達上限。";
                    state.truncated = true;
                    state.warn("已達本次搜尋的 " + state.options.maxPages() + " 次抓取上限（含失敗嘗試），部分節點未讀取。");
                    continue;
                }
                state.attempts++;
                try {
                    page = Objects.requireNonNull(source.fetch(node.url), "page");
                    if (page.text() == null || page.title() == null || page.links() == null) throw new IllegalArgumentException("incomplete page");
                    state.cache.put(node.url, page);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new CancellationException("搜尋已取消");
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    checkCancelled();
                    state.failed.add(node.url); node.status = "error";
                    node.warning = "頁面讀取失敗（" + e.getClass().getSimpleName() + "），不列入分數。";
                    state.warn("有頁面讀取失敗；失敗節點不當成零分網頁，詳見網址樹。");
                    if (node.depth == 0) state.warn("無法讀取候選起點：" + node.url);
                    continue;
                }
            }
            node.page = page; node.title = page.title(); node.status = "ok";
            node.counts = KeywordCounter.counts(page.title() + " " + page.text(), state.keywords);
            node.score = Ranking.pageScore(node.counts, page.title().length() + page.text().length());
            LinkedHashSet<String> eligible = new LinkedHashSet<>();
            for (String rawLink : page.links()) {
                String link = canonical(rawLink);
                if (link != null && inScope(visit.root.url, link) && !visit.root.seen.contains(link)) eligible.add(link);
            }
            if (node.depth >= state.options.maxDepth()) {
                if (!eligible.isEmpty()) {
                    state.truncated = true; node.warning = "已達設定深度，尚有子頁面未走訪。";
                    state.warn("網址樹已按設定深度截斷；網站分數只代表本次取得的頁面。");
                }
                continue;
            }
            int linked = 0;
            for (String link : eligible) {
                if (linked >= MAX_LINKS_PER_PAGE || visit.root.seen.size() >= state.options.maxPages()) {
                    state.truncated = true; node.warning = "此頁面部分連結因數量限制未加入網址樹。";
                    state.warn("部分子頁面超過每頁連結或單棵樹的節點上限，未加入網址樹。");
                    break;
                }
                if (visit.root.seen.add(link)) {
                    Node child = new Node(link, node.depth + 1);
                    node.children.add(child); state.queue.addLast(new Visit(visit.root, child)); linked++;
                }
            }
        }
    }

    private SearchResult result(Root root, State state) {
        Map<Integer,List<Node>> levels = new TreeMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>(); queue.add(root.node);
        LinkedHashMap<String,Integer> totalCounts = new LinkedHashMap<>();
        state.keywords.forEach(k -> totalCounts.put(k, 0));
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.status.equals("ok")) {
                levels.computeIfAbsent(node.depth, ignored -> new ArrayList<>()).add(node);
                node.counts.forEach((word,count) -> totalCounts.merge(word, count, Integer::sum));
            }
            queue.addAll(node.children);
        }
        double ruleScore = 0, intentScore = 0;
        for (Map.Entry<Integer,List<Node>> level : levels.entrySet()) {
            double factor = Math.pow(0.5, level.getKey());
            ruleScore += factor * level.getValue().stream().mapToDouble(n -> n.score).average().orElse(0);
            // Intent adds value only to pages that match the topic; unrelated pages cannot gain rank by style alone.
            intentScore += factor * level.getValue().stream()
                .mapToDouble(n -> n.score > 0 ? Ranking.intentScore(n.page, state.options.intent()) : 0).average().orElse(0);
        }
        Page page = root.node.page;
        return new SearchResult(root.url, page.title(), Ranking.snippet(page.text(), state.keywords), page.source(),
            root.originalRank, Collections.unmodifiableMap(totalCounts), Ranking.round(ruleScore), Ranking.round(intentScore),
            Ranking.round(ruleScore + 0.75 * intentScore), null, freeze(root.node));
    }

    private TreeNode freeze(Node node) {
        return new TreeNode(node.url, node.title, node.depth, node.counts, node.score,
            node.children.stream().map(this::freeze).toList(), node.status, node.warning);
    }

    private List<String> expansionTerms(State state) {
        StringBuilder relevant = new StringBuilder();
        for (Page page : state.cache.values()) {
            if (KeywordCounter.counts(page.text(), state.keywords).values().stream().anyMatch(n -> n > 0))
                relevant.append(page.text()).append('\n');
        }
        Set<String> existing = new HashSet<>();
        state.keywords.forEach(k -> existing.add(k.toLowerCase(Locale.ROOT)));
        Map<String,Integer> counts = KeywordCounter.counts(relevant.toString(), KeywordCounter.expansionVocabulary());
        ArrayList<String> selected = new ArrayList<>();
        counts.entrySet().stream().filter(e -> e.getValue() > 0 && !existing.contains(e.getKey().toLowerCase(Locale.ROOT)))
            .sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> {
                if (selected.size() >= 3 || existing.contains(e.getKey().toLowerCase(Locale.ROOT))) return;
                selected.add(e.getKey());
                KeywordCounter.keywords(e.getKey()).forEach(k -> existing.add(k.toLowerCase(Locale.ROOT)));
            });
        return List.copyOf(selected);
    }

    /** Public for fixture lookup and transparent unit tests; strips fragments and normalizes dot segments. */
    public static String canonical(String value) {
        if (value == null || value.length() > 2000) return null;
        try {
            URI uri = URI.create(value.strip()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            int port = uri.getPort();
            if (port == 443 && scheme.equals("https") || port == 80 && scheme.equals("http")) port = -1;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException e) { return null; }
    }

    private static boolean inScope(String root, String child) {
        URI base = URI.create(root), target = URI.create(child);
        if (!base.getHost().equalsIgnoreCase(target.getHost()) || effectivePort(base) != effectivePort(target)) return false;
        String path = scopePath(base), childPath = scopePath(target);
        if (path == null || childPath == null) return false;
        String directory = path.endsWith("/") ? path : path.substring(0, path.lastIndexOf('/') + 1);
        return childPath.startsWith(directory);
    }

    private static String scopePath(URI uri) {
        // Re-normalize the decoded path so encoded dot-segments cannot escape the directory policy.
        String path = uri.getPath();
        if (path == null || path.indexOf('\\') >= 0) return null;
        try { return new URI(null, null, path, null).normalize().getPath(); }
        catch (java.net.URISyntaxException e) { return null; }
    }

    private static int effectivePort(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80; }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("搜尋已取消");
    }
    private static void validate(SearchOptions options) {
        if (options == null || options.query() == null || options.query().isBlank() || options.query().length() > 240)
            throw new IllegalArgumentException("查詢需為 1 至 240 個字元。");
        if (options.intent() == null || !Set.of("concept", "compare", "build").contains(options.intent())) throw new IllegalArgumentException("不支援的搜尋目的。");
        if (options.maxDepth() < 0 || options.maxDepth() > 2 || options.maxPages() < 1 || options.maxPages() > 40
            || options.limit() < 1 || options.limit() > 10) throw new IllegalArgumentException("搜尋深度、頁數或結果數超過限制。");
    }

    private static final class State {
        final SearchOptions options; final List<String> keywords;
        final List<Root> roots = new ArrayList<>(); final Set<String> rootUrls = new HashSet<>();
        final ArrayDeque<Visit> queue = new ArrayDeque<>();
        final LinkedHashMap<String,Page> cache = new LinkedHashMap<>(); final Set<String> failed = new HashSet<>();
        final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        int attempts; boolean truncated;
        State(SearchOptions options, List<String> keywords) { this.options = options; this.keywords = keywords; }
        void warn(String warning) { if (warnings.size() < 32) warnings.add(warning); }
    }
    private static final class Root {
        final String url; final int originalRank; final Node node; final Set<String> seen = new HashSet<>();
        Root(String url, int rank) { this.url = url; originalRank = rank; node = new Node(url, 0); seen.add(url); }
    }
    private static final class Node {
        final String url; final int depth; String title, status = "skipped", warning = "";
        Map<String,Integer> counts = Map.of(); double score; Page page; final List<Node> children = new ArrayList<>();
        Node(String url, int depth) { this.url = url; this.depth = depth; title = url; }
    }
    private record Visit(Root root, Node node) { }
}
