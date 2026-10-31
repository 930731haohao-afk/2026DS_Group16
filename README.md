# AI SEARCH｜AI 知識與議題搜尋

組員閱讀：[專案功能與架構說明書](docs/專案功能與架構說明書.md)，包含功能、系統架構、Stage 0–6 完成狀態與待辦。

計分驗證：執行 `./scripts/qa-scores.ps1`，詳見[網頁計分自動 QA](docs/scoring-qa.md)。

資料結構期末專題，搜尋 **AI 的技術、地緣政治、哲學、經濟、政策與社會議題**。使用者選擇「概念與背景」「觀點與比較」或「研究與應用」，系統以關鍵字、來源內容與需求分數重新排列結果。任何 AI 相關詞都能提交，不用先存在於本機詞彙表；不保證所有查詢都有相關結果。

**預設就是真實來源搜尋：**沒有 API 金鑰時，透過 Wikipedia 官方 API 與 Crossref 公開索引查找百科、論文與書目。標題及「開啟原文」會開啟真實來源，不是自編頁面。這是公開百科與學術索引，**不是整個網際網路的索引**。設定 Brave 金鑰後改查一般網頁，不再加上技術網域限制。

仍保留 Java 21 後端、網址樹、Boyer–Moore 計數、關鍵字擴展及人工評估工作台。22 篇自編資料只在手動選擇「示範資料集」時使用。Jev 與 OpenAI 來源回答為選配功能，需設定金鑰。

示範資料是自編教學內容，不是 Google 搜尋結果，也不是官方網頁快照。本專題尚未取得人工比較成績，不宣稱已勝過 Google。

## 快速啟動

安裝 **JDK 21 或更新版本**（需要 `javac`）。不需要 Maven、Gradle、npm、資料庫或額外 Java 套件。

在專案資料夾開啟 PowerShell：

```powershell
.\scripts\run.ps1
```

開啟 <http://127.0.0.1:8080>。用 `Ctrl+C` 停止。若 8080 已被占用：

```powershell
.\scripts\run.ps1 -Port 8081
```

macOS／Linux：

```sh
sh scripts/run.sh
```

Windows 腳本優先尋找 `JAVA_HOME`，其次尋找 Program Files 下的 JDK，避免 Oracle javapath 啟動器造成問題。建置使用 UTF-8、`--release 21`、`-Xlint:all -Werror`。

## 介面怎麼用

1. **知識搜尋**：輸入 `RSI`、`AI 地緣政治`、`AI 哲學 意識` 或 `AI 經濟 就業`。預設查詢真實公開索引；首頁不自動送出搜尋。
2. **開啟來源**：點結果標題或「開啟原文」，在新分頁閱讀來源。無法讀取全文時仍保留可點擊結果，顯示「搜尋摘要」及摘要計數；不能把它視為整頁或整站分析。可展開明細查看分數、來源狀態與有實際走訪的網址樹。
3. **關鍵字實驗**：選示範頁或公開 HTTPS 網址，以逗號分隔詞彙，例如 `RAG, Python`。此頁做原詞計數，不擴展同義詞。
4. **評估工作台**：選開發題，填入前五筆是否相關與耗時，計算 P@5、MRR@5，下載紀錄或匯入 CSV。資料暫存在瀏覽器分頁，重新整理前要下載。

### 三種資料來源

| 模式 | 是否需要金鑰 | 用途 |
|---|---|---|
| 公開來源搜尋（預設） | 不需要；Brave 選配 | 免金鑰查 Wikipedia 與 Crossref，提供真實網址；設定 Brave 後搜尋一般網頁 |
| 自行提供公開網址 | 不需要 | 直接分析允許清單中的真實 HTTPS 網頁；需網路且遵守 robots.txt |
| 示範資料集（手動選用） | 不需要 | 可重現的課堂測試；`demo.local` 是自編資料識別碼，不能當成真實來源 |

**搜尋範圍與全文抓取是兩件事。** 搜尋結果不受技術網域清單限制；現有清單只限制選配的 HTML 全文與子頁走訪。其他來源仍以索引提供的標題／摘要呈現並連到原文。全文抓取禁止本機／私有 IP、非 HTTPS、帳密網址及非標準連接埠，轉址也重新檢查；不支援登入、JavaScript 動態頁面或 PDF 內容擷取。部分論文原文可能需訂閱。

RSI 在 AI 語境下可找到 recursive self-improvement（遞迴自我改進）。中英詞彙對照只是補充，未知詞會保留。公開 API 可能限流或暫時失敗，畫面會列出來源警告及可手動開啟的外部搜尋連結，不會改用自編資料冒充結果。

## 選配設定

```powershell
Copy-Item .env.example .env
```

編輯 `.env` 後重新啟動；環境變數優先於 `.env`。真實 `.env` 已加入 Git 忽略清單。

