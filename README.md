# Open Launcher

[![CI](https://github.com/wustar576/open-launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/wustar576/open-launcher/actions/workflows/ci.yml)

Open Launcher 是一個簡潔、貼近 Android 原生外觀的自由開源桌面啟動器（launcher）。專案衍生自 [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) 與 AOSP Launcher3，但刻意大幅簡化：移除了 Lawnchair 的品牌識別、社群連結、遙測／更新檢查與大部分自訂選項，只保留貼近原生 Pixel 啟動器的核心體驗。

**本專案與 Lawnchair 團隊沒有任何關係，也未獲得其背書。**「Lawnchair」名稱、圖示與商標僅屬於原專案所有；Open Launcher 是依 Apache-2.0 授權條款進行的獨立衍生作品。

## 三項功能

Open Launcher 只提供以下三項功能，其餘一律不在範圍內（例如圖示包、字型選擇、手勢自訂、備份還原、Quickstep 整合、網路搜尋建議或任何遙測）：

1. **桌面**：背景透明、直接顯示桌布，可擺放應用程式圖示與小工具。
2. **應用程式抽屜**：提供基礎的自動分類資料夾，並可依名稱模糊搜尋本機已安裝的應用程式；搜尋過程完全在裝置端進行，不會發出任何網路請求。
3. **Blink 新聞頁外掛**：在主畫面向右滑動可開啟 Google Discover 新聞頁。此功能由**另一個獨立的 APK**（位於本儲存庫的 [`feed/`](feed/) 目錄，是獨立的 Gradle 專案）提供，啟動器本體並不內含，也不需要安裝就能正常使用桌面與抽屜。

更完整的產品規格請見 [`docs/specs/open-launcher-spec.md`](docs/specs/open-launcher-spec.md)。

## 建置方式

1. 準備環境：Android Studio（內建 JBR，即 JetBrains Runtime）、已設定好的 Android SDK（`local.properties` 需指向 SDK 路徑）。
2. 於儲存庫根目錄執行（Git Bash）：

   ```bash
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
   ```

3. 建置完成後，debug APK 會輸出在 `build/outputs/apk/` 對應的資料夾中，檔名格式為 `OpenLauncher.<版本>.github.debug.apk`。

新聞頁外掛（`feed/`）是獨立的 Gradle 專案，需另外進入該目錄以其自身的 wrapper 建置，詳見該目錄內的說明。

### 建置注意事項

- **切換分支／合併後請先乾淨建置再裝機**：每次 `git pull`／合併／切換分支之後，若要把 APK 裝到實機測試，請先執行 `./gradlew clean`，或手動刪除 `build/kotlin` 與 `build/intermediates/built_in_kotlinc` 兩個目錄，再重新建置。原因是 Kotlin 的增量編譯（incremental compilation）在切換分支後可能只重新編譯了部分類別，導致產物不同步；曾經實際出現的症狀是安裝後啟動器立即以 `NoSuchFieldError: No field $stable ... BasePreferenceManager$StringPref` 閃退，重新乾淨建置後問題就消失，原始碼本身並沒有問題。
- **Windows 上的 KSP 「different roots」flake**：在 Windows（尤其專案與使用者暫存目錄不同磁碟機代號時）偶爾會遇到 KSP 回報 "different roots" 的建置失敗，這是已知的環境性偶發問題，並非程式碼錯誤；直接重新執行同一個建置指令即可。也因為如此，**不要**在同一個 session 裡緊接著先跑 spotless 再跑 assemble，以免更容易觸發這個 flake。
- **快速驗證 APK**：建置完成後，可先用 `unzip -l` 或 Android Studio 的 APK Analyzer 檢查輸出的 `build/outputs/apk/**/*.apk` 內是否包含預期的 `classes*.dex`、資源與 `AndroidManifest.xml`，確認產物不是空的或不完整的；若要更深入確認某個類別是否真的被編譯進去，可選擇性地用 `dexdump`（Android SDK build-tools 內附）對 `classes.dex` 做反組譯抽查，但這一步非必要，多數情況下裝置安裝並實際操作即可驗證。



本專案整體以 **GPL-3.0-or-later** 授權發佈（因樹內包含數個 GPL-3.0-or-later 檔案，例如抽屜分類所需的 `flowerpot/`）。上游 AOSP Launcher3 與 Lawnchair 的主要部份為 Apache-2.0 授權；相關著作權聲明與授權全文請見 [`LICENSE.txt`](LICENSE.txt) 與 [`NOTICE`](NOTICE)。

## 與 Lawnchair 的關係

Open Launcher 是 Lawnchair（`LawnchairLauncher/lawnchair`，`16-dev` 分支）的原始碼修改衍生版本，同時也使用了 AOSP Launcher3 的程式碼。修改內容主要包含：移除品牌識別、社群／贊助／自動更新相關程式碼與連結、精簡設定頁、加入應用程式抽屜自動分類，以及將新聞頁功能拆分為獨立外掛 APK。詳細修改範圍請見 [`NOTICE`](NOTICE)。

本專案不隸屬於 Lawnchair 開發團隊，亦未經其審核或背書；如有問題請至[本儲存庫的 Issues](https://github.com/wustar576/open-launcher/issues) 回報，不要回報至 Lawnchair 官方頻道。
