package edu.nccu.ai;

import edu.nccu.ai.Models.SearchResult;
import edu.nccu.ai.Models.TreeNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** All responses are authored fixtures. No network, real credentials, or charges. */
public final class ProvidersTest {
    private static int checks;
    private static final Map<String,String> ENV = Map.of("BRAVE_API_KEY", "fixture-brave-secret",
            "TYPESAFE_API_KEY", "fixture-jev-secret", "OPENAI_API_KEY", "fixture-openai-secret",
            "OPENAI_MODEL", "fixture-model");
    private ProvidersTest() { }
    public static void main(String[] args) throws Exception {
        System.out.println("ProvidersTest checks: " + run());
    }
    public static int run() throws Exception {
        checks = 0;
        availability();
        searchSchema();
        reranking();
        answerSchema();
        safeFailures();
        return checks;
    }
    private static Providers providers(Providers.Transport transport) {
        return new Providers(new Config(ENV), transport);
    }
    private static void availability() throws Exception {
        Providers none = new Providers(new Config(Map.of()), request -> { throw new AssertionError("Unexpected network"); });
        check(!none.searchAvailable() && !none.jevAvailable() && !none.answerAvailable(), "Empty configuration disables providers");
        expectFailure(() -> none.search("RAG", 5));
        expectFailure(() -> none.rerank("RAG", "concept", List.of()));
        expectFailure(() -> none.answer("RAG", List.of()));
        Providers noModel = new Providers(new Config(Map.of("OPENAI_API_KEY", "fixture-key")), request -> null);
        check(!noModel.answerAvailable(), "OpenAI requires an explicit model, not just a key");
        check(providers(request -> null).searchAvailable() && providers(request -> null).jevAvailable()
                && providers(request -> null).answerAvailable(), "Complete configuration enables capabilities");
    }
    private static void searchSchema() throws Exception {
        Providers service = providers(request -> {
            check(request.method().equals("GET"), "Brave uses GET");
            check(request.uri().getHost().equals("api.search.brave.com")
                    && request.uri().getPath().equals("/res/v1/web/search"), "Brave fixed official endpoint");
            check(request.uri().getRawQuery().contains("q=RAG+%26+AI")
                    && request.uri().getRawQuery().contains("count=20"), "Query escaped and count bounded");
            check(request.headers().get("X-Subscription-Token").equals("fixture-brave-secret"), "Brave auth header");
            check(!request.toString().contains("fixture-brave-secret"), "Request toString redacted");
            check(request.timeoutSeconds() <= 30 && request.maxResponseBytes() <= 1_048_576, "Request resource limits");
            return reply(Map.of("web", Map.of("results", List.of(
                    Map.of("url", "https://example.org/rag"), Map.of("url", "javascript:alert(1)"),
                    Map.of("url", "https://example.org/rag"), Map.of("url", "https://user:pw@example.org/private"),
                    Map.of("url", "https://example.org/agent")))));
        });
        check(service.search("RAG & AI", 99).equals(List.of("https://example.org/rag", "https://example.org/agent")),
                "Deduplicate and reject unsafe URL schemes/userinfo");
        check(providers(request -> reply(Map.of("query", Map.of("original", "RAG")))).search("RAG", 5).isEmpty(),
                "Missing web section means no web results");
        expectFailure(() -> providers(request -> reply(Map.of("web", Map.of("results", "bad")))).search("RAG", 5));
    }
    private static void reranking() throws Exception {
        List<SearchResult> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) rows.add(row(i, 11 - i));
        AtomicInteger calls = new AtomicInteger();
        Providers service = providers(request -> {
            int call = calls.incrementAndGet();
            check(request.uri().toString().equals("https://api.typesafe.ai/v1/systemone"), "Jev fixed endpoint");
            check(request.headers().get("Authorization").equals("Bearer fixture-jev-secret"), "Jev bearer header");
            Map<String,Object> body = Json.object(Json.parse(request.body()));
            check("jev-latest".equals(body.get("model")), "Jev model setting");
            Map<String,Object> question = Json.object(Json.object(body.get("questions")).get("relevance"));
            check("score".equals(question.get("type")) && Json.array(question.get("criteria")).size() == 5,
                    "Score question has five ordered rubric levels");
            Map<String,Object> state = Json.object(body.get("state"));
            check("RAG".equals(state.get("query")) && state.containsKey("untrusted_document"), "Jev receives query and isolated source");
            return jevReply(call == 2 ? 4 : 0, 1);
        });
        List<SearchResult> ranked = service.rerank("RAG", "concept", rows);
        check(calls.get() == 5, "At most five Jev requests");
        check(ranked.size() == 7, "Jev does not drop unscored candidates");
        check(ranked.get(0).url().equals(rows.get(1).url()), "Relevance can rerank top candidate window");
        check(ranked.get(5).equals(rows.get(5)) && ranked.get(6).equals(rows.get(6)), "Unscored tail unchanged");
        List<SearchResult> uncertain = providers(request -> jevReply(0, .2)).rerank("RAG", "concept", List.of(rows.get(0)));
        check(uncertain.get(0).finalScore() == rows.get(0).finalScore() && uncertain.get(0).jevScore() == null,
                "Low confidence abstains and preserves rule score");
        expectFailure(() -> providers(request -> jevReply(6, .9)).rerank("RAG", "concept", List.of(rows.get(0))));
        expectFailure(() -> providers(request -> jevReply(2, 1.2)).rerank("RAG", "concept", List.of(rows.get(0))));
        expectFailure(() -> providers(request -> reply(Map.of("answers", Map.of("relevance",
                Map.of("type", "score", "score", 2))))).rerank("RAG", "concept", List.of(rows.get(0))));
        expectFailure(() -> providers(request -> reply(Map.of("answers", Map.of("relevance",
                Map.of("type", "score", "score", 2, "confidence", .8, "probabilities", Map.of("0", 1))))))
                .rerank("RAG", "concept", List.of(rows.get(0))));
    }
    private static void answerSchema() throws Exception {
        Providers service = providers(request -> {
            check(request.uri().toString().equals("https://api.openai.com/v1/responses"), "OpenAI fixed endpoint");
            Map<String,Object> body = Json.object(Json.parse(request.body()));
            check(Boolean.FALSE.equals(body.get("store")) && "fixture-model".equals(body.get("model")), "Explicit model and no response storage");
            check(Json.array(body.get("tools")).isEmpty(), "No tools in evidence summarization");
            check(((Number)body.get("max_output_tokens")).intValue() == 1200, "Output tokens bounded");
            Map<String,Object> input = Json.object(Json.parse((String)body.get("input")));
            List<Object> evidence = Json.array(input.get("evidence"));
            check(evidence.size() == 2 && "S1".equals(Json.object(evidence.get(0)).get("id")), "Evidence citations locally assigned");
            check(Json.object(evidence.get(0)).keySet().equals(java.util.Set.of("id", "title", "excerpt")), "Only bounded snippet data sent");
            check(((String)body.get("instructions")).contains("忽略"), "Source prompt-injection instruction included");
            return response("RAG 先檢索資料。[S1] 比較來源。[S2]", "completed");
        });
        Map<String,Object> answer = service.answer("RAG", List.of(row(1, 10), row(2, 9)));
        check("generated".equals(answer.get("mode")) && Json.array(answer.get("citations")).size() == 2, "Generated answer and cited sources returned");
        check("https://example.org/1".equals(Json.object(Json.array(answer.get("citations")).get(0)).get("url")), "Citation URLs come from inputs");
        expectFailure(() -> providers(request -> response("假的來源 [S99]", "completed")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("非法引用 [S1 extra]", "completed")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("混入損壞引用 [S1] [S99", "completed")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("混入不支援格式 [S1] [s2]", "completed")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("未附引用", "completed")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("內容 [S1]", "incomplete")).answer("RAG", List.of(row(1, 10))));
        expectFailure(() -> providers(request -> response("點擊 https://other.example/ [S1]", "completed")).answer("RAG", List.of(row(1, 10))));
        Map<String,Object> none = providers(request -> { throw new AssertionError("No request without evidence"); }).answer("RAG", List.of());
        check("none".equals(none.get("mode")), "Empty evidence requires no paid request");
    }
    private static void safeFailures() throws Exception {
        for (int status : List.of(302, 401, 403, 429, 500)) {
            Providers service = providers(request -> new Providers.Reply(status, "fixture-secret raw provider message"));
            String message = expectFailure(() -> service.search("RAG", 5));
            check(!message.contains("fixture-secret") && !message.contains("raw provider"), "Provider error body never exposed");
        }
        String message = expectFailure(() -> providers(request -> { throw new IOException("fixture-brave-secret"); }).search("RAG", 5));
        check(!message.contains("fixture-brave-secret"), "Transport exception sanitized");
        expectFailure(() -> providers(request -> new Providers.Reply(200, "not json")).search("RAG", 5));
        expectFailure(() -> providers(request -> new Providers.Reply(200, "x".repeat(1_048_577))).search("RAG", 5));
        check(!new Providers.Reply(500, "fixture-secret").toString().contains("fixture-secret"), "Reply toString redacted");
        try {
            providers(request -> { throw new InterruptedException(); }).search("RAG", 5);
            throw new AssertionError("Interrupted provider request must abort");
        } catch (InterruptedException expected) { checks++; }
    }
    private static SearchResult row(int index, double score) {
        String url = "https://example.org/" + index;
        TreeNode tree = new TreeNode(url, "AI source " + index, 0, Map.of("rag", 1), score, List.of(), "ok", "");
        return new SearchResult(url, "AI source " + index, "RAG retrieves evidence before generation.",
                "authored fixture", index, Map.of("rag", 1), score, 0, score, null, tree);
    }
    private static Providers.Reply jevReply(double score, double confidence) {
        return reply(Map.of("model", "fixture-jev", "answers", Map.of("relevance",
                Map.of("type", "score", "score", score, "confidence", confidence,
                        "probabilities", Map.of("0", .2, "1", .2, "2", .2, "3", .2, "4", .2))),
                "usage", Map.of("input_tokens", 1, "output_tokens", 1)));
    }
    private static Providers.Reply response(String text, String status) {
        return reply(Map.of("status", status, "output", List.of(Map.of("type", "message", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
    private static Providers.Reply reply(Object body) { return new Providers.Reply(200, Json.stringify(body)); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static String expectFailure(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected safe ProviderException"); }
        catch (Providers.ProviderException e) {
            checks++;
            check(e.getCause() == null, "Provider failure carries no sensitive cause");
            return e.getMessage();
        }
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
