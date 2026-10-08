# 執行與部署

## 本機或同學電腦

複製專案、安裝 JDK 21、執行 `scripts/run.ps1` 即可。原生服務只開在 127.0.0.1，不會自動公開到網路。不要直接雙擊 `web/index.html`，否則 API 無法運作。

## Linux 主機

1. 在選定主機準備 JDK 21，以一般使用者取得專案。
2. `sh scripts/test.sh` 通過後，設定環境變數或 `.env`。
3. 若前方有反向代理，讓 Java 保持只監聽 127.0.0.1；設定 `APP_ACCESS_TOKEN` 與 `APP_ALLOWED_HOSTS` 為公開網域。
4. 用你選擇的服務管理器執行 `sh scripts/run.sh`，把標準輸出與錯誤輸出保留作診斷。
5. 由 HTTPS 反向代理轉送到 8080，保留原始 Host；設定請求 body 上限 600 KB、讀取請求逾時、連線速率限制，以及至少 60 秒的後端回應逾時。Basic 密碼必須在 HTTPS 下傳送。
6. 驗證 `/api/health`、首頁、無金鑰示範與錯誤回退，再逐一啟用付費服務。對外監聽時不得使用空白或短密碼。

`APP_ACCESS_TOKEN` 是小組展示用的共用入口密碼，不是完整使用者帳號系統。多人付費公開服務需要每人身分驗證、日額度、請求速率與費用控制；目前的並行／單次頁數限制不等於總花費上限。

## Docker

根目錄提供兩階段 Dockerfile，使用 Eclipse Temurin JDK/JRE 21，執行階段以非 root UID 啟動。映像標籤會隨供應者更新；正式部署應自行記錄建置日期與 image digest。

Docker 建置及遠端部署需要在實際目標環境驗證。此文件與 Dockerfile 是部署材料，不代表網站已發布，亦未建立任何雲端資源。
