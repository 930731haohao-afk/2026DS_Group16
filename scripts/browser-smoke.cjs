/* Optional development check; Playwright is not a runtime dependency.
 * Start the Java server first, then run:
 *   node scripts/browser-smoke.cjs
 * Set PLAYWRIGHT_MODULE to a local Playwright module path if not installed in Node's resolution path.
 * Set BROWSER_PATH to an existing Chromium/Edge binary; BASE_URL defaults to http://127.0.0.1:8080.
 * Uses mocked live results, authored demo data, and synthetic judgments solely to verify UI behavior,
 * not external provider availability or search quality.
 */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = path.resolve(__dirname, "..");
const work = path.join(root, "work");
const base = process.env.BASE_URL || "http://127.0.0.1:8080";
assert(["127.0.0.1", "localhost", "[::1]"].includes(new URL(base).hostname), "Smoke test only targets a local server.");
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; console.log(`PASS ${message}`); }

(async () => {
  await fs.mkdir(work, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: "zh-TW", acceptDownloads: true });
  const errors = [];
  const attachErrors = (page) => {
    page.on("pageerror", (error) => errors.push(`JS: ${error.message}`));
    page.on("console", (message) => { if (message.type() === "error") errors.push(`console: ${message.text()}`); });
  };
  const page = await context.newPage(); attachErrors(page);
  page.setDefaultTimeout(15000);
  const liveRequests = [];
  await context.route("**/api/search", async (route) => {
    const request = route.request().postDataJSON();
    if (request.mode !== "live") return route.continue();
    liveRequests.push(request);
    if (request.query === "AI invalid response smoke") return route.fulfill({ contentType: "application/json", body: "invalid-json" });
    const noResults = request.query === "AI no results smoke";
    const url = "https://arxiv.org/abs/2504.16066";
    await route.fulfill({ json: {
      runId: "synthetic-live-ui-check", query: request.query, searchQuery: `${request.query} artificial intelligence`,
      mode: "live", intent: request.intent, elapsedMs: 12, pagesFetched: 0,
      keywords: ["AI"], expandedKeywords: [], sourceWarnings: ["合成測試資料，僅驗證介面，不代表搜尋品質。"],
      results: noResults ? [] : [{ title: "AI 研究來源（合成介面測試）", url, snippet: "AI 相關研究的搜尋摘要。這份回應只用於驗證 UI。", source: "測試搜尋摘要", contentStatus: "snippet", originalRank: 1,
        ruleScore: 2, intentScore: 1, finalScore: 3, counts: { AI: 1 }, tree: { title: "AI 研究來源", url, status: "snippet", pageScore: 2, counts: { AI: 1 }, children: [], warning: "未讀取全文" } }]
    } });
  });
  async function downloadFrom(id) {
    const waiting = page.waitForEvent("download");
    await page.locator(id).click();
    const download = await waiting;
    const file = await download.path();
    return { name: download.suggestedFilename(), text: await fs.readFile(file, "utf8") };
  }
  async function submittedSearch(action) {
    const waiting = page.waitForResponse((response) => response.url().endsWith("/api/search") && response.request().method() === "POST");
    await action();
    const response = await waiting;
    assert.equal(response.status(), 200);
    await page.locator("#results[aria-busy='false'] .result-card").first().waitFor();
    return response.json();
  }
  async function noOverflow(target, label) {
    const dimensions = await target.evaluate(() => ({ document: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    check(dimensions.document <= dimensions.viewport + 1, `${label}: no horizontal page overflow (${dimensions.document}/${dimensions.viewport})`);
  }
  try {
    await page.goto(base, { waitUntil: "networkidle" });
    check(await page.locator(".hero, .hero-note, .orbit-art, .search-sidebar").count() === 0, "Plain layout has no hero, decorative art, or sidebar");
    check((await page.locator("#query").boundingBox()).y <= 220, "Desktop search input is within the top 220 pixels");
    check(await page.locator("#mode").inputValue() === "live", "Public web search is the default mode");
    check(liveRequests.length === 0 && await page.locator("#query").inputValue() === "", "Startup waits for a user question and does not auto-search RAG");
    await submittedSearch(() => page.locator('[data-example="RSI"]').click());
    check(liveRequests[0].query === "RSI" && liveRequests[0].mode === "live", "RSI example submits a live query");
    check(await page.locator(".result-card h3 a").getAttribute("href") === "https://arxiv.org/abs/2504.16066", "Live result title links to the supplied original page");
    check(await page.locator(".open-source").getAttribute("target") === "_blank" && (await page.locator(".open-source").innerText()).includes("開啟原文"), "Prominent original-source action opens a separate tab");
    check((await page.locator(".content-status").innerText()).includes("未讀取全文"), "Snippet-only result clearly says full text was not fetched");
    check(await page.locator(".result-details[open], #run-details[open]").count() === 0 && !await page.locator(".keyword-chip").first().isVisible() && !await page.locator(".result-score").first().isVisible() && !await page.locator(".rank-move").first().isVisible(), "Ranking and search metadata are collapsed by default");
    await page.locator(".result-details > summary").first().click();
    check((await page.locator(".details-content").innerText()).includes("搜尋摘要中的次數（非全文）"), "Snippet counts are not presented as full-text counts");
    await page.locator("#run-details > summary").click();
    check((await page.locator("#run-details").innerText()).includes("RSI artificial intelligence"), "Actual provider query is visible in run details");
    check((await page.locator("#search-warnings").innerText()).includes("合成測試資料"), "Provider source warnings are rendered");
    const compareReport = await submittedSearch(() => page.locator('[data-example="AI 地緣政治"]').click());
    check(compareReport.intent === "compare", "Geopolitics example selects comparison intent");
    await submittedSearch(() => page.locator('[data-example="AI 意識 哲學"]').click());
    const buildReport = await submittedSearch(() => page.locator('[data-example="AI 就業 經濟"]').click());
    check(buildReport.intent === "build", "Economics example selects research/application intent");
    await noOverflow(page, "Desktop");
    await page.screenshot({ path: path.join(work, "simple-ui-desktop.png"), fullPage: true });

    await page.locator("#query").fill("AI no results smoke");
    const emptyWait = page.waitForResponse((response) => response.url().endsWith("/api/search"));
    await page.locator("#search-submit").click(); await emptyWait;
    await page.locator(".search-alternatives a").first().waitFor();
    check(await page.locator(".search-alternatives a").count() === 2 && (await page.locator("#results").innerText()).includes("不表示主題沒有資料"), "Empty response offers honest explanation and external search links");
    check(new URL(await page.locator(".search-alternatives a").first().getAttribute("href")).searchParams.get("q") === "AI no results smoke artificial intelligence", "External fallback preserves the actual search query");
    await page.locator("#query").fill("AI invalid response smoke");
    const failedWait = page.waitForResponse((response) => response.url().endsWith("/api/search"));
    await page.locator("#search-submit").click(); await failedWait;
    await page.locator("#search-error").waitFor();
    check((await page.locator("#search-error").innerText()).includes("未傳回有效資料") && await page.locator(".search-alternatives a").count() === 2, "Failed response keeps a clear error and usable external search links");

    await page.locator("#settings-toggle").click();
    check(await page.locator("#search-settings").isVisible(), "Advanced settings expand");
    await page.locator("#mode").selectOption("demo");
    await page.locator("#query").fill("RAG");
    const report = await submittedSearch(() => page.locator("#search-submit").click());
    check(report.mode === "demo" && report.results.length > 0, "Deliberately selected demo search still renders actual local API results");
    check((await page.locator("#data-notice").innerText()).includes("自編"), "Demo provenance is explicit");
    check(await page.locator('a[href*="demo.local"]').count() === 0 && await page.locator(".open-source").count() === 0, "Authored demo pages have neither fake links nor original-source actions");

    const jsonExport = await downloadFrom("#export-search-json");
    const exportedReport = JSON.parse(jsonExport.text);
    check(exportedReport.runId === report.runId && exportedReport.results.length > 0, "Search JSON export contains current run");
    const csvExport = await downloadFrom("#export-search-csv");
    check(csvExport.text.includes('"originalRank"') && csvExport.text.includes(report.runId), "Search CSV export contains ranking evidence");

    await page.locator("#max-depth").selectOption("2");
    await page.locator("#max-pages").fill("12");
    await page.locator("#limit").selectOption("3");
    await page.locator("#expand").uncheck();
    const bounded = await submittedSearch(() => page.locator("#search-submit").click());
    check(bounded.results.length <= 3 && bounded.pagesFetched <= 12, "Search applies result limit and page budget");
    await page.locator(".result-details > summary").first().click();
    check(await page.locator(".result-details[open] .tree details").count() > 0, "Result details show a navigable URL tree");
    await page.locator("#mode").selectOption("urls");
    check(await page.locator("#urls-field").isVisible() && (await page.locator("#url-hosts-note").innerText()).includes("huggingface.co"), "Manual mode exposes supported sources");
    await page.locator("#mode").selectOption("demo");

    await page.locator("#tab-count").click();
    const corpus = JSON.parse(await fs.readFile(path.join(root, "data/demo-corpus.json"), "utf8"));
    const fixture = corpus.find((entry) => entry.url.includes("/rag/index")) || corpus.find((entry) => /\bRAG\b/.test(entry.text));
    await page.locator("#demo-page").selectOption(fixture.url);
    await page.locator("#count-keywords").fill("RAG, Python");
    const countWait = page.waitForResponse((response) => response.url().endsWith("/api/count"));
    await page.locator("#count-submit").click();
    const counted = await (await countWait).json();
    await page.locator(".count-cell").first().waitFor();
    check(JSON.stringify(Object.keys(counted.counts)) === JSON.stringify(["RAG", "Python"]), "Stage 0 counts literal supplied terms without synonym expansion");
    check(counted.counts.RAG > 0 && await page.locator(".count-cell").count() === 2, "Literal counts are rendered from API result");

    await page.locator("#tab-evaluation").click();
    check(await page.locator("#question-split").inputValue() === "dev", "Evaluation defaults to development questions");
    check(await page.locator("#questions-body tr").count() === 20 && await page.locator("#rating-question option").count() === 20, "Default question table and rating selector contain only 20 dev questions");
    const prompts = JSON.parse((await downloadFrom("#export-prompts")).text);
    check(prompts.questions.length === 20 && prompts.questions.every((entry) => entry.split === "dev" && entry.plainPrompt && entry.strategyPrompt), "Prompt export respects development-only selection");
    const template = await downloadFrom("#export-evaluation");
    check(template.text.includes('"Q20"') && !template.text.includes('"Q21"'), "Scoring template excludes holdout questions by default");
    await page.locator("#question-split").selectOption("test");
    check(await page.locator("#questions-body tr").count() === 30, "Holdout set is displayed after deliberate selection");
    await page.locator("#question-split").selectOption("dev");
    await page.locator("#rating-question").selectOption("Q01");
    await page.locator("#rating-system").selectOption("rules");
    await page.locator("#rating-seconds").fill("42.5");
    for (let rank = 1; rank <= 5; rank++) await page.locator(`#rank-${rank}`).selectOption(rank <= 3 ? "true" : "false");
    await page.locator('#rating-form button[type="submit"]').click();
    check((await page.locator("#rows-count").innerText()).includes("5 筆"), "Synthetic smoke judgments added as five ranked rows");
    const metricWait = page.waitForResponse((response) => response.url().endsWith("/api/evaluate"));
    await page.locator("#evaluate-submit").click();
    const metrics = await (await metricWait).json();
    await page.locator("#metrics table").waitFor();
    check(metrics.systems[0].precisionAt5 === 0.6 && metrics.systems[0].mrrAt5 === 1 && metrics.systems[0].meanSeconds === 42.5, "Metric API and UI compute expected synthetic smoke outcome");
    check((await page.locator("#metrics").innerText()).includes("60%"), "Precision is shown as a percentage");
    const judgments = await downloadFrom("#download-ratings");
    check(judgments.text.includes('"42.5"') && judgments.text.includes('"Q01"'), "Judgment download preserves observations");

    await page.locator(".import-details > summary").click();
    await page.locator("#ratings-csv").fill("queryId,system,rank,relevant,seconds\nQ01,rules,1,true,10\nQ01,rules,1,false,10");
    await page.locator("#import-ratings").click();
    check((await page.locator("#import-feedback").innerText()).includes("重複"), "Duplicate CSV rows are rejected");
    check((await page.locator("#rows-count").innerText()).includes("5 筆"), "Rejected CSV leaves existing observations intact");
    await page.locator("#ratings-csv").fill("queryId,system,rank,relevant,seconds\nQ02,rules,1,true,12\nQ02,rules,2,false,12");
    await page.locator("#import-ratings").click();
    check((await page.locator("#rows-count").innerText()).includes("2 筆"), "Valid CSV imports replace observations");
    const importedWait = page.waitForResponse((response) => response.url().endsWith("/api/evaluate"));
    await page.locator("#evaluate-submit").click();
    const imported = await (await importedWait).json();
    check(imported.systems[0].precisionAt5 === 0.2 && imported.warnings.length > 0, "Missing top-five slots count as misses and produce a warning");

    const mobile = await context.newPage(); attachErrors(mobile);
    await mobile.setViewportSize({ width: 375, height: 812 });
    await mobile.goto(base, { waitUntil: "networkidle" });
    await mobile.locator('[data-example="RSI"]').click();
    await mobile.locator(".result-card").first().waitFor();
    await noOverflow(mobile, "Mobile search 375px");
    await mobile.screenshot({ path: path.join(work, "simple-ui-mobile.png"), fullPage: true });
    await mobile.locator("#settings-toggle").click();
    await noOverflow(mobile, "Mobile expanded settings");
    await mobile.locator("#tab-count").click();
    await noOverflow(mobile, "Mobile keyword lab");
    await mobile.locator("#tab-evaluation").click();
    await noOverflow(mobile, "Mobile evaluation workspace");
    await mobile.locator("#tab-evaluation").focus();
    await mobile.keyboard.press("ArrowRight");
    check(await mobile.locator("#tab-search").getAttribute("aria-selected") === "true", "Tab keyboard navigation wraps correctly");
    check(errors.length === 0, `No JavaScript or browser console/CSP errors${errors.length ? ": " + errors.join(" | ") : ""}`);
    console.log(`\nBrowser smoke PASS: ${checks} checks. Screenshots: work/simple-ui-desktop.png, work/simple-ui-mobile.png`);
  } catch (error) {
    console.error("Browser errors:", errors);
    console.error("Page excerpt:", (await page.locator("body").innerText().catch(() => "Unavailable")).slice(0, 2000));
    await page.screenshot({ path: path.join(work, "browser-failure.png"), fullPage: true }).catch(() => {});
    throw error;
  } finally { await context.close(); await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });
