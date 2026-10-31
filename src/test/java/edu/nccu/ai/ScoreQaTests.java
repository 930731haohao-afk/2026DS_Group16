package edu.nccu.ai;

import edu.nccu.ai.Models.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/** Offline scoring QA. Expected values never call Ranking or KeywordCounter. */
public final class ScoreQaTests {
    private static final MathContext MC = new MathContext(45, RoundingMode.HALF_EVEN);
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal LN2 = new BigDecimal("0.693147180559945309417232121458176568075500134360255254120680");
    private static int checks;
    private ScoreQaTests() { }

    public static void main(String[] args) throws Exception {
        System.out.println("PASS: " + run() + " scoring QA checks (independent decimal oracle, properties, trees, ordering)");
    }

    public static int run() throws Exception {
        checks = 0;
        pageCases(); intentCases(); treeCases(); orderingCases();
        return checks;
    }

    private static void pageCases() {
        same(0, Ranking.pageScore(Map.of(), 0), "empty counts");
        same(1, Ranking.pageScore(Map.of("x", 1), 0), "one occurrence");
        same(2.191, Ranking.pageScore(Map.of("x", 3, "y", 1), 1200), "published formula example");
        for (int length : new int[]{-1, 0, 1, 1199, 1200, 1201, 100_000, Integer.MAX_VALUE}) {
            for (int count : new int[]{-1, 0, 1, 2, 3, 100, Integer.MAX_VALUE}) {
                Map<String,Integer> counts = Map.of("x", count, "zero", 0);
                same(oracle(counts, length), Ranking.pageScore(counts, length), "boundary " + count + "/" + length);
            }
        }
        Random random = new Random(20261031L);
        for (int trial = 0; trial < 250; trial++) {
            LinkedHashMap<String,Integer> counts = new LinkedHashMap<>();
            for (int i = 0, n = 1 + random.nextInt(8); i < n; i++) counts.put("k" + i, random.nextInt(100_001));
            int length = random.nextInt(2_000_001);
            double actual = Ranking.pageScore(counts, length);
            same(oracle(counts, length), actual, "decimal oracle trial " + trial);
            check(Double.isFinite(actual) && actual >= 0, "finite nonnegative score");
            LinkedHashMap<String,Integer> reversed = new LinkedHashMap<>();
            new ArrayList<>(counts.keySet()).reversed().forEach(k -> reversed.put(k, counts.get(k)));
            same(actual, Ranking.pageScore(reversed, length), "term order does not matter");
            reversed.put("absent", 0);
            same(actual, Ranking.pageScore(reversed, length), "zero count does not add score");
            check(Ranking.pageScore(counts, length + 1200) <= actual, "longer text does not increase score");
            reversed.put("k0", reversed.get("k0") + 1);
            check(Ranking.pageScore(reversed, length) >= actual, "additional match does not lower score");
        }
    }

    /** ln(n) = k*ln(2) + 2*(z + z^3/3 + ...), after binary range reduction. */
    private static BigDecimal logarithm(int n) {
        int k = 31 - Integer.numberOfLeadingZeros(n);
        BigDecimal reduced = new BigDecimal(n).divide(new BigDecimal(1L << k), MC);
        BigDecimal z = reduced.subtract(BigDecimal.ONE).divide(reduced.add(BigDecimal.ONE), MC);
        BigDecimal square = z.multiply(z, MC), power = z, sum = BigDecimal.ZERO;
        for (int denominator = 1; denominator <= 99; denominator += 2) {
            sum = sum.add(power.divide(new BigDecimal(denominator), MC), MC);
            power = power.multiply(square, MC);
        }
        return sum.multiply(TWO, MC).add(LN2.multiply(new BigDecimal(k), MC), MC);
    }

