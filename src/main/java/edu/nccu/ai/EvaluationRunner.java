package edu.nccu.ai;

import edu.nccu.ai.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Reproducible offline collection and human-judgment scoring; never calls a paid or live provider. */
public final class EvaluationRunner {
    private static final List<String> CSV_COLUMNS = List.of("queryId", "split", "system", "rank", "relevant", "seconds",
        "url", "title", "resultAvailable", "query", "notes");
    private EvaluationRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) { usage(); return; }
        switch (args[0]) {
            case "prepare" -> {
                String split = "dev"; Path output = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--split") && i + 1 < args.length) split = args[++i];
                    else if (!args[i].startsWith("--") && output == null) output = Path.of(args[i]);
                    else throw new IllegalArgumentException("用法：prepare [output-dir] [--split dev|test]");
                }
                if (!Set.of("dev", "test").contains(split)) throw new IllegalArgumentException("split 只能是 dev 或 test");
                if (output == null) output = Path.of("outputs", "evaluation", split + "-" + ZonedDateTime.now(ZoneId.of("Asia/Taipei"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                prepare(output, split);
            }
            case "score" -> {
                if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("用法：score input.csv [output.json]");
                Map<String,Object> summary = score(Path.of(args[1]));
                String json = Json.stringify(summary);
                if (args.length == 3) writeNew(Path.of(args[2]), json + "\n");
                System.out.println(json);
            }
            default -> throw new IllegalArgumentException("未知命令；使用 --help 查看用法。");
        }
    }

    private static void usage() {
        System.out.println("AI WEBSITE 離線評估\n"
            + "  prepare [output-dir] [--split dev|test]  預設僅執行 dev 題\n"
            + "  score input.csv [output.json]           僅計算人工填寫的 relevant 標記\n"
            + "prepare 不會呼叫 Google、Brave、Jev 或生成模型；不會自動判斷相關性。");
    }

    public static void prepare(Path output, String split) throws Exception {
        if (!Set.of("dev", "test").contains(split)) throw new IllegalArgumentException("split 只能是 dev 或 test");
        Path corpusPath = Path.of("data/demo-corpus.json"), questionsPath = Path.of("data/evaluation.json");
        Map<String,Object> evaluation = Json.object(Json.parse(Files.readString(questionsPath, StandardCharsets.UTF_8)));
        List<Map<String,Object>> questions = Json.array(evaluation.get("questions")).stream().map(Json::object)
            .filter(q -> split.equals(Json.string(q.get("split"), ""))).toList();
        if (questions.isEmpty()) throw new IllegalArgumentException("指定 split 沒有題目");
        List<String> artifacts = List.of("results.json", "judgments.csv", "prompts.txt", "README.txt");
        for (String artifact : artifacts) if (Files.exists(output.resolve(artifact)))
            throw new IOException("輸出已存在，請使用新資料夾以保留先前評分：" + output.resolve(artifact));
        DemoCorpus corpus = DemoCorpus.load(corpusPath);
        SearchEngine engine = new SearchEngine(corpus::candidates, corpus::fetch);
        List<Map<String,Object>> runs = new ArrayList<>();
        StringBuilder csv = new StringBuilder(); appendCsv(csv, CSV_COLUMNS);
        StringBuilder prompts = new StringBuilder("Stage 6 提示詞比較草稿\n"
            + "請記錄服務、模型、日期、是否啟用網路搜尋；本文沒有模型實際回答。\n"
            + "同題的一般與策略版本應使用同一模型與工具設定。\n\n");
        for (Map<String,Object> question : questions) {
            String id = Json.string(question.get("id"), ""), query = Json.string(question.get("query"), "");
            String intent = Json.string(question.get("intent"), "concept");
            long originalStart = System.nanoTime();
            List<String> originalCandidates = corpus.candidates(query, 6);
            List<Map<String,Object>> originalResults = new ArrayList<>();
            for (String url : originalCandidates.stream().limit(5).toList()) {
                Page page = corpus.fetch(url);
                originalResults.add(Map.of("url", url, "title", page.title(), "snippet", Ranking.snippet(page.text(), KeywordCounter.keywords(query)),
                    "source", page.source(), "synthetic", true));
            }
            Map<String,Object> originalRun = run(question, "demo-original", (System.nanoTime() - originalStart) / 1_000_000.0);
            originalRun.put("candidateUrls", originalCandidates); originalRun.put("results", originalResults); runs.add(originalRun);
            appendJudgments(csv, question, "demo-original", originalResults);
            for (boolean expand : List.of(false, true)) {
                String system = expand ? "demo-expanded" : "demo-rule";
                SearchOptions options = new SearchOptions(query, intent, 1, 20, 5, expand);
                long start = System.nanoTime(); SearchReport report = engine.search(options);
                Map<String,Object> run = run(question, system, (System.nanoTime() - start) / 1_000_000.0);
                run.put("options", options); run.put("report", report); runs.add(run);
                List<Map<String,Object>> resultRows = report.results().stream()
                    .map(r -> Map.<String,Object>of("url", r.url(), "title", r.title())).toList();
                appendJudgments(csv, question, system, resultRows);
            }
            prompts.append(id).append(" [").append(split).append("] ").append(query).append('\n')
                .append("一般提問：\n").append(query).append("\n\n策略提問：\n")
                .append("請處理以下 AI 知識問題：").append(query).append('\n')
                .append("本次目的：").append(intentLabel(intent)).append("。先辨識核心主題與限制，再查找與問題直接相關的資料。")
                .append("優先查看原始論文、官方文件與可驗證的教學；實作問題請說明環境及版本。")
                .append("檢查資料是否真的回答問題；若需改用相關詞，說明新增詞及理由。")
                .append("提供簡短回答與可點擊來源，並指出來源支持的內容。")
                .append("若未使用網路搜尋、資料不足或找不到依據，請明確說明，不要編造來源。\n\n")
                .append("人工評分準則：").append(String.join("；", Json.array(question.get("expected")).stream().map(v -> Json.string(v, "")).toList()))
                .append("\n\n--------------------\n\n");
        }
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("createdAt", Instant.now().toString()); metadata.put("split", split); metadata.put("questionCount", questions.size());
        metadata.put("source", "data/demo-corpus.json — 教學示範資料（自編）"); metadata.put("synthetic", true);
        metadata.put("corpusSha256", sha256(corpusPath)); metadata.put("questionsSha256", sha256(questionsPath));
        metadata.put("providers", Map.of("Google", "not run", "Brave Search", "not run", "Jev", "not run", "LLM answer", "not run"));
        metadata.put("labels", "pending human judgment; no automatic relevance labels");
        metadata.put("baseline", "demo-original uses the first five of the same six initial local candidates used by demo-rule");
        metadata.put("warning", "離線合成資料只用於流程驗證，不能用來宣稱搜尋品質勝過 Google。engineElapsedMs 是程式耗時，不是人工完成時間。");
        metadata.put("testExposure", split.equals("test") ? "Explicitly requested; do not tune on these outputs and still call them unseen holdout." : "Only dev queries executed.");
        Files.createDirectories(output);
        writeNew(output.resolve("results.json"), Json.stringify(Map.of("metadata", metadata, "questions", questions, "runs", runs)) + "\n");
        writeNew(output.resolve("judgments.csv"), "\ufeff" + csv);
        writeNew(output.resolve("prompts.txt"), prompts.toString());
        writeNew(output.resolve("README.txt"), "本資料夾含 " + questions.size() + " 題 " + split + " 題目的三組離線結果。\n"
            + "所有搜尋文字來自自編示範資料；未執行 Google、Brave、Jev 或 LLM。\n"
            + "judgments.csv 的 relevant 請由人填 true/false，尚未判讀保持空白；seconds 是同題同系統的人工完成秒數。\n"
            + "每題每系統列出五個名次；resultAvailable=false 表示確實沒有該名次結果，確認後可填 false。\n"
            + "請保留原始模板，複製一份給每位評分者。分歧討論後另存共識評分，勿合併重複名次。\n"
            + "score 會列出尚未評分筆數及各系統題目集合；只可比較同題集合的結果。\n"
            + "prompts.txt 是可手動貼到同一 LLM 服務的兩種提示詞，並非模型回答。\n"
            + "測試集只有明確指定 --split test 才執行；看過測試結果後不可用來調參卻仍稱未見資料。\n"
            + "更多說明見 docs/evaluation-guide.md。\n");
        System.out.println("已建立 " + questions.size() + " 題 × 3 組離線結果與空白人工評分表：" + output.toAbsolutePath().normalize());
        System.out.println("尚未產生任何真實搜尋成效分數；未呼叫外部服務。");
    }

    private static Map<String,Object> run(Map<String,Object> question, String system, double elapsed) {
        Map<String,Object> run = new LinkedHashMap<>();
        run.put("queryId", question.get("id")); run.put("query", question.get("query")); run.put("intent", question.get("intent"));
        run.put("system", system); run.put("engineElapsedMs", Math.round(elapsed * 1000) / 1000.0); return run;
    }
    private static void appendJudgments(StringBuilder csv, Map<String,Object> question, String system, List<Map<String,Object>> results) {
        for (int rank = 1; rank <= 5; rank++) {
            Map<String,Object> result = rank <= results.size() ? results.get(rank - 1) : Map.of();
            appendCsv(csv, List.of(Json.string(question.get("id"), ""), Json.string(question.get("split"), ""), system,
                String.valueOf(rank), "", "", Json.string(result.get("url"), ""), Json.string(result.get("title"), ""),
                String.valueOf(!result.isEmpty()), Json.string(question.get("query"), ""), ""));
        }
    }

    public static Map<String,Object> score(Path input) throws Exception {
        if (Files.size(input) > 4_000_000) throw new IllegalArgumentException("CSV 上限為 4 MB");
        List<List<String>> table = parseCsv(Files.readString(input, StandardCharsets.UTF_8));
        if (table.isEmpty()) throw new IllegalArgumentException("CSV 不可空白");
        List<String> columns = new ArrayList<>(table.getFirst());
        if (!columns.isEmpty()) columns.set(0, columns.getFirst().replaceFirst("^\ufeff", ""));
        Set<String> uniqueColumns = new HashSet<>(columns);
        if (uniqueColumns.size() != columns.size()) throw new IllegalArgumentException("CSV 欄位名稱不可重複");
        for (String required : List.of("queryId", "system", "rank", "relevant"))
            if (!columns.contains(required)) throw new IllegalArgumentException("CSV 缺少欄位：" + required);
        List<Object> judgments = new ArrayList<>(); int pending = 0;
        Map<String,Set<String>> querySets = new LinkedHashMap<>(); Set<String> allPositions = new HashSet<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i); if (cells.size() == 1 && cells.getFirst().isBlank()) continue;
            if (cells.size() != columns.size()) throw new IllegalArgumentException("CSV 第 " + (i+1) + " 筆欄數不符");
            Map<String,String> row = new LinkedHashMap<>(); for (int c = 0; c < columns.size(); c++) row.put(columns.get(c), cells.get(c));
            String id = row.get("queryId").strip(), system = row.get("system").strip(), rank = row.get("rank").strip();
            if (!rank.matches("[1-5]") || id.isBlank() || system.isBlank()) throw new IllegalArgumentException("CSV 第 " + (i+1) + " 筆題號、系統或排名不合法");
            if (!allPositions.add(id + "\u0000" + system + "\u0000" + rank)) throw new IllegalArgumentException("CSV 有重複的題號、系統及名次");
            String relevant = row.get("relevant").strip().toLowerCase(Locale.ROOT);
            if (relevant.isEmpty()) { pending++; continue; }
            if (!relevant.equals("true") && !relevant.equals("false")) throw new IllegalArgumentException("relevant 只接受 true、false 或空白");
            Map<String,Object> judgment = new LinkedHashMap<>(); judgment.put("queryId", id); judgment.put("system", system);
            judgment.put("rank", Integer.parseInt(rank)); judgment.put("relevant", Boolean.valueOf(relevant));
            String seconds = row.getOrDefault("seconds", "").strip();
            if (!seconds.isEmpty()) { try { judgment.put("seconds", Double.parseDouble(seconds)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("seconds 必須是非負秒數"); } }
            judgments.add(judgment); querySets.computeIfAbsent(system, key -> new TreeSet<>()).add(id);
        }
        Map<String,Object> output = new LinkedHashMap<>(Evaluation.evaluate(judgments));
        output.put("input", input.toAbsolutePath().normalize().toString()); output.put("inputSha256", sha256(input));
        output.put("pendingRows", pending); output.put("queryIdsBySystem", querySets);
        boolean matched = querySets.values().stream().distinct().count() <= 1;
        output.put("sameQuerySet", matched);
        output.put("comparisonStatus", judgments.isEmpty() ? "沒有人工評分，尚無成效指標。" : pending > 0 ? "尚有未判讀資料；分數僅供檢查，請完成同一題集合再比較。"
            : matched ? "題目集合一致；仍須確認資料來源、設定、評分準則與評分者一致。" : "各系統的已評分題目不同，不能直接比較平均分數。");
        return output;
    }

    /** Strict RFC 4180-style parser: quoted commas, escaped quotes, and embedded newlines supported. */
    public static List<List<String>> parseCsv(String input) {
        if (input.startsWith("\ufeff")) input = input.substring(1);
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false, afterQuote = false, fieldStarted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i+1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; afterQuote = true; }
                } else cell.append(c);
                continue;
            }
            if (c == '"') {
                if (fieldStarted || afterQuote) throw new IllegalArgumentException("CSV 引號位置不合法");
                quoted = true; fieldStarted = true;
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(cell.toString()); cell.setLength(0); fieldStarted = false; afterQuote = false;
                if (c != ',') { rows.add(List.copyOf(row)); row.clear(); if (c == '\r' && i+1 < input.length() && input.charAt(i+1) == '\n') i++; }
            } else {
                if (afterQuote) throw new IllegalArgumentException("CSV 關閉引號後只能接逗號或換行");
                cell.append(c); fieldStarted = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 有未關閉的引號");
        if (!row.isEmpty() || fieldStarted || afterQuote) { row.add(cell.toString()); rows.add(List.copyOf(row)); }
        return List.copyOf(rows);
    }
    private static void appendCsv(StringBuilder out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append(','); String value = fields.get(i);
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }
    private static String intentLabel(String intent) { return switch (intent) { case "build" -> "動手實作"; case "compare" -> "比較方法"; default -> "理解概念"; }; }
    private static String sha256(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static void writeNew(Path path, String text) throws IOException {
        Path parent = path.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
