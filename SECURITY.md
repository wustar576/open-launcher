# 安全性政策

## 支援的版本

Open Launcher 目前處於早期開發階段，**只有最新一次的建置會收到修正**。舊版本一律不支援。

## 回報安全性問題

請不要公開開立 issue 描述可被利用的漏洞。請改用 GitHub 的私下回報功能：

<https://github.com/wustar576/open-launcher/security/advisories/new>

回報時請附上：

- 受影響的版本與裝置／Android 版本
- 重現步驟
- 影響範圍（可取得什麼資料、可執行什麼動作）

這是業餘維護的專案，無法保證回應時間，但會盡量處理。

## 範圍說明

- 啟動器本體不會主動連線任何伺服器，也不收集遙測資料。
- `feed/` 目錄的新聞頁外掛 APK 刻意以 `android:debuggable="true"` 建置，這是 Google app overlay 介面的要求。它沒有 INTERNET 權限、沒有 UI，只負責轉送 binder。安裝它會讓該 APK 可被 debugger 附加，屬於已知且必要的取捨。