| 設定 | 功能 |
|---|---|
| `BRAVE_API_KEY` | 將即時來源換成 Brave 一般網頁搜尋；未設定時使用公開百科／學術索引 |
| `TYPESAFE_API_KEY`、`TYPESAFE_MODEL` | 啟用 Jev 重排；每次最多評估前五筆，低信心保留原分數 |
| `OPENAI_API_KEY`、`OPENAI_MODEL` | 啟用 OpenAI Responses 來源回答；模型需填寫帳號實際可用的名稱 |
| `FETCH_ALLOWED_HOSTS` | 允許全文抓取的精確網域，與搜尋結果範圍分開；不影響來源連結顯示 |
| `APP_HOST`、`APP_PORT` | 預設只服務本機 127.0.0.1:8080 |
| `APP_ACCESS_TOKEN` | 非本機監聽時必填，至少 16 字元；瀏覽器使用 HTTP Basic 登入 |
| `APP_ALLOWED_HOSTS` | 網站接受的 Host 名稱，部署時明確加入網域 |

金鑰只在後端使用，不放進網頁。設定 Brave 後，主動送出即時搜尋會呼叫 Brave；Jev／RAG 需另外勾選，首頁不自動呼叫模型。Brave 失敗會明示並改查公開索引；Jev 失敗回到規則排名；RAG 失敗則顯示來源摘錄並標示不是生成答案。設定金鑰前請先確認額度與預算。

外部 API 的固定端點、資料格式與測試範圍見 [服務整合說明](docs/provider-reference.md)。模擬測試通過不代表真實帳號權限、費用、延遲或回答品質已驗證。

## 驗證與評估

```powershell
.\scripts\test.ps1
```

或 `sh scripts/test.sh`。測試包含比對算法、HTML 清理、網址樹、去重／預算／取消、排名與擴展、JSON、網路位址限制、robots、API 模擬、評分計算與本機 HTTP 整合。HTTP 測試只監聽隨機本機連接埠，不呼叫付費服務。

題庫位於 `data/evaluation.json`：20 題開發、30 題保留測試，附人工判讀準則。開發階段先用 dev 題；不要針對 test 題調整規則後，又把它當未見過的測試。評分必須由組員觀察真實結果後填寫，不能把預期答案當成實測。

離線批次實驗與 CSV 計分操作見 [評估指南](docs/evaluation-guide.md)。Google／LLM 的真實輸出需另外蒐集，系統提供同條件 Google 查詢連結與一般／策略提示詞。

```powershell
.\scripts\evaluate.ps1
```

這會產生開發題結果與空白人工評分表，不會自動填造成績。

瀏覽器冒煙測試為選配開發工具，見 `scripts/browser-smoke.cjs`；不影響使用者執行網站。GitHub Actions 已加入 Java 編譯、測試與 JS 語法檢查，尚需 push 後才會在遠端執行。

交付組員時可執行 `scripts/package.ps1`，它會先執行測試，再產生不含 `.env`、Git 歷史與暫存資料的原始碼 ZIP。最新驗證紀錄見 [驗證結果](docs/verification.md)。

## 程式結構與課程對照

```text
src/main/java/edu/nccu/ai/
  App.java              HTTP 介面、驗證、逾時與外部功能整合
  KeywordCounter.java   Boyer–Moore、詞邊界、固定中英詞彙對照
  HtmlParser.java       HTML 文字、標題與連結擷取
  SearchEngine.java     BFS、網址樹、候選／頁數預算與一次擴展
  Ranking.java          詞頻與學習目的分數、內容摘錄
  SafeFetcher.java      公開 HTTPS 抓取、robots 與網域／IP 限制
  Providers.java        Brave、Jev、OpenAI 選配串接
  PublicSearch.java     Wikipedia、Crossref 免金鑰公開索引
  Evaluation.java       人工評分的 P@5、MRR@5 與耗時
  EvaluationRunner.java 離線實驗材料與 CSV 計分工具
web/                    HTML、CSS、JavaScript 介面
data/                   自編教材與評估題庫
src/test/java/          不依賴付費服務的測試
scripts/                建置、啟動、測試與評估腳本
docs/                   設計、操作、評估與課堂展示說明
```

| 階段 | 實作對應 | 尚需組員確認或執行 |
|---|---|---|
| Stage 0 | 關鍵字實驗、Boyer–Moore | 確認老師要求的重疊／英文詞邊界語義 |
| Stage 1 | 關鍵字次數、網頁分數與排序 | 用真實頁面展示 |
| Stage 2 | BFS 網址樹與各層平均分數 | 向助教確認有限深度符合課程範圍 |
| Stage 3 | 公開索引／Brave 候選＋自訂排名 | 摘要分析與完整網站分析分開展示；用真實來源評估 |
| Stage 4 | 從已取得內容挑選相關詞再搜尋一輪 | 用實測判斷是否改善 |
| Stage 5 | 完整網頁服務與部署設定 | 尚未對外部署；需選擇主機與帳號 |
| Stage 6 | 50 題、提示詞匯出、評分工作台 | 蒐集 Google／LLM 輸出與人工評分 |

完整設計與複雜度見 [設計說明](docs/design.md)，組員展示與分工見 [展示指南](docs/demo-guide.md)。企畫書是原提案，實作細節與實際限制以這些說明為準。

## 部署

本機執行不需要 Docker。選配容器：

```sh
docker build -t ai-knowledge-search .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e APP_ACCESS_TOKEN='replace-with-your-own-long-password' \
  ai-knowledge-search
```

Dockerfile 與範例設定已提供，但若本機沒有 Docker，就不能視為已測過容器。公開部署需 HTTPS 反向代理、明確的 Host 清單、請求大小與逾時限制，以及個人金鑰預算控管。沒有在本次工作中建立帳號、購買服務或公開網站。
