# 外部服務與測試邊界

更新日期：2026-10-08。預設公開索引已用真實網路請求驗證；Brave 已用使用者設定的金鑰完成一次真實 Web Search 驗證；Jev、OpenAI adapter 仍僅用自製回應資料測試，**尚未驗證其帳戶權限、模型品質、費用或延遲**。不設定金鑰也能查詢公開百科與研究書目。金鑰只放伺服器環境變數，不能放在前端、Git 或評估匯出檔。

## 預設免金鑰公開索引

`PublicSearch` 並行查詢 Wikipedia Action API 與 Crossref REST API，各來源最多等待 10 秒，整批共享 11 秒期限，回應限制 1 MiB。Wikipedia 依中文／英文選擇站點，以 `generator=search` 配合 `info|extracts` 取得標題、原始網址與導言摘錄；Crossref 取得標題、DOI、出版者與年份，不複製出版社全文或摘要。

兩種索引的資料交錯合併，再依查詢內容重排。它們是公開百科與學術索引，不是全網索引。任意 AI 相關詞都能送入查詢，沒有 LLM／RAG／Agent 主題白名單；中文 AI 用詞與 RSI 的語境會做小幅正規化，實際送出的查詢記錄於回應。

來源網址直接指向百科條目或 DOI。這些結果標記為 `contentStatus=snippet`，計數基於標題與摘要／書目，`pagesFetched` 不會把它們當成已取得的全文。即使全文抓取失敗或查詢詞與來源用詞不同，仍保留真實搜尋命中，不能因本機詞彙表不認識就丟棄。

部分來源限流時保留另一來源結果並顯示警告；全部失敗則顯示失敗與外部搜尋入口，不使用示範資料替代。公開 API 沒有可用性保證，搜尋相關性仍需人工評估。

