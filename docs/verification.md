# 驗證紀錄

驗證環境：Windows、JDK 21.0.12、Edge headless；日期 2026-10-08（Asia/Taipei）。本次更新修正預設示範模式及 AI 搜尋範圍過窄的問題。

## 已執行

- `scripts/test.ps1`：20 個 Java 原始碼檔以 `--release 21 -encoding UTF-8 -Xlint:all -Werror` 編譯成功，612 項檢查通過，新增公開索引、RSI、未知詞保留、預設即時搜尋與摘要來源標示回歸檢查。成功抓過全文但最後補回搜尋摘要的結果，也會維持摘要標示。
- 範圍包括 Boyer–Moore 與參考比對、HTML 清理、BFS／循環／預算、網址範圍、取消操作、關鍵字擴展、排名、外部 API mock、引用檢查、JSON、robots、網路位址限制、CSV、人工評分與 HTTP 整合。
- `scripts/browser-smoke.cjs`：48 項 Edge 瀏覽器檢查通過，包含即時搜尋契約、三種意圖、原文連結、摘要標示、網址樹、Stage 0 原詞計數、下載、評估匯入／計算、預設 dev 題庫、鍵盤切頁與 375px 手機版。此腳本的即時來源回應是模擬資料，用於檢查介面；真實來源另依下一項驗證。
- 不使用 mock，實際在網站搜尋 `RSI`、`AI 地緣政治`、`AI 哲學 意識`、`AI 經濟 就業`，每題取得五筆真實來源連結。某次 Crossref 回應 429，系統保留 Wikipedia 結果並呈現限流警告；公開來源可用性不能由一次測試保證。
- 實際在瀏覽器搜尋 RSI，點「開啟原文」後新分頁開啟 `https://en.wikipedia.org/wiki/Recursive_self-improvement`，確認文章標題為 Recursive self-improvement。介面已改成白底單欄，移除主視覺與側欄，計分預設收合；桌面與 375px 手機無頁面水平溢出；沒有把消歧義頁或書目資料當作已驗證相關性。
- `node --check web/app.js` 與 `node --check scripts/browser-smoke.cjs` 通過；瀏覽器沒有 JavaScript 或 CSP 錯誤。
- 已查看桌面及手機實際截圖，確認無水平頁面溢出或主要元件重疊。
- 指定網址模式實際抓取 `https://docs.typesafe.ai/introduction` 成功：取得 1 頁、產生 1 筆搜尋結果。使用深度 0，子頁未讀取且有截斷標示。這只驗證公開頁面流程，不代表所有網站都可讀取。
- `EvaluationRunner prepare` 僅以自編語料測試 20 題 dev、60 組離線結果及 300 列空白判讀表的產生流程，不是公開索引的成效測試。題庫本次已擴大到 AI 社會議題，舊版結果不得當作新版成績。未自動填入相關性，未執行 test 題。
- 專案安全基本檢查沒有發現已知金鑰或危險 agent 設定；`git diff --check` 通過。這不是完整安全稽核。

## 尚未執行或尚待外部條件

- Jev、OpenAI 的真實 API 呼叫：尚未驗證。其 provider 測試使用 mock，不證明帳號權限、費用、模型品質或實際延遲。
- Google 與 LLM 的正式人工比較、30 題 test 評估：尚未填入真實觀察，因此沒有勝率或品質結論。免金鑰來源是 Wikipedia／Crossref，不代表全網覆蓋。
- Docker 建置與遠端部署：本機未提供 Docker 執行環境，只有 Dockerfile 與部署說明；沒有公開網站。
- GitHub Actions：已建立設定，尚未推送執行；沒有改變 GitHub 帳號設定。
- macOS／Linux 啟動腳本：已提供，尚未在該平台執行。

## 重現瀏覽器檢查

先啟動本機網站。瀏覽器測試是開發時的選配工具，需要 Playwright 與 Chromium／Edge；網站本身不需要 Node 或 Playwright。

```powershell
$env:PLAYWRIGHT_MODULE = '你的 Playwright 模組完整路徑'
$env:BROWSER_PATH = '你的 Chromium 或 Edge 執行檔完整路徑'
node scripts/browser-smoke.cjs
```

瀏覽器測試只連線 localhost，使用合成評分驗證算式；測試中的 60% 等數值不是真實搜尋成績。

## Brave 啟用確認

2026-10-08 使用本機設定完成一次真實 Brave 搜尋，查詢 RSI recursive self-improvement，回傳三筆來源，來源標示為 Brave Search，沒有回退公開索引。第一筆為 Sakana AI 的 RSI Lab 公開文章。此測試關閉關鍵字擴展、Jev 與 RAG；只證明當次金鑰與搜尋介面可用，不代表搜尋品質、長期額度或費用已驗證。金鑰未寫入本紀錄。