    private static double oracle(Map<String,Integer> counts, int length) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int n : counts.values()) if (n > 0) sum = sum.add(BigDecimal.ONE.add(logarithm(n), MC), MC);
        BigDecimal normalization = BigDecimal.ONE.add(new BigDecimal(Math.max(0, length))
                .divide(new BigDecimal(1200), MC), MC).sqrt(MC);
        return rounded(sum.divide(normalization, MC));
    }

    private static double rounded(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static void intentCases() {
        same(1.5, Ranking.intentScore(page("x", "definition", "quasar overview"), "concept"), "concept signal and title bonus");
        same(1.5, Ranking.intentScore(page("x", "comparison", "quasar versus"), "compare"), "comparison signal and title bonus");
        same(1.5, Ranking.intentScore(page("x", "python", "quasar implementation"), "build"), "application signal and title bonus");
        same(0, Ranking.intentScore(page("x", "plain", "quasar"), "concept"), "no intent signals");
        same(3, Ranking.intentScore(page("x", "definition overview", "quasar beginner introduction background intuition"), "concept"), "intent cap");
        same(0.5, Ranking.intentScore(page("x", "plain", "definition definition definition"), "concept"), "repeated signal counted once");
    }

    private static void treeCases() {
        String root = "https://qa.test/docs/root", a = "https://qa.test/docs/a", b = "https://qa.test/docs/b";
        String child = "https://qa.test/docs/child", failed = "https://qa.test/docs/failed";
        Page rootPage = page(root, "overview", "quasar definition", a, b, failed, a, root);
        Page aPage = page(a, "plain", "quasar quasar", child, root);
        Page bPage = page(b, "definition", "unrelated overview introduction beginner background");
        Page childPage = page(child, "plain", "quasar quasar quasar");
        Map<String,Page> pages = Map.of(root, rootPage, a, aPage, b, bPage, child, childPage);
        SearchEngine engine = new SearchEngine((q, limit) -> List.of(root), url -> {
            if (url.equals(failed)) throw new IOException("authored missing page");
            return pages.get(url);
        });
        SearchResult result = engine.search(new SearchOptions("quasar", "concept", 2, 10, 5, false)).results().getFirst();
        BigDecimal rootScore = decimalPage(rootPage, 1), aScore = decimalPage(aPage, 2), childScore = decimalPage(childPage, 3);
        // Successful zero-match child remains in the average; failed child is excluded.
        BigDecimal rule = rootScore.add(aScore.divide(new BigDecimal(4), MC))
                .add(childScore.divide(new BigDecimal(4), MC));
        same(rounded(rule), result.ruleScore(), "per-depth mean, 0.5/0.25 factors, failure excluded");
        same(1.5, result.intentScore(), "zero-topic child receives no intent bonus");
        same(rounded(rule.add(new BigDecimal("1.125"))), result.finalScore(), "final score uses unrounded aggregate plus 0.75 intent");
        check(result.counts().get("quasar") == 6, "unique-page counts total 1+2+0+3");
        check(result.tree().children().size() == 3, "duplicate and cycle do not create extra nodes");
        for (TreeNode node : result.tree().children()) {
            if (node.url().equals(a)) same(aScore.doubleValue(), node.pageScore(), "child score independently checked");
            if (node.url().equals(b)) same(0, node.pageScore(), "successful zero-topic page");
            if (node.url().equals(failed)) check(node.status().equals("error"), "failed page represented as error");
        }
        SearchResult shallow = engine.search(new SearchOptions("quasar", "concept", 0, 10, 5, false)).results().getFirst();
        same(rootScore.doubleValue(), shallow.ruleScore(), "depth zero excludes descendants");
        same(rounded(rootScore.add(new BigDecimal("1.125"))), shallow.finalScore(), "single-page final score");
        same(rootScore.doubleValue(), result.tree().pageScore(), "root score uses title plus body length");
    }

    private static BigDecimal decimalPage(Page page, int count) {
        return BigDecimal.valueOf(oracle(Map.of("quasar", count), page.title().length() + page.text().length()));
    }

    private static void orderingCases() {
        String low = "https://qa.test/low", high = "https://qa.test/high", tie = "https://qa.test/tie";
        Page lowPage = page(low, "plain", "quasar " + "x".repeat(193));
        Page highPage = page(high, "plain", "quasar quasar " + "x".repeat(186));
        Page tiePage = page(tie, highPage.title(), highPage.text());
        Map<String,Page> pages = Map.of(low, lowPage, high, highPage, tie, tiePage);
        SearchEngine engine = new SearchEngine((q, limit) -> List.of(low, high, tie), pages::get);
        List<SearchResult> results = engine.search(new SearchOptions("quasar", "concept", 0, 10, 2, false)).results();
        check(results.size() == 2, "top-k result count");
        check(results.get(0).url().equals(high) && results.get(1).url().equals(tie), "top-k and original-order tie break");
        same(decimalPage(highPage, 2).doubleValue(), results.get(0).finalScore(), "ranking numeric score");
    }

    private static Page page(String url, String title, String text, String... links) {
        return new Page(url, title, text, List.of(links), "authored scoring QA", true);
    }

    private static void same(double expected, double actual, String label) {
        check(Math.abs(expected - actual) < 1e-10, label + ": expected " + expected + ", actual " + actual);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
