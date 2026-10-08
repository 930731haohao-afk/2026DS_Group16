package edu.nccu.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Regression tests for offline collection and human-judgment scoring. No provider or test-split execution. */
public final class EvaluationRunnerTests {
    private static int checks;
    private EvaluationRunnerTests() { }

    public static int run() throws Exception {
        checks = 0;
        Files.createDirectories(Path.of("work"));
        Path scratch = Files.createTempDirectory(Path.of("work"), "test-evaluation-");
        metricsAndCsv(scratch);
        pendingAndComparability(scratch);
        invalidInput(scratch);
        preparation(scratch);
        return checks;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("EvaluationRunnerTests: " + run() + " checks passed");
    }

    private static void metricsAndCsv(Path scratch) throws Exception {
        String csv = "\ufeffqueryId,system,rank,relevant,seconds,notes\r\n"
            + "\"Q,01\",\"sample\"\"system\",1,TRUE,20,\"中文, comma and \"\"quote\"\"\r\nembedded newline\"\r\n"
            + "\"Q,01\",\"sample\"\"system\",2,false,,\r\n"
            + "\"Q,01\",\"sample\"\"system\",3,true,,\r\n"
            + "\"Q,01\",\"sample\"\"system\",4,false,,\r\n"
            + "\"Q,01\",\"sample\"\"system\",5,false,,\r\n"
            + "Q02,\"sample\"\"system\",1,false,40,\r\n"
            + "Q02,\"sample\"\"system\",2,true,,\r\n"
            + "Q02,\"sample\"\"system\",3,false,,\r\n"
            + "Q02,\"sample\"\"system\",4,false,,\r\n"
            + "Q02,\"sample\"\"system\",5,false,,";
        Map<String,Object> output = EvaluationRunner.score(write(scratch, "quoted.csv", csv));
        List<Object> systems = Json.array(output.get("systems"));
        equal(1, systems.size(), "quoted fields form one system");
        Map<String,Object> metric = Json.object(systems.getFirst());
        equal("sample\"system", metric.get("system"), "escaped quote preserved in identifier");
        near(0.3, metric.get("precisionAt5"), "known mean Precision@5");
        near(0.75, metric.get("mrrAt5"), "known mean reciprocal rank");
        near(30, metric.get("meanSeconds"), "human time counted once per question");
        equal(2, metric.get("queries"), "quoted comma retained within question ID");
        equal(10, metric.get("judgedRows"), "embedded newline does not create an extra record");
        equal(0, output.get("pendingRows"), "all rows judged");
        check(Json.bool(output.get("sameQuerySet"), false), "single system has consistent question set");
        check(Json.string(output.get("inputSha256"), "").matches("[0-9a-f]{64}"), "input provenance hash recorded");
        List<Object> repeated = Json.array(EvaluationRunner.score(scratch.resolve("quoted.csv")).get("systems"));
        equal(systems, repeated, "scoring same input is deterministic");
    }