官方文件：[MediaWiki Search](https://www.mediawiki.org/wiki/API:Search)、[TextExtracts](https://www.mediawiki.org/wiki/Extension:TextExtracts#API)、[Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)。

## 設定

| 環境變數 | 用途 |
|---|---|
| `BRAVE_API_KEY` | 啟用 Brave 即時候選網址搜尋。 |
| `TYPESAFE_API_KEY` | 啟用 Jev 重排。 |
| `TYPESAFE_MODEL` | 預設 `jev-latest`；正式實驗應在供應商可用範圍內固定模型版本。 |
| `OPENAI_API_KEY` | 文字回答金鑰。 |
| `OPENAI_MODEL` | 必須明確設定帳戶可使用的 Responses 模型；沒有預設模型。 |
| `PROVIDER_TIMEOUT_SECONDS` | 單次請求上限，預設 15 秒，範圍 3 至 30 秒。 |

各項能力依設定是否齊全顯示；這不是金鑰有效性檢查。使用者在介面選擇外部功能時，才會呼叫對應服務，可能產生費用。正式使用前請自行查核帳戶與供應商目前的計價。系統不自動重試付費請求，避免不明情況下重複呼叫。

## Brave 候選搜尋

使用固定的 `GET https://api.search.brave.com/res/v1/web/search`，透過 `X-Subscription-Token` 驗證；查詢以 URL 編碼傳入 `q`，`count` 最多 20。從 `web.results` 取出網址，去重並排除非 HTTP(S) 或包含帳密的網址。取得網址不代表已成功讀取該網頁；後續仍交給受限制的網頁抓取器檢查。

另保留標題與來源摘要；即時搜尋不加 `site:` 技術網域條件。HTML 抓取允許清單只限制全文／子頁抓取，不限制結果的來源範圍。Brave 失敗時可退回公開百科與學術索引，來源名稱與警告會一併顯示。

Brave 的排名是 **Brave 基準**，不能標成 Google 排名。Google 另以相同查詢人工記錄及評分。[Brave 官方 Web Search 文件](https://api-dashboard.search.brave.com/app/documentation/web-search/get-started)

## Jev 重排

使用固定的 `POST https://api.typesafe.ai/v1/systemone`，Bearer 驗證，傳入 `model`、`state` 與具名 `questions`。本專題的 `relevance` 是 `score` 問題，使用五級有序規準；讀取 `answers.relevance` 中的 `score`、`probabilities`、`confidence`。分數的級別索引為 0 至 4，再除以 4 轉成 0 至 1；信心是獨立欄位，不能把它當成正確率。[TypeSafe 官方 API 文件](https://docs.typesafe.ai/api)

每次搜尋最多對前五筆各呼叫一次。`state` 只有查詢、學習目的、標題與最長 1,800 字元的片段，不假設模型看過整頁。指令要求將來源當資料，忽略其中的操作要求。

目前混合方式：Jev 權重為 `0.35 × confidence`，其分數先乘上候選視窗的最大規則分數，再與原分數混合。`confidence < 0.55` 時保留原分數，`jevScore` 設為空值，代表不採用。這些權重與門檻是專題的初始設定，未經正式評估證明最佳。只重排前五筆視窗，尾端資料完整保留，不把未評分文件當成零分。任何呼叫或格式失敗由伺服器回退至整份原始規則排名。

## 來源摘要（RAG）

使用固定的 `POST https://api.openai.com/v1/responses`，Bearer 驗證；設定 `model`、`instructions`、`input`、`store=false`、`max_output_tokens=1200` 及空的 `tools`。讀取完成回應的 `output` 訊息中 `output_text`，不把 SDK 的便利欄位當成原始 HTTP 結構。[OpenAI 官方 Create a model response 文件](https://developers.openai.com/api/reference/python/resources/responses/methods/create)

最多傳五筆搜尋結果片段，每筆上限 1,800 字元。應用程式自行分配 `S1` 等引用代碼；模型只可使用 `[S1]` 形式的引用。回傳後檢查代碼屬於本次輸入，拒絕不存在、格式不符、完全沒有引用、附帶任意網址或未完成的回答。來源連結從本次搜尋結果建立，不採用模型生成的網址。

這是「檢索片段後生成摘要」的 RAG，沒有另建向量資料庫，也沒有宣稱向量檢索已完成。引用檢查能確認來源代碼存在，**不能自動證明每個句子都受到來源支持**；仍需人工評估忠實性。沒有可用片段時不呼叫模型。提示注入防護與無工具設計降低影響範圍，但不能保證模型永遠遵守指令。

## 資源限制與錯誤

HTTP client 不跟隨重新導向，避免驗證標頭被轉送到其他位置；單次回應在串流接收時限制 1 MiB。連線上限 8 秒，整個回應等待另受 `PROVIDER_TIMEOUT_SECONDS` 限制。五次 Jev 呼叫為循序，最差等待時間可能是單次上限的五倍。

401/403、429、其他失敗狀態及無效 JSON 只回傳固定的中文錯誤，不將供應商原始 body、傳輸例外或金鑰回傳瀏覽器。`Request`、`Reply` 的字串表示也隱藏內容；仍不可主動記錄其 headers/body 屬性。

## 評估資料與已驗證範圍

`data/evaluation.json` 提供 50 題人工評分草案，分為 20 題開發用、30 題正式測試用。`expected` 是評分規準，不是真實相關網址標記；`answerable` 依公開知識可回答性設定，不保證離線示範語料足夠。組員須先共同審閱與凍結資料，再由至少兩人評分。不存在已跑完的 Google 勝率或模型品質數字。

`ProvidersTest` 完全使用 mock transport，檢查官方 request 形狀、回應欄位解析、候選保留、信心不足時回退、引用 allowlist、大小限制及敏感錯誤遮蔽。這些測試證明本機 adapter 行為，不能代替真實供應商整合驗證。公開網站可讀性、帳戶授權、模型輸出品質、費用及供應商實際延遲均尚待使用者設定後驗證。
