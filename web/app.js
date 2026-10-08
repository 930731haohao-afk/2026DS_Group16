"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const intents = { concept: "概念與背景", compare: "觀點與比較", build: "研究與應用" };
  const systems = { google: "Google", baseline: "候選來源原始排名", rules: "自訂規則", jev: "規則 + Jev", expanded: "規則 + 相關詞擴展", llm_plain: "LLM 一般提問", llm_strategy: "LLM 搜尋策略" };
  const state = { status: null, report: null, questions: [], ratings: [], searching: false, evaluating: false };

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
  }

  function safeUrl(raw) {
    try {
      const url = new URL(String(raw));
      return ["https:", "http:"].includes(url.protocol) ? url : null;
    } catch { return null; }
  }

  function isDemoUrl(raw) { return safeUrl(raw)?.hostname === "demo.local"; }

  function validFetchUrl(raw) {
    const url = safeUrl(raw);
    return Boolean(url && url.protocol === "https:" && !url.username && !url.password && (!url.port || url.port === "443"));
  }

  function externalLink(raw, label, className) {
    const url = safeUrl(raw);
    if (!url || url.hostname === "demo.local") return node("span", className, label);
    const link = node("a", className, label);
    link.href = url.href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    return link;
  }

  function compactNumber(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return "—";
    return n.toLocaleString("zh-TW", { maximumFractionDigits: 2 });
  }

  function showError(id, text) {
    $(id).textContent = text || "";
    $(id).hidden = !text;
  }

  async function api(path, body, timeout = 120000) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(path, {
        method: body === undefined ? "GET" : "POST",
        headers: body === undefined ? { Accept: "application/json" } : { "Content-Type": "application/json", Accept: "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
        credentials: "same-origin",
        cache: "no-store"
      });
      let result;
      try { result = await response.json(); }
      catch { throw new Error("伺服器未傳回有效資料，請確認本機服務仍在執行。"); }
      if (!response.ok) throw new Error(result.message || result.error || `請求失敗（${response.status}），請稍後再試。`);
      return result;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("這次處理超過等待時間。可減少網頁總預算或走訪深度後重試。");
      if (error instanceof TypeError) throw new Error("無法連上本機服務。請確認伺服器已啟動，再重新整理頁面。");
      throw error;
    } finally { window.clearTimeout(timer); }
  }

  function activateTab(name, focus = false) {
    const names = ["search", "count", "evaluation"];
    if (!names.includes(name)) return;
    names.forEach((entry) => {
      const active = entry === name;
      $(`panel-${entry}`).hidden = !active;
      $(`tab-${entry}`).classList.toggle("active", active);
      $(`tab-${entry}`).setAttribute("aria-selected", String(active));
      $(`tab-${entry}`).tabIndex = active ? 0 : -1;
    });
    if (focus) $(`tab-${name}`).focus();
  }

  document.querySelectorAll("[data-tab]").forEach((tab) => {
    tab.addEventListener("click", () => activateTab(tab.dataset.tab));
    tab.addEventListener("keydown", (event) => {
      const names = ["search", "count", "evaluation"];
      const index = names.indexOf(tab.dataset.tab);
      let next = null;
      if (event.key === "ArrowRight") next = names[(index + 1) % names.length];
      if (event.key === "ArrowLeft") next = names[(index + names.length - 1) % names.length];
      if (event.key === "Home") next = names[0];
      if (event.key === "End") next = names[names.length - 1];
      if (next) { event.preventDefault(); activateTab(next, true); }
    });
  });
  document.querySelectorAll("[data-go-tab]").forEach((button) => button.addEventListener("click", () => {
    activateTab(button.dataset.goTab, true);
    window.scrollTo({ top: 0, behavior: "auto" });
  }));

  $("settings-toggle").addEventListener("click", () => {
    const open = $("search-settings").hidden;
    $("search-settings").hidden = !open;
    $("settings-toggle").setAttribute("aria-expanded", String(open));
    $("settings-toggle").textContent = open ? "收起設定" : "搜尋設定";
  });

  function updateModeNotice() {
    const mode = $("mode").value;
    $("urls-field").hidden = mode !== "urls";
    $("urls").required = mode === "urls";
    $("url-hosts-note").hidden = mode !== "urls";
    const allowed = state.status?.allowedFetchHosts;
    $("url-hosts-note").textContent = Array.isArray(allowed) && allowed.length
      ? `目前可讀取來源：${allowed.join("、")}。來源清單可由專案設定調整。`
      : "公開網頁須位於伺服器支援的來源清單，且使用 HTTPS。";
    const text = mode === "demo"
      ? (state.status?.fixtureNotice || "目前使用自編示範資料，供驗證搜尋流程與資料結構；內容不是即時網頁，排序不代表已勝過 Google。")
      : mode === "live"
        ? (state.status?.liveNotice || "從已連接的公開來源搜尋 AI 相關議題。結果提供來源連結；只有搜尋摘要時，計數與排名依據摘要，不代表已讀取網頁全文。")
        : "從你提供的公開 HTTPS 網址開始，沿同站連結有限走訪。請使用搜尋設定中列出的支援來源；無法取得的頁面會列出提醒。";
    $("data-notice").querySelector("p").textContent = text;
  }
  $("mode").addEventListener("change", updateModeNotice);

  function emptyState(container, title, message) {
    const wrapper = node("div", "empty-state");
    wrapper.append(node("h3", "", title), node("p", "", message));
    container.replaceChildren(wrapper);
  }

  function searchAlternatives(query) {
    const links = node("div", "search-alternatives");
    const text = query.trim();
    if (!text) return links;
    for (const [base, label] of [["https://www.google.com/search", "到 Google 搜尋相同問題 ↗"], ["https://www.bing.com/search", "到 Bing 搜尋相同問題 ↗"]]) {
      const url = new URL(base); url.searchParams.set("q", text);
      links.append(externalLink(url.href, label, "quiet-button"));
    }
    return links;
  }

  function renderLoading() {
    $("results").replaceChildren(node("p", "muted", "搜尋中，請稍候。"));
  }

  function renderWarnings(container, warnings) {
    container.replaceChildren();
    const values = Array.isArray(warnings) ? warnings.filter(Boolean) : [];
    container.hidden = values.length === 0;
    if (!values.length) return;
    const box = node("div", "warning-list");
    values.forEach((warning) => box.append(node("p", "", String(warning))));
    container.append(box);
  }

  function countsText(counts) {
    if (!counts || typeof counts !== "object") return "無關鍵字計數";
    const entries = Object.entries(counts);
    return entries.length ? entries.map(([keyword, count]) => `${keyword}：${compactNumber(count)}`).join(" · ") : "無關鍵字計數";
  }

  function renderTree(tree, depth = 0) {
    if (!tree || depth > 8) return node("p", "small muted", "沒有可顯示的節點。");
    const details = node("details");
    if (depth === 0) details.open = true;
    const title = tree.title || safeUrl(tree.url)?.pathname || "未命名網頁";
    const snippet = tree.status === "snippet";
    const status = tree.status === "error" ? " · 讀取失敗" : tree.status === "skipped" ? " · 未走訪" : snippet ? " · 僅搜尋摘要" : "";
    details.append(node("summary", tree.status === "error" ? "status-error" : "", `${title}${status}`));
    const info = node("div", "tree-info");
    info.append(externalLink(tree.url, isDemoUrl(tree.url) ? "自編示範頁面" : (tree.url || "未提供網址")));
    info.append(node("p", "", snippet
      ? `尚未讀取全文或走訪子頁 · 摘要分數 ${compactNumber(tree.pageScore)} · 摘要計數：${countsText(tree.counts)}`
      : `深度 ${compactNumber(tree.depth ?? depth)} · 頁面分數 ${compactNumber(tree.pageScore)} · ${countsText(tree.counts)}`));
    if (tree.warning) info.append(node("p", "status-error", tree.warning));
    details.append(info);
    if (Array.isArray(tree.children)) tree.children.forEach((child) => details.append(renderTree(child, depth + 1)));
    return details;
  }

  function renderResult(result, index) {
    const card = node("article", "result-card");
    const top = node("div", "result-top");
    const source = node("div", "source-line");
    const demo = result.contentStatus === "demo" || isDemoUrl(result.url);
    const snippet = result.contentStatus === "snippet" || result.tree?.status === "snippet";
    source.append(demo ? node("span", "", "自編示範") : externalLink(result.url, safeUrl(result.url)?.hostname || "來源未提供"));
    top.append(source);
    const title = node("h3");
    title.append(demo ? node("span", "", result.title || "示範頁面") : externalLink(result.url, result.title || "未命名網頁"));
    card.append(top, title, node("p", "result-snippet", result.snippet || "此網頁沒有可顯示的內容片段。"));
    const access = node("div", "result-access");
    access.append(node("span", "content-status", demo ? "課堂示範內容，沒有外部原文" : snippet ? "僅搜尋摘要 · 未讀取全文" : "已取得網頁內容"));
    if (!demo && safeUrl(result.url)) access.append(externalLink(result.url, "開啟原文 ↗", "open-source"));
    card.append(access);
    const bottom = node("div", "result-bottom");
    if (Number(result.originalRank) > 0) bottom.append(node("span", "rank-move", `原始 #${result.originalRank} → #${index + 1}`));
    Object.entries(result.counts || {}).slice(0, 5).forEach(([keyword, count]) => {
      const chip = node("span", "keyword-chip", keyword);
      chip.append(node("b", "", compactNumber(count)));
      bottom.append(chip);
    });
    const score = node("span", "result-score", "綜合分數");
    score.append(node("b", "", compactNumber(result.finalScore)));
    bottom.append(score);
    const details = node("details", "result-details");
    details.append(node("summary", "", snippet ? "查看摘要計分與來源狀態" : "查看計分與網址樹"));
    const content = node("div", "details-content");
    content.append(bottom);
    if (result.source) content.append(node("p", "small muted", `資料來源：${result.source}`));
    const grid = node("div", "score-grid");
    [["規則分數", result.ruleScore], ["需求分數", result.intentScore], ["Jev 分數", result.jevScore]].forEach(([label, value]) => {
      const item = node("div", "score-item", label);
      item.append(node("b", "", value === null || value === undefined ? "未使用" : compactNumber(value)));
      grid.append(item);
    });
    content.append(grid, node("p", "small muted", `${snippet ? "搜尋摘要中的次數（非全文）" : demo ? "示範內容中的次數" : "已取得內容中的次數"}：${countsText(result.counts)}`));
    if (result.tree) {
      const treeContainer = node("div", "tree");
      treeContainer.append(renderTree(result.tree));
      content.append(treeContainer);
    }
    details.append(content);
    card.append(details);
    return card;
  }

  function renderAnswer(answer) {
    const container = $("answer-card");
    container.replaceChildren();
    container.hidden = !answer || answer.mode === "none" || !answer.text;
    if (container.hidden) return;
    const generated = answer.mode === "generated";
    container.append(node("span", "answer-label", generated ? "RAG · 依來源生成" : "SOURCE EXTRACT · 來源摘錄"));
    container.append(node("h3", "", generated ? "來源回答" : "來源摘錄"));
    container.append(node("p", "", answer.text));
    const sources = node("div", "citation-list");
    (Array.isArray(answer.citations) ? answer.citations : []).forEach((citation, i) => {
      sources.append(externalLink(citation.url, `[${citation.id || i + 1}] ${citation.title || "來源"}`));
    });
    container.append(sources);
  }

  function renderRunDetails(report) {
    const container = $("run-details");
    container.hidden = false;
    container.open = false;
    container.replaceChildren(node("summary", "", "搜尋資訊"));
    const list = node("dl");
    const entries = [
      ["候選來源", report.baseline?.provider || report.mode || "—"],
      ["搜尋目的", intents[report.intent] || report.intent],
      ["實際搜尋字串", report.searchQuery || report.query || "—"],
      ["原始關鍵字", (report.keywords || []).join(" · ") || "—"],
      ["本輪加入的相關詞", (report.expandedKeywords || []).join(" · ") || "未加入"],
      ["Jev 執行狀態", report.jev?.applied ? "已套用模型評分" : report.jev?.requested ? "未套用，使用規則結果" : "使用規則排名"],
      ["全文讀取", `${compactNumber(report.pagesFetched)} 頁${report.mode === "demo" ? "（示範資料）" : ""}${report.truncated ? " · 已達本輪限制" : ""}`],
      ["僅搜尋摘要", `${(report.results || []).filter((result) => result.contentStatus === "snippet" || result.tree?.status === "snippet").length} 筆結果`]
    ];
    if (report.pagesProcessed !== undefined) entries.push(["已處理來源（含摘要）", `${compactNumber(report.pagesProcessed)} 筆`]);
    entries.forEach(([label, value]) => list.append(node("dt", "", label), node("dd", "", value)));
    container.append(list);
    if (report.baseline?.googleComparisonUrl) {
      container.append(externalLink(report.baseline.googleComparisonUrl, "使用相同問題到 Google 比較 ↗"));
    }
  }

  function renderReport(report) {
    const results = Array.isArray(report.results) ? report.results : [];
    $("result-heading").textContent = `${report.query || "你的問題"} 的搜尋結果`;
    $("search-status").textContent = `${results.length} 筆結果 · ${intents[report.intent] || "相關內容"} · ${report.mode === "demo" ? "示範資料" : `全文讀取 ${compactNumber(report.pagesFetched)} 頁`} · ${compactNumber(Number(report.elapsedMs || 0) / 1000)} 秒`;
    renderWarnings($("search-warnings"), [...new Set([...(report.warnings || []), ...(report.sourceWarnings || [])])]);
    renderAnswer(report.answer);
    $("results").replaceChildren();
    if (results.length) results.forEach((result, index) => $("results").append(renderResult(result, index)));
    else {
      emptyState($("results"), "這次沒有取得可用結果", report.mode === "demo"
        ? "目前選用的是有限的自編示範資料。請在搜尋設定改用即時搜尋，查找其他 AI 議題。"
        : "這不表示主題沒有資料。請查看上方來源或連線提醒，稍後重試、補充關鍵字，或直接開啟外部搜尋。");
      $("results").append(searchAlternatives(report.searchQuery || report.query || ""));
    }
    renderRunDetails(report);
    $("export-search-json").disabled = false;
    $("export-search-csv").disabled = !results.length;
  }

  async function search() {
    if (state.searching) return;
    const query = $("query").value.trim();
    if (!query) { $("query").focus(); return; }
    if (!$("search-form").reportValidity()) return;
    const mode = $("mode").value;
    const urls = mode === "urls" ? $("urls").value.split(/\r?\n/).map((url) => url.trim()).filter(Boolean) : [];
    if (mode === "urls" && (!urls.length || urls.length > 10 || urls.some((url) => !validFetchUrl(url)))) {
      showError("search-error", "請每行提供一個不含帳密的 HTTPS 網址，最多 10 個，使用標準連接埠。");
      $("urls").focus(); return;
    }
    const body = {
      query,
      intent: document.querySelector('input[name="intent"]:checked').value,
      mode, urls,
      maxDepth: Number($("max-depth").value), maxPages: Number($("max-pages").value), limit: Number($("limit").value),
      expand: $("expand").checked, useJev: !$("use-jev").disabled && $("use-jev").checked,
      answer: !$("use-answer").disabled && $("use-answer").checked
    };
    state.searching = true;
    state.report = null;
    $("search-submit").disabled = true;
    $("search-submit").textContent = "搜尋中…";
    $("export-search-json").disabled = true;
    $("export-search-csv").disabled = true;
    $("search-status").textContent = mode === "live" ? "正在搜尋公開來源，取得資料並計算排名…" : "正在讀取資料、建立網址樹並計算排名…";
    $("result-heading").textContent = "搜尋中";
    $("results").setAttribute("aria-busy", "true");
    $("answer-card").hidden = true;
    $("search-warnings").hidden = true;
    $("run-details").hidden = true;
    showError("search-error", "");
    renderLoading();
    try {
      const report = await api("/api/search", body);
      state.report = report;
      renderReport(report);
    } catch (error) {
      showError("search-error", error.message);
      $("search-status").textContent = "這次搜尋未完成，請調整設定後再試。";
      $("result-heading").textContent = "搜尋結果";
      emptyState($("results"), "目前無法完成搜尋", "請稍後重試或查看連線提醒。你也可以直接前往外部搜尋，查找相同問題。");
      $("results").append(searchAlternatives(query));
    } finally {
      state.searching = false;
      $("search-submit").disabled = false;
      $("search-submit").textContent = "搜尋";
      $("results").setAttribute("aria-busy", "false");
    }
  }

  $("search-form").addEventListener("submit", (event) => { event.preventDefault(); search(); });
  document.querySelectorAll("[data-example]").forEach((button) => button.addEventListener("click", () => {
    if (state.searching) return;
    $("query").value = button.dataset.example;
    document.querySelector(`input[name="intent"][value="${button.dataset.intent}"]`).checked = true;
    search();
  }));

  function csvCell(value) {
    let text = value === null || value === undefined ? "" : String(value);
    if (/^[\s]*[=+\-@]/.test(text)) text = `'${text}`;
    return `"${text.replace(/"/g, '""')}"`;
  }
  function csvString(headers, rows) {
    return "\ufeff" + [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n");
  }
  function download(filename, content, type) {
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const link = node("a");
    link.href = url; link.download = filename;
    document.body.append(link); link.click(); link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  $("export-search-json").addEventListener("click", () => {
    if (state.report) download("知途AI_搜尋紀錄.json", JSON.stringify(state.report, null, 2), "application/json;charset=utf-8");
  });
  $("export-search-csv").addEventListener("click", () => {
    if (!state.report) return;
    const report = state.report;
    const rows = report.results.map((r, i) => [report.runId, report.query, report.searchQuery || report.query, report.intent, report.mode, i + 1, r.originalRank, r.title, r.url, r.contentStatus || (isDemoUrl(r.url) ? "demo" : r.tree?.status === "snippet" ? "snippet" : "fetched"), r.ruleScore, r.intentScore, r.jevScore, r.finalScore, JSON.stringify(r.counts || {})]);
    download("知途AI_搜尋排名.csv", csvString(["runId", "query", "searchQuery", "intent", "mode", "rank", "originalRank", "title", "url", "contentStatus", "ruleScore", "intentScore", "jevScore", "finalScore", "counts"], rows), "text/csv;charset=utf-8");
  });

  const countHostNote = node("p", "settings-help");
  countHostNote.hidden = true;
  $("count-url-field").append(countHostNote);
  $("count-url-field").firstChild.textContent = "公開 HTTPS 網頁網址（限支援來源）";
  $("count-url").placeholder = "https://huggingface.co/learn/llm-course/chapter1/1";
  $("count-mode").addEventListener("change", () => {
    const live = $("count-mode").value === "live";
    $("demo-page-field").hidden = live;
    $("count-url-field").hidden = !live;
    $("count-url").required = live;
    countHostNote.hidden = !live;
    const allowed = state.status?.allowedFetchHosts;
    countHostNote.textContent = Array.isArray(allowed) && allowed.length
      ? `目前可讀取：${allowed.join("、")}`
      : "需使用伺服器支援來源的公開 HTTPS 網頁。";
  });
  $("count-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const mode = $("count-mode").value;
    const url = mode === "demo" ? $("demo-page").value : $("count-url").value.trim();
    if (!url) { emptyState($("count-result"), "尚未選擇頁面", "請先選擇示範頁面，或輸入公開網頁網址。"); return; }
    if (mode === "live" && !validFetchUrl(url)) { emptyState($("count-result"), "請使用完整的 HTTPS 網址", "網頁需使用標準連接埠且不含帳密，並位於伺服器支援的來源清單。"); return; }
    $("count-submit").disabled = true;
    $("count-result").replaceChildren(node("p", "small muted", "正在讀取文字並計算關鍵字…"));
    try {
      const result = await api("/api/count", { mode, url, keywords: $("count-keywords").value });
      const container = $("count-result");
      container.replaceChildren();
      const title = node("h3"); title.append(externalLink(result.url, result.title || "網頁計數結果"));
      container.append(title, node("p", "small muted", `${result.synthetic ? "自編示範資料 · " : ""}${compactNumber(result.totalCharacters)} 個字元 · ${result.algorithm || "Boyer–Moore"} 字串比對`));
      const grid = node("div", "count-grid");
      Object.entries(result.counts || {}).forEach(([keyword, value]) => {
        const cell = node("div", "count-cell");
        cell.append(node("b", "", compactNumber(value)), node("span", "", keyword));
        grid.append(cell);
      });
      container.append(grid, node("p", "small muted", "0 次表示本次取得的文字中沒有符合比對規則的詞。它不等於該網頁與主題完全無關。"));
    } catch (error) {
      const message = node("div", "error-box", error.message);
      message.setAttribute("role", "alert");
      $("count-result").replaceChildren(message);
    } finally { $("count-submit").disabled = false; }
  });

  function isDev(question) { return ["dev", "development", "train"].includes(String(question.split).toLowerCase()); }
  function selectedQuestions() {
    const split = $("question-split").value;
    const intent = $("question-intent").value;
    return state.questions.filter((q) => (split === "all" || (split === "dev" ? isDev(q) : !isDev(q))) && (intent === "all" || q.intent === intent));
  }
  function renderQuestions() {
    const questions = selectedQuestions();
    $("questions-body").replaceChildren();
    questions.forEach((question) => {
      const row = node("tr");
      row.append(node("td", "", question.id), node("td", "", question.query), node("td", "", intents[question.intent] || question.intent));
      const use = node("td"); use.append(node("span", "mini-tag", isDev(question) ? "開發用" : "保留測試")); row.append(use);
      const criteria = node("td");
      const details = node("details"); details.append(node("summary", "", "展開標準"));
      const expected = Array.isArray(question.expected) ? question.expected.join("；") : String(question.expected || "依題目需求判讀");
      details.append(node("p", "", expected));
      if (question.answerable === false) details.append(node("p", "muted", "資料不足／無法確定題：應表達不確定，不能編造。"));
      criteria.append(details); row.append(criteria); $("questions-body").append(row);
    });
    if (!questions.length) {
      const row = node("tr"), cell = node("td", "muted", "目前篩選條件沒有題目。"); cell.colSpan = 5; row.append(cell); $("questions-body").append(row);
    }
    $("question-count").textContent = `顯示 ${questions.length} / ${state.questions.length} 題`;
    const previousSelection = $("rating-question").value;
    $("rating-question").replaceChildren();
    questions.forEach((question) => {
      const option = node("option", "", `${question.id} · ${question.query}`); option.value = question.id; $("rating-question").append(option);
    });
    if (questions.some((q) => String(q.id) === previousSelection)) $("rating-question").value = previousSelection;
    $("export-evaluation").disabled = !questions.length;
    $("export-prompts").disabled = !questions.length;
  }
  $("question-split").addEventListener("change", renderQuestions);
  $("question-intent").addEventListener("change", renderQuestions);

  function populateEvaluation(data) {
    state.questions = Array.isArray(data.questions) ? data.questions : [];
    $("question-total").textContent = state.questions.length;
    $("dev-total").textContent = state.questions.filter(isDev).length;
    $("test-total").textContent = state.questions.filter((q) => !isDev(q)).length;
    $("evaluation-instructions").textContent = typeof data.instructions === "string" ? data.instructions : Array.isArray(data.instructions) ? data.instructions.join(" ") : "";
    renderQuestions();
  }

  for (let rank = 1; rank <= 5; rank++) {
    const label = node("label", "field", `第 ${rank} 筆`);
    const select = node("select"); select.id = `rank-${rank}`; select.required = true;
    [["", "請判讀"], ["true", "相關"], ["false", "不相關"]].forEach(([value, title]) => {
      const option = node("option", "", title); option.value = value; select.append(option);
    });
    label.append(select); $("rating-ranks").append(label);
  }

  function ratingsCsv(rows) {
    return csvString(["queryId", "system", "rank", "relevant", "seconds"], rows.map((r) => [r.queryId, r.system, r.rank, r.relevant, r.seconds]));
  }
  $("export-evaluation").addEventListener("click", () => {
    const rows = [];
    selectedQuestions().forEach((q) => ["google", "baseline", "rules", "jev", "expanded"].forEach((system) => {
      for (let rank = 1; rank <= 5; rank++) rows.push({ queryId: q.id, system, rank, relevant: "", seconds: "" });
    }));
    download("知途AI_評分空白表.csv", ratingsCsv(rows), "text/csv;charset=utf-8");
  });
  $("export-prompts").addEventListener("click", () => {
    const promptSet = {
      instructions: "分別使用一般與策略提示詞。記錄模型、日期、是否啟用網路搜尋及完整輸出。不要把示範結果當成實測。",
      questions: selectedQuestions().map((q) => ({
        id: q.id, split: q.split, intent: q.intent, query: q.query,
        plainPrompt: q.query,
        strategyPrompt: `請針對下列 AI 知識問題尋找或整理資料。使用者的目的為「${intents[q.intent] || q.intent}」。\n問題：${q.query}\n請優先使用直接相關的官方文件、原始論文或可靠教學。先確認問題中的版本與限制；有需要時加入少量相關詞。以繁體中文回答，附可核對的來源連結，說明適用情境與限制。若有程式範例需求，確認範例能支援解釋。若無法上網、缺少證據或資料不足，請明確說明，不要編造來源與結論。`,
        record: { model: "", date: "", webSearchEnabled: "", plainOutput: "", strategyOutput: "", notes: "" }
      }))
    };
    download("知途AI_LLM比較提示詞.json", JSON.stringify(promptSet, null, 2), "application/json;charset=utf-8");
  });

  function updateRatingState() {
    const count = state.ratings.length;
    const groups = new Set(state.ratings.map((r) => `${r.queryId}\u0000${r.system}`)).size;
    $("rows-count").textContent = `${count} 筆 · ${groups} 組`;
    $("download-ratings").disabled = !count;
    $("clear-ratings").disabled = !count;
    $("evaluate-submit").disabled = !count || state.evaluating;
    $("metrics").replaceChildren(node("p", "metric-placeholder", count ? "紀錄已更新，請按「計算指標」取得最新結果。" : "填入觀察後，才會顯示計算結果。"));
  }
  $("rating-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!state.questions.length) { $("rating-feedback").textContent = "題庫尚未載入，請確認本機服務。"; return; }
    const queryId = $("rating-question").value;
    const system = $("rating-system").value;
    const secondsInput = $("rating-seconds").value;
    const seconds = secondsInput === "" ? undefined : Number(secondsInput);
    const ratings = [];
    for (let rank = 1; rank <= 5; rank++) {
      if (!["true", "false"].includes($(`rank-${rank}`).value)) return;
      const row = { queryId, system, rank, relevant: $(`rank-${rank}`).value === "true" };
      if (seconds !== undefined) row.seconds = seconds;
      ratings.push(row);
    }
    state.ratings = state.ratings.filter((r) => !(String(r.queryId) === queryId && r.system === system)).concat(ratings);
    updateRatingState();
    $("rating-feedback").textContent = `已保存 ${queryId}／${systems[system] || system} 的前五筆判讀。請下載紀錄以免重新整理後遺失。`;
  });
  $("download-ratings").addEventListener("click", () => download("知途AI_實驗觀察.csv", ratingsCsv(state.ratings), "text/csv;charset=utf-8"));
  $("clear-ratings").addEventListener("click", () => {
    if (!state.ratings.length || !window.confirm("清除這個分頁內的所有評估紀錄？尚未下載的紀錄將無法還原。")) return;
    state.ratings = []; updateRatingState(); $("rating-feedback").textContent = "評估紀錄已清除。";
  });

  function parseCsv(text) {
    const rows = []; let row = [], field = "", quoted = false;
    const value = text.replace(/^\ufeff/, "");
    for (let i = 0; i < value.length; i++) {
      const char = value[i];
      if (char === '"') {
        if (quoted && value[i + 1] === '"') { field += '"'; i++; }
        else if (quoted) quoted = false;
        else if (!field) quoted = true;
        else throw new Error("CSV 引號格式不正確，請使用下載的評分表。");
      } else if (char === "," && !quoted) { row.push(field); field = ""; }
      else if ((char === "\n" || char === "\r") && !quoted) {
        if (char === "\r" && value[i + 1] === "\n") i++;
        row.push(field); if (row.some((entry) => entry.trim())) rows.push(row); row = []; field = "";
      } else field += char;
    }
    if (quoted) throw new Error("CSV 有未閉合的引號。");
    row.push(field); if (row.some((entry) => entry.trim())) rows.push(row);
    return rows;
  }

  function importRatings(text) {
    if (!text.trim()) throw new Error("請選擇 CSV 檔案或貼上內容。");
    if (text.length > 1500000) throw new Error("CSV 太大，請限制在 1.5 MB 以內。");
    const parsed = parseCsv(text);
    if (parsed.length < 2) throw new Error("CSV 必須有欄名與至少一筆已判讀資料。");
    const headers = parsed[0].map((value) => value.trim());
    ["queryId", "system", "rank", "relevant"].forEach((key) => {
      if (!headers.includes(key)) throw new Error(`CSV 缺少 ${key} 欄位。`);
    });
    const records = [];
    const known = new Set(state.questions.map((q) => String(q.id)));
    const keys = new Set();
    parsed.slice(1).forEach((values, index) => {
      const item = Object.fromEntries(headers.map((key, i) => [key, (values[i] || "").trim()]));
      if (!item.relevant) return;
      const rank = Number(item.rank);
      const relevance = item.relevant.toLowerCase();
      if (!known.has(item.queryId)) throw new Error(`第 ${index + 2} 行的 queryId 不在目前題庫中。`);
      if (!item.system || item.system.length > 80) throw new Error(`第 ${index + 2} 行的 system 不正確。`);
      if (!Number.isInteger(rank) || rank < 1 || rank > 5) throw new Error(`第 ${index + 2} 行的 rank 必須是 1 至 5。`);
      if (!["true", "false", "1", "0", "yes", "no", "相關", "不相關"].includes(relevance)) throw new Error(`第 ${index + 2} 行的 relevant 請填 true 或 false。`);
      const key = `${item.queryId}\u0000${item.system}\u0000${rank}`;
      if (keys.has(key)) throw new Error(`第 ${index + 2} 行與前面的題目、系統、排名重複。`);
      keys.add(key);
      const record = { queryId: item.queryId, system: item.system, rank, relevant: ["true", "1", "yes", "相關"].includes(relevance) };
      if (item.seconds) {
        const seconds = Number(item.seconds);
        if (!Number.isFinite(seconds) || seconds < 0 || seconds > 86400) throw new Error(`第 ${index + 2} 行的 seconds 必須介於 0 至 86400。`);
        record.seconds = seconds;
      }
      records.push(record);
    });
    if (!records.length) throw new Error("CSV 尚未填寫任何 relevant 判讀。請先填入 true 或 false。");
    if (records.length > 5000) throw new Error("一次最多匯入 5,000 筆判讀。");
    state.ratings = records; updateRatingState();
    $("import-feedback").textContent = `已載入 ${records.length} 筆真實判讀；空白判讀已略過。`;
  }
  $("import-ratings").addEventListener("click", async () => {
    const file = $("ratings-file").files[0];
    try {
      if (file && file.size > 1500000) throw new Error("CSV 太大，請限制在 1.5 MB 以內。");
      const text = file ? await file.text() : $("ratings-csv").value;
      importRatings(text);
    } catch (error) { $("import-feedback").textContent = error.message; }
  });

  function renderMetrics(metrics) {
    const container = $("metrics"); container.replaceChildren();
    const results = Array.isArray(metrics.systems) ? metrics.systems : [];
    if (!results.length) {
      container.append(node("p", "metric-placeholder", "伺服器沒有傳回可計算的組別。請檢查題號、排名與相關性欄位。"));
    } else {
      const scroll = node("div", "table-scroll metrics-table");
      const table = node("table", "data-table");
      const head = node("thead"), headings = node("tr");
      ["系統", "題數", "前五筆相關比例", "MRR@5", "平均秒數"].forEach((title) => { const th = node("th", "", title); th.scope = "col"; headings.append(th); });
      head.append(headings); table.append(head); const body = node("tbody");
      results.forEach((result) => {
        const row = node("tr");
        const precision = result.precisionAt5 === undefined || result.precisionAt5 === null ? "—" : `${compactNumber(result.precisionAt5 * 100)}%`;
        const time = result.meanSeconds === undefined || result.meanSeconds === null ? "未記錄" : compactNumber(result.meanSeconds);
        [systems[result.system] || result.system, result.queries, precision, compactNumber(result.mrrAt5), time].forEach((value) => row.append(node("td", "", value)));
        body.append(row);
      });
      table.append(body); scroll.append(table); container.append(scroll);
      container.append(node("p", "small muted", "MRR@5：第一筆相關結果排名的倒數，再取平均；越接近 1 越好。缺少的前五筆位置按不相關計算。各系統應使用相同題目，才適合比較。"));
    }
    if (Array.isArray(metrics.warnings) && metrics.warnings.length) {
      const warnings = node("div"); renderWarnings(warnings, metrics.warnings); container.append(warnings);
    }
  }
  $("evaluate-submit").addEventListener("click", async () => {
    if (!state.ratings.length || state.evaluating) return;
    state.evaluating = true; $("evaluate-submit").disabled = true;
    $("metrics").replaceChildren(node("p", "metric-placeholder", "正在計算指標…"));
    try { renderMetrics(await api("/api/evaluate", { rows: state.ratings })); }
    catch (error) { const message = node("div", "error-box", error.message); message.setAttribute("role", "alert"); $("metrics").replaceChildren(message); }
    finally { state.evaluating = false; $("evaluate-submit").disabled = !state.ratings.length; }
  });

  async function initialize() {
    emptyState($("results"), "輸入問題開始搜尋", "可搜尋 AI 技術、政策、哲學、經濟等議題。點擊結果標題或「開啟原文」閱讀來源。");
    const loaded = await Promise.allSettled([api("/api/status", undefined, 12000), api("/api/demo-pages", undefined, 12000), api("/api/evaluation", undefined, 12000)]);
    const [status, pages, evaluation] = loaded;
    if (status.status === "fulfilled") {
      state.status = status.value;
      const caps = state.status.capabilities || {};
      $("mode").querySelector('option[value="live"]').disabled = !caps.liveSearch;
      $("mode").querySelector('option[value="live"]').textContent = caps.liveSearch ? `即時搜尋（${state.status.liveProvider || "公開網頁"}）` : "即時搜尋（目前無法使用）";
      if (state.status.modeDefault === "live" && caps.liveSearch) $("mode").value = "live";
      else if (state.status.modeDefault === "demo") $("mode").value = "demo";
      $("use-jev").disabled = !caps.jev;
      $("jev-state").textContent = caps.jev ? "可使用" : "未設定";
      $("use-answer").disabled = !caps.answer;
      $("answer-state").textContent = caps.answer ? "可使用" : "未設定";
      $("max-pages").max = String(state.status.limits?.maxPages || 40);
      $("connection-state").textContent = "本機服務已連線";
      updateModeNotice();
    } else {
      $("connection-state").textContent = "本機服務未連線";
      $("data-notice").querySelector("p").textContent = "目前無法連上本機服務。請依操作說明啟動伺服器後，重新整理此頁。";
      showError("search-error", status.reason.message);
    }
    if (pages.status === "fulfilled") {
      $("demo-page").replaceChildren();
      (pages.value.pages || []).forEach((page) => {
        const option = node("option", "", page.title || page.url); option.value = page.url; $("demo-page").append(option);
      });
      if (!$("demo-page").options.length) { const option = node("option", "", "示範資料集沒有頁面"); option.value = ""; $("demo-page").append(option); }
    } else { $("demo-page").replaceChildren(node("option", "", "無法載入示範頁面")); $("demo-page").options[0].value = ""; }
    if (evaluation.status === "fulfilled") populateEvaluation(evaluation.value);
    else showError("evaluation-error", evaluation.reason.message);
  }
  initialize();
})();
