# 參與 Open Launcher

歡迎回報問題或送出修改。本專案規模很小，流程也刻意保持簡單。

- 專案首頁：<https://github.com/wustar576/open-launcher>
- 問題回報與討論：<https://github.com/wustar576/open-launcher/issues>

本專案是 [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) 與 AOSP Launcher3 的獨立衍生版本，**與 Lawnchair 團隊無關**。請勿把本專案的問題回報到 Lawnchair 的 Issue 追蹤器或社群頻道。

## 回報問題前

- 先搜尋既有 issue，避免重複。
- 附上裝置型號、Android 版本、APK 版本，以及可重現的操作步驟。

## 送出修改

1. 開一個分支，一個 PR 只做一件事。
2. 產品範圍請先看 [`docs/specs/open-launcher-spec.md`](docs/specs/open-launcher-spec.md)。本專案只做三件事：桌面、應用程式抽屜、Blink 新聞頁外掛；超出範圍的新功能通常不會被接受。
3. 建置與檢查（Git Bash，於儲存庫根目錄）：

   ```bash
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew spotlessCheck --no-daemon
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew testLawnWithQuickstepGithubDebugUnitTest --no-daemon
   ```

   新聞頁外掛在 `feed/` 目錄，是獨立的 Gradle 專案，請用它自己的 wrapper 執行 `./gradlew assembleDebug testDebugUnitTest`。

4. 格式不符時用 `./gradlew spotlessApply` 自動修正。

## 授權與著作權

- 本專案整體以 **GPL-3.0-or-later** 發佈，送出的修改視同以相同條款授權。
- **不要改寫既有檔案的著作權標頭**，也不要刪改 [`LICENSE.txt`](LICENSE.txt)、[`NOTICE`](NOTICE)、[`COPYING`](COPYING)。
- 不要加入 Lawnchair 或其他專案的名稱、圖示、商標與捐款連結。
- `feed/` 外掛必須維持 clean-room：只能依據 `docs/specs/open-launcher-spec.md` 第 8 節的協定描述撰寫，不得閱讀或複製 Lawnfeed、`LawnchairLauncher/launcherclient`、AIDLBridge 等未附授權檔的專案原始碼。
