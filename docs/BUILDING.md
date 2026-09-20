# 從原始碼建置 Open Launcher

本文件收錄完整的建置步驟與注意事項。專案簡介請見 [README](../README.md)。

## 基本建置

1. 準備環境：Android Studio（內建 JBR，即 JetBrains Runtime）、已設定好的 Android SDK（`local.properties` 需指向 SDK 路徑）。
2. 於儲存庫根目錄執行（Git Bash）：

   ```bash
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
   ```

3. 建置完成後，debug APK 會輸出在 `build/outputs/apk/` 對應的資料夾中，檔名格式為 `OpenLauncher.<版本>.github.debug.apk`。

新聞頁外掛（`feed/`）是獨立的 Gradle 專案，需另外進入該目錄以其自身的 wrapper 建置，詳見該目錄內的說明。

### 本機測試用 release 建置（debug key 簽署）

沒有正式 release keystore 時，也可以建置**可安裝、非 debug** 的 release 版本供本機測試：

1. 根目錄若沒有 `keystore.properties`，`build.gradle` 的 release 簽章設定會自動退回使用
   系統的 Android debug keystore（`~/.android/debug.keystore`，別名 `androiddebugkey`，
   密碼皆為 `android`）簽署啟動器 release 版。要換回正式簽章，只要在根目錄放一份
   `keystore.properties`（`keyAlias`／`keyPassword`／`storeFile`／`storePassword`）即可，
   不需要改程式碼。
2. 建置啟動器 release 版：
   ```bash
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleLawnWithQuickstepGithubRelease --no-daemon
   ```
3. `feed/build.gradle.kts` 的 release build type **一律**用 AGP 內建的 `debug` 簽章設定簽署
   （見 [`feed/README.md`](../feed/README.md)，這是刻意的設計，與正式／測試無關）。
4. 啟動器 release 版會驗證外掛的簽章雜湊，預設是「尚未填入」的佔位值，一律拒絕外掛。
   要讓本機測試的 release 啟動器接受外掛，需要把簽章雜湊填進根目錄的 `local.properties`
   （`feedSignatureHash=0x...`，該檔已被 git 忽略）；取得方式與注入機制見
   [`feed/README.md`](../feed/README.md) 的「取得 release 簽章雜湊」章節。**正式發佈**用真正的
   release keystore 簽外掛時，同樣要用該章節的方法算出那把 keystore 的雜湊並注入，不能沿用
   debug keystore 算出的值。

### 建置注意事項

- **切換分支／合併後請先乾淨建置再裝機**：每次 `git pull`／合併／切換分支之後，若要把 APK 裝到實機測試，請先執行 `./gradlew clean`，或手動刪除 `build/kotlin` 與 `build/intermediates/built_in_kotlinc` 兩個目錄，再重新建置。原因是 Kotlin 的增量編譯（incremental compilation）在切換分支後可能只重新編譯了部分類別，導致產物不同步；曾經實際出現的症狀是安裝後啟動器立即以 `NoSuchFieldError: No field $stable ... BasePreferenceManager$StringPref` 閃退，重新乾淨建置後問題就消失，原始碼本身並沒有問題。
- **Windows 上的 KSP 「different roots」flake**：在 Windows（尤其專案與使用者暫存目錄不同磁碟機代號時）偶爾會遇到 KSP 回報 "different roots" 的建置失敗，這是已知的環境性偶發問題，並非程式碼錯誤；直接重新執行同一個建置指令即可。也因為如此，**不要**在同一個 session 裡緊接著先跑 spotless 再跑 assemble，以免更容易觸發這個 flake。
- **快速驗證 APK**：建置完成後，可先用 `unzip -l` 或 Android Studio 的 APK Analyzer 檢查輸出的 `build/outputs/apk/**/*.apk` 內是否包含預期的 `classes*.dex`、資源與 `AndroidManifest.xml`，確認產物不是空的或不完整的；若要更深入確認某個類別是否真的被編譯進去，可選擇性地用 `dexdump`（Android SDK build-tools 內附）對 `classes.dex` 做反組譯抽查，但這一步非必要，多數情況下裝置安裝並實際操作即可驗證。