    private static void pendingAndComparability(Path scratch) throws Exception {
        Map<String,Object> empty = EvaluationRunner.score(write(scratch, "blank-judgments.csv",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,,\nQ01,s,2,,\n"));
        equal(2, empty.get("pendingRows"), "blank labels remain pending");
        equal(0, Json.array(empty.get("systems")).size(), "blank labels never become fabricated negatives");
        check(Json.string(empty.get("comparisonStatus"), "").contains("沒有人工評分"), "empty scoring status explicit");

        Map<String,Object> partial = EvaluationRunner.score(write(scratch, "partial.csv",
            "queryId,system,rank,relevant\nQ01,s,1,true\nQ01,s,2,\n"));
        equal(1, partial.get("pendingRows"), "partially judged table retains pending count");
        Map<String,Object> partialMetric = Json.object(Json.array(partial.get("systems")).getFirst());
        near(0.2, partialMetric.get("precisionAt5"), "denominator stays five for partial results");
        check(!Json.array(partial.get("warnings")).isEmpty(), "partial ranks produce coverage warning");
        check(Json.string(partial.get("comparisonStatus"), "").contains("尚有未判讀"), "provisional metric status explicit");

        Map<String,Object> different = EvaluationRunner.score(write(scratch, "different-queries.csv",
            "queryId,system,rank,relevant\nQ01,A,1,true\nQ02,B,1,true\n"));
        check(!Json.bool(different.get("sameQuerySet"), true), "different question sets detected");
        check(Json.string(different.get("comparisonStatus"), "").contains("不能直接比較"), "incomparable averages not endorsed");
        Map<String,Object> sets = Json.object(different.get("queryIdsBySystem"));
        check(sets.containsKey("A") && sets.containsKey("B"), "question membership available per system");
    }

    private static void invalidInput(Path scratch) throws Exception {
        List<String> invalid = List.of(
            "queryId,system,rank,relevant\nQ01,s,1,maybe\n",
            "queryId,system,rank,relevant\nQ01,s,1,true\nQ01,s,1,false\n",
            "queryId,system,rank,relevant\nQ01,s,1,\nQ01,s,1,\n",
            "queryId,system,rank,relevant\nQ01,s,0,true\n",
            "queryId,system,rank,relevant\nQ01,s,6,true\n",
            "queryId,system,rank,relevant\nQ01,s,1.0,true\n",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,true,-1\n",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,true,NaN\n",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,true,Infinity\n",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,true,abc\n",
            "queryId,system,rank,relevant,seconds\nQ01,s,1,true,20\nQ01,s,2,false,30\n",
            "queryId,system,rank,relevant\nQ01,s,1,\"true\n",
            "queryId,system,rank,relevant\nQ01,s,1,\"true\"x\n",
            "queryId,system,rank,relevant\nQ01,s,1\n",
            "queryId,system,rank\nQ01,s,1\n",
            "queryId,system,rank,relevant,relevant\nQ01,s,1,true,true\n",
            "queryId,system,rank,relevant\n,s,1,true\n"
        );
        for (int i = 0; i < invalid.size(); i++) {
            Path path = write(scratch, "invalid-" + i + ".csv", invalid.get(i));
            expectIllegal(() -> EvaluationRunner.score(path), "invalid judgment case " + i);
        }
        Path oversized = write(scratch, "oversized.csv", "x".repeat(4_000_001));
        expectIllegal(() -> EvaluationRunner.score(oversized), "CSV size bound");
    }

    private static void preparation(Path scratch) throws Exception {
        Map<String,Object> source = Json.object(Json.parse(Files.readString(Path.of("data/evaluation.json"), StandardCharsets.UTF_8)));
        List<Map<String,Object>> questions = Json.array(source.get("questions")).stream().map(Json::object).toList();
        equal(20L, questions.stream().filter(q -> "dev".equals(q.get("split"))).count(), "source has 20 development questions");
        equal(30L, questions.stream().filter(q -> "test".equals(q.get("split"))).count(), "source has 30 holdout questions");

        Path output = scratch.resolve("dev-output");
        EvaluationRunner.prepare(output, "dev");
        Map<String,Object> results = Json.object(Json.parse(Files.readString(output.resolve("results.json"), StandardCharsets.UTF_8)));
        Map<String,Object> metadata = Json.object(results.get("metadata"));
        equal("dev", metadata.get("split"), "prepared split is development only");
        equal(20L, metadata.get("questionCount"), "all dev questions prepared");
        equal(60, Json.array(results.get("runs")).size(), "three offline systems per development question");
        check(Json.array(results.get("questions")).stream().allMatch(q -> "dev".equals(Json.object(q).get("split"))), "no test question executed");
        check(Json.object(metadata.get("providers")).values().stream().allMatch("not run"::equals), "providers explicitly not run");
        check(Json.bool(metadata.get("synthetic"), false), "prepared corpus labeled synthetic");
        Map<String,Object> preparedScore = EvaluationRunner.score(output.resolve("judgments.csv"));
        equal(300, preparedScore.get("pendingRows"), "20 by 3 by 5 human labels all blank");
        equal(0, Json.array(preparedScore.get("systems")).size(), "preparation creates no invented metrics");
        String prompts = Files.readString(output.resolve("prompts.txt"), StandardCharsets.UTF_8);
        check(prompts.contains("一般提問：") && prompts.contains("策略提問："), "both prompt variants included");

        byte[] before = Files.readAllBytes(output.resolve("judgments.csv"));
        boolean blocked = false;
        try { EvaluationRunner.prepare(output, "dev"); } catch (IOException e) { blocked = true; }
        check(blocked, "preparation refuses an existing output collection");
        check(Arrays.equals(before, Files.readAllBytes(output.resolve("judgments.csv"))), "existing judgment file unchanged");
        expectIllegal(() -> EvaluationRunner.prepare(scratch.resolve("bad-split"), "all"), "unsupported split rejected");
        check(!Files.exists(scratch.resolve("bad-split")), "invalid split creates no artifacts");
    }

    private static Path write(Path dir, String name, String value) throws IOException {
        Path path = dir.resolve(name); return Files.writeString(path, value, StandardCharsets.UTF_8);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message + " expected=" + expected + " actual=" + actual); }
    private static void near(double expected, Object actual, String message) { check(actual instanceof Number n && Math.abs(expected - n.doubleValue()) < 0.000001, message + " expected=" + expected + " actual=" + actual); }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
    private static void expectIllegal(ThrowingAction action, String message) throws Exception {
        boolean thrown = false; try { action.run(); } catch (IllegalArgumentException e) { thrown = true; } check(thrown, message);
    }
}
