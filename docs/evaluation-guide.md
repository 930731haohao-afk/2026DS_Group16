# 搜尋效果怎麼驗證

本工具幫組員保存搜尋結果、準備人工評分表並計算成績。**示範資料是自編文章，只能確認流程與比較方法能運作；不能拿它的分數宣稱打敗 Google。**

## 先產生開發題結果

在專案根目錄開啟 PowerShell：

```powershell
.\scripts\evaluate.ps1
```

也可指定新資料夾：

```powershell
.\scripts\evaluate.ps1 -Action prepare -Path outputs/evaluation/dev-round-1
```

macOS / Linux：

```sh
sh scripts/evaluate.sh prepare outputs/evaluation/dev-round-1
```

預設只執行 20 題 `dev`。每一題比較三組：

| 組別 | 做了什麼 |
|---|---|
| demo-original | 依示範資料庫的原始規則取前五筆；不是 Google。 |
| demo-rule | 使用相同的六個初始候選，再依網址樹、關鍵字與搜尋目的重新排名。 |
| demo-expanded | 同樣的規則，再從實際取得的內容找相關詞，最多擴展一次查詢。 |

這些命令不會呼叫 Google、Brave、Jev 或生成模型，不會產生費用，也不會自動填寫相關性答案。離線規則設定為深度 1、總抓取上限 20、結果上限 5。

輸出資料夾包括：

- `results.json`：各組原始結果、設定、網址樹與警告，另有資料檔 SHA-256，方便確認兩次是否用了同一批資料。
- `judgments.csv`：每題每組各五列的空白評分表。
- `prompts.txt`：每題一般提問與加入搜尋策略的提問，供 Stage 6 比較使用；沒有模型實際回答。
- `README.txt`：這一批結果的使用說明。

如果指定資料夾已包含上述檔案，工具會停止，避免蓋掉前次人工評分。

## 組員怎麼填表

1. 每位評分者各複製一份 `judgments.csv`，不要直接覆蓋模板。
2. 閱讀 `data/evaluation.json` 中對應題目的 `expected`，再檢查該筆結果是否符合需求。
3. `relevant` 填 `true` 或 `false`；還沒判讀就留白。空白不代表判定不相關。
4. `seconds` 可填「人從送出查詢到找到可用資料」的完成秒數。同題同系統填一次即可；若重複填寫，數字必須相同。
5. `resultAvailable=false` 表示該名次根本沒有搜尋結果。確認後可以填 `false`；這不是模型替你判讀的分數。
6. 在 `notes` 記下理由或分歧。至少兩人獨立判讀後討論，再另存共識版本。同題、同系統、同名次只能留一筆，不要把兩人的表直接接在一起。

`engineElapsedMs` 是程式執行時間，與人工任務完成秒數不同。兩者不要混用。題目中的 `answerable` 是問題是否原則上能有合理依據，並不保證這份小型示範語料已涵蓋答案。

## 計算成績

```powershell
.\scripts\evaluate.ps1 -Action score -Path outputs/evaluation/dev-round-1/judgments-consensus.csv -OutputJson outputs/evaluation/dev-round-1/metrics.json
```

```sh
sh scripts/evaluate.sh score outputs/evaluation/dev-round-1/judgments-consensus.csv outputs/evaluation/dev-round-1/metrics.json
```

CSV 需為 UTF-8，可有 BOM。至少包含 `queryId,system,rank,relevant`；其他欄位可保留。支援雙引號、逗號及儲存格內換行，雙引號需寫成兩個連續雙引號。`rank` 只接受 1–5；`relevant` 只接受 true、false 或空白，大小寫不拘。

工具會計算：

- **Precision@5**：每題相關結果數除以 5，再取各題平均。結果不足五筆的缺漏位置按未命中處理。
- **MRR@5**：每題第一筆相關結果排名的倒數，再取平均。前五筆皆不相關就是 0。
- **平均完成秒數**：只對有人工時間資料的題目計算，並回報有幾題計時。

尚未評分的列不會自動變成 false，但當同題部分名次已評分時，計算仍用固定分母 5，因此未完成表的分數只是暫時計算。輸出會列出 `pendingRows`，正式比較前應完成評分。

工具另列出每個系統已評分的題號與 `sameQuerySet`。題號集合不同時，不能直接比較平均分數。沒有任何人工判讀時，輸出沒有成效數字。

## 正式測試與 Google / Jev 比較

先用 dev 題決定所有設定、評分標準與資料來源，再凍結程式版本。要執行 30 題 test，必須明確指定：

```powershell
.\scripts\evaluate.ps1 -Action prepare -Path outputs/evaluation/test-final -Split test
```

```sh
sh scripts/evaluate.sh prepare outputs/evaluation/test-final --split test
```

看過 test 結果後再調參，就不能把同一批題目當成未見過的正式測試。建議由負責評估的組員保管正式測試結果。

要比較真實搜尋，需另外在應用程式中使用已設定的外部來源，保存實際結果；Google、Brave、Jev 重新排名應用不同的 `system` 名稱。使用相同題目、版本條件與時間範圍，記錄日期、來源、等待時間及無結果情況。沒有金鑰或沒有真的呼叫，就寫「未測試」，不要補上假成績。

Stage 6 的兩種提示詞應交給同一模型、同一工具設定執行。記下服務、模型、日期及是否可上網，再檢查回答正確性與來源支持度。LLM 回答品質與搜尋前五筆的相關比例是不同指標，需要分開報告。
