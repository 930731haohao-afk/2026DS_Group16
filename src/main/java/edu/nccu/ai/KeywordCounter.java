package edu.nccu.ai;

import java.util.*;
import java.util.regex.*;

/** Deterministic query analysis and Boyer-Moore matching; no model or network calls. */
public final class KeywordCounter {
    private KeywordCounter() { }
    private static final Pattern TOKENS = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_+#.\\-]*|[\\p{IsHan}]+");
    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "what", "how", "to", "of", "and", "or", "in", "for", "with", "什麼", "什麼是", "如何", "怎麼", "請問", "介紹", "的", "與", "和", "有什麼", "可以", "做", "知識");
    private static final List<List<String>> GROUPS = List.of(
        List.of("ai", "人工智慧", "人工智能", "artificial intelligence"),
        List.of("rsi", "recursive self-improvement", "recursive self improvement", "遞迴自我改進", "遞歸自我改進"),
        List.of("agi", "通用人工智慧", "通用人工智能", "artificial general intelligence"),
        List.of("asi", "人工超級智慧", "超級人工智慧", "artificial superintelligence", "superintelligence"),
        List.of("llm", "llms", "大型語言模型", "大語言模型", "language model"),
        List.of("rag", "檢索增強生成", "retrieval augmented generation", "retrieval-augmented generation"),
        List.of("agent", "agents", "智慧代理", "智能代理", "代理人"),
        List.of("embedding", "embeddings", "向量嵌入", "嵌入向量"),
        List.of("retrieval", "檢索"), List.of("chunking", "文本切分", "分塊"),
        List.of("fine-tuning", "finetuning", "微調"), List.of("transformer", "transformers"),
        List.of("attention", "注意力"), List.of("hallucination", "hallucinations", "幻覺"),
        List.of("evaluation", "評估"), List.of("tool calling", "function calling", "工具呼叫"),
        List.of("prompt", "prompting", "提示詞"), List.of("memory", "記憶"),
        List.of("vector", "向量"), List.of("reranking", "reranker", "重排"),
        List.of("beginner", "入門", "初學"), List.of("implementation", "實作"),
        List.of("example", "examples", "範例"), List.of("comparison", "比較", "差異"),
        List.of("alignment", "對齊"), List.of("safety", "安全"),
        List.of("governance", "治理"), List.of("regulation", "監管", "法規"),
        List.of("geopolitics", "geopolitical", "地緣政治"),
        List.of("export controls", "export control", "出口管制"),
        List.of("compute", "算力"), List.of("semiconductor", "semiconductors", "半導體"),
        List.of("sovereignty", "主權"), List.of("supply chain", "供應鏈"),
        List.of("economics", "經濟學", "經濟"), List.of("employment", "就業"),
        List.of("labor", "labour", "勞動"), List.of("automation", "自動化"),
        List.of("productivity", "生產力"), List.of("inequality", "不平等"),
        List.of("philosophy", "哲學"), List.of("consciousness", "意識"),
        List.of("ethics", "倫理"), List.of("democracy", "民主"),
        List.of("bias", "偏見"), List.of("privacy", "隱私"),
        List.of("copyright", "著作權", "版權"));

    /** Up to 36 terms; optional bilingual aliases enrich queries but never restrict their topics. */
    public static List<String> keywords(String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKENS.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group().replaceAll("[.\\-]+$", "");
            if (token.isBlank() || STOP.contains(token)) continue;
            if (token.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                String clean = cleanHan(token);
                if (!clean.isBlank() && !STOP.contains(clean)) terms.add(clean);
                // ponytail: preserve unknown Chinese phrases; dictionary matches are hints, not a complete segmenter.
                String remainder = clean;
                for (String alias : GROUPS.stream().flatMap(List::stream).filter(KeywordCounter::containsHan)
                        .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                    if (clean.contains(alias)) terms.add(alias);
                    remainder = remainder.replace(alias, " ");
                }
                for (String part : remainder.split("\\s+")) {
                    String word = cleanHan(part);
                    if (!word.isBlank() && !STOP.contains(word)) terms.add(word);
                }
            } else terms.add(token);
        }
        for (List<String> group : GROUPS) {
            if (group.stream().anyMatch(alias -> count(lower, alias) > 0)) terms.addAll(group);
        }
        return terms.stream().limit(36).toList();
    }

    /** Preserve the user's full query; add AI context and disambiguate a few overloaded acronyms. */
    public static String webQuery(String input) {
        String query = Objects.requireNonNullElse(input, "").strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) return query;
        if (count(query, "rsi") > 0 && count(query, "recursive self-improvement") == 0
                && count(query, "recursive self improvement") == 0) query += " recursive self-improvement";
        if (count(query, "agi") > 0 && count(query, "artificial general intelligence") == 0)
            query += " artificial general intelligence";
        if (count(query, "asi") > 0 && count(query, "artificial superintelligence") == 0)
            query += " artificial superintelligence";
        if (GROUPS.getFirst().stream().noneMatch(alias -> count(input, alias) > 0)) query += " 人工智慧 AI";
        return query;
    }

    private static String cleanHan(String text) {
        return text.replaceFirst("^(請問|什麼是|如何|怎麼)", "")
            .replaceFirst("(是什麼|怎麼做|怎麼用|有哪些|有什麼|的意思)$", "");
    }

    public static Map<String, Integer> counts(String text, List<String> keywords) {
        LinkedHashMap<String,Integer> counts = new LinkedHashMap<>();
        String value = Objects.requireNonNullElse(text, "").toLowerCase(Locale.ROOT);
        for (String word : keywords) {
            if (word != null && !word.isBlank()) counts.put(word, countLower(value, word.toLowerCase(Locale.ROOT)));
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Stage 0 counts exactly these comma/semicolon-separated terms; spaces inside a phrase are retained. */
    public static List<String> literalKeywords(String input) {
        if (input == null || input.isBlank()) return List.of();
        return Arrays.stream(input.split("[,，;；\\r\\n]+"))
            .map(String::strip).filter(s -> !s.isBlank()).distinct().limit(36).toList();
    }

    /** Overlapping matches count. ASCII letter/digit/underscore tokens need word boundaries. */
    public static int count(String text, String pattern) {
        if (text == null || pattern == null || pattern.isEmpty()) return 0;
        return countLower(text.toLowerCase(Locale.ROOT), pattern.toLowerCase(Locale.ROOT));
    }

    private static int countLower(String text, String pattern) {
        int m = pattern.length(), n = text.length();
        if (m == 0 || m > n) return 0;
        Map<Character,Integer> last = new HashMap<>();
        for (int i = 0; i < m; i++) last.put(pattern.charAt(i), i);
        int[] shift = goodSuffix(pattern);
        int start = 0, found = 0;
        while (start <= n - m) {
            int j = m - 1;
            while (j >= 0 && pattern.charAt(j) == text.charAt(start + j)) j--;
            if (j < 0) {
                boolean left = !asciiWord(pattern.charAt(0)) || start == 0 || !asciiWord(text.charAt(start - 1));
                boolean right = !asciiWord(pattern.charAt(m - 1)) || start + m == n || !asciiWord(text.charAt(start + m));
                if (left && right) found++;
                start += shift[0];
            } else {
                int badCharacter = j - last.getOrDefault(text.charAt(start + j), -1);
                start += Math.max(shift[j + 1], Math.max(1, badCharacter));
            }
        }
        return found;
    }

    private static int[] goodSuffix(String pattern) {
        int m = pattern.length();
        int[] shift = new int[m + 1], border = new int[m + 1];
        int i = m, j = m + 1;
        border[i] = j;
        while (i > 0) {
            while (j <= m && pattern.charAt(i - 1) != pattern.charAt(j - 1)) {
                if (shift[j] == 0) shift[j] = j - i;
                j = border[j];
            }
            border[--i] = --j;
        }
        j = border[0];
        for (i = 0; i <= m; i++) {
            if (shift[i] == 0) shift[i] = j;
            if (i == j) j = border[j];
        }
        return shift;
    }

    private static boolean asciiWord(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }
    private static boolean containsHan(String s) {
        return s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    /** Optional expansion vocabulary; a term still must occur in fetched relevant text. */
    public static List<String> expansionVocabulary() {
        return GROUPS.stream().skip(1)
            .filter(group -> !Set.of("beginner", "implementation", "example", "comparison").contains(group.getFirst()))
            .flatMap(List::stream).distinct().toList();
    }
}
