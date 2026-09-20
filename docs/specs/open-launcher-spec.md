# Open Launcher 產品規格

- 版本：v0.1（2026-09-20）
- 上游：[LawnchairLauncher/lawnchair](https://github.com/LawnchairLauncher/lawnchair) `16-dev`（AOSP Launcher3 / Android 16）
- 儲存庫：https://github.com/wustar576/open-launcher

## 1. 產品目標

做一個**簡潔、貼近原生外觀**的自由開源 Android 桌面啟動器，只提供三項功能：

1. **桌面**：背景透明（直接顯示桌布），可以擺放應用程式圖示與小工具。
2. **應用程式抽屜**：有基礎分類，並可搜尋應用程式。
3. **Blink 新聞頁外掛**：在主畫面向右滑出 Google Discover 新聞頁；此功能由**另一個獨立 APK** 提供，啟動器本體不內含。

不在範圍內：圖示包、字型選擇、手勢自訂、Smartspace 自製卡片、備份還原、Quickstep／最近使用畫面整合、網路搜尋建議、任何遙測或遠端設定。

## 2. 設計原則

- **貼近原生**：預設值對齊 AOSP／Pixel 的外觀（5×5 格線、5 個 Dock 圖示、系統字型、不透明抽屜、系統強調色）。
- **少即是多**：設定頁只留必要項目。採「保留偏好欄位、改預設值、移除設定畫面」的策略，不大規模刪除執行期程式碼，以降低回歸風險。
- **零背景連線**：啟動器不主動連任何伺服器。
- **不改 Kotlin／Java 套件名 `app.lawnchair`**：它被 700 多個檔案引用（含被修改過的 AOSP 檔），使用者看不到。只改使用者看得到的部分。

## 3. 識別資訊

| 項目 | 值 |
|---|---|
| 顯示名稱 | Open Launcher（debug：Open Launcher (Debug)） |
| 啟動器 applicationId | `app.openlauncher`（僅保留 `github` 通路；debug 加 `.debug`） |
| 新聞頁外掛 applicationId | `app.openlauncher.feed` |
| 新聞頁外掛顯示名稱 | Open Launcher Feed |
| 建置指令 | `assembleLawnWithQuickstepGithubDebug`（JDK 使用 Android Studio 內建 JBR） |
| 設定頁 deep link scheme | `openlauncher` |

## 4. 授權與合規（必須遵守）

1. 上游主體為 **Apache-2.0**；但樹內有 18 個 **GPL-3.0-or-later** 檔案（`flowerpot/`、`IconShape*`、`FontCache` 等），而抽屜分類正需要 flowerpot。因此**本專案整體以 GPL-3.0-or-later 發佈**，並保留 `LICENSE.txt`（Apache-2.0，AOSP 與 Lawnchair 的著作權行不得刪改）。
2. 新增根目錄 `NOTICE`，聲明本作品是 AOSP Launcher3 與 Lawnchair 的修改衍生作，並概述修改範圍（Apache-2.0 §4(b)）。不得批次改寫既有檔案的著作權標頭。
3. **商標**：Apache-2.0 §6 不授權商標。必須移除 Lawnchair 名稱、圖示、捐款連結、Startpage 分潤參數、核心團隊名單、社群連結。README 需註明「衍生自 Lawnchair，與其團隊無關」。
4. **移除 Google Sans Flex 字型**（`lawnchair/res/font/googlesansflex_variable.ttf`，Google 專有、無授權檔），預設字型改為系統字型。
5. **刪除** `prebuilts/libs/libGoogleFeed.jar`（來源不明、未被引用）。`seeder.jar` 先確認是否被引用，未引用則刪除。
6. **Lawnfeed 與 LawnchairLauncher/launcherclient 沒有授權檔，禁止閱讀或複製其原始碼。** 新聞頁外掛必須 clean-room 撰寫，只能依據本規格第 8 節的協定描述與本儲存庫內的程式碼。
7. `lawnchair/aidl/` 下的 AIDL 與 `lawnchair/src/com/google/android/libraries/launcherclient/` 沒有授權標頭。外掛端的 AIDL 必須依第 8 節的協定表**自行撰寫**，不得複製檔案。

## 5. 功能一：桌面

- 維持 Launcher3 原生的 Workspace 與小工具機制（`windowShowWallpaper=true`，桌布直接透出）。
- 驗收條件：
  - 可從抽屜拖曳應用程式到桌面、建立資料夾、移除圖示。
  - 長按桌面可開啟小工具選擇器，加入、縮放、移除小工具。
  - 桌面背景無任何底色或遮罩，完整顯示桌布。
  - 長按桌面的選單只有：桌布、小工具、主畫面設定。

### 近原生預設值（由 WS-2 套用）

| 項目 | 新預設值 |
|---|---|
| Smartspace（`enable_smartspace`） | 關閉 |
| Dock 搜尋列（`hotseat_mode`） | `disabled` |
| 格線（`LayoutConfig`） | 手機 5 欄 × 5 列、Dock 5 個 |
| 抽屜不透明度（`pref_drawerOpacity`） | 1.0 |
| 字型選擇（`enable_font_selection`） | 關閉，全部使用系統字型 |
| 雙擊桌面手勢 | 無動作 |
| 自動包覆 adaptive 圖示／圖示陰影 | 關閉 |
| 桌布景深效果 | 關閉 |
| Live information／公告 | 關閉並移除程式碼 |
| 新聞頁（`enable_feed`） | 開啟 |
| 應用程式預測模式 | 關閉（不顯示抽屜頂端建議列） |

## 6. 功能二：應用程式抽屜

- **版面**：保留原生 A–Z 清單與快速捲軸；在清單**上方**顯示自動分類資料夾。
  - 使用者未手動建立抽屜資料夾時，自動以 flowerpot 規則產生分類資料夾。
  - 分類收斂為約 10–12 個桶（例如：通訊、社群、影音、遊戲、工具、生產力、購物、金融、地圖與旅遊、相片、系統、Google、其他）；成員少於 2 個的分類併入「其他」；「其他」不顯示為資料夾（這些 app 只出現在 A–Z）。
  - 分類名稱必須有字串資源，至少提供英文與繁體中文（新檔 `lawnchair/res/values/strings_categories.xml` 與 `values-zh-rTW/`）。
  - 分類結果必須快取，只在應用程式安裝／移除／更新時重算，不得每次開抽屜都查詢 PackageManager。
  - 設定頁提供一個開關「顯示分類資料夾」（預設開）。
- **搜尋**：只搜尋本機應用程式名稱與 app 捷徑，啟用模糊比對排序（`AppMatcher`）。
  - 移除：網路搜尋建議、網路搜尋動作列、聯絡人、檔案、系統設定項、計算機、最近搜尋、ASI 全域搜尋。
  - 移除後不再需要的權限（聯絡人、儲存空間等）一併從 manifest 拿掉。
- 驗收條件：
  - 上滑開啟抽屜，可見分類資料夾與 A–Z 清單；點資料夾可展開並啟動 app。
  - 輸入關鍵字即時過濾 app；錯一兩個字仍能找到。
  - 搜尋過程不發出任何網路請求。

## 7. 設定頁

只保留：**主畫面**（格線、新聞頁開關與提供者）、**應用程式抽屜**（分類開關、隱藏的應用程式）、**關於**（版本、授權聲明、原始碼連結）。
其餘入口（Smartspace、Dock、Search、Folders、Gestures、Quickstep、Backup、Experimental、Debug）從儀表板與導覽圖移除。

## 8. 功能三：Blink 新聞頁外掛（獨立 APK）

### 8.1 架構

```
Open Launcher  ──bind──▶  Open Launcher Feed（本專案，debuggable）  ──bind──▶  Google app
   (client)                      (透明轉送 ILauncherOverlay)                (Discover 畫面)
```

Google app 只接受「系統 app」或「debuggable app」作為 overlay 客戶端。一般安裝的啟動器不符合，所以由一個 **`android:debuggable="true"` 的外掛 APK**代為連線，並把 binder 交還給啟動器。外掛放在儲存庫的 `feed/` 目錄，是**獨立的 Gradle 專案**（自己的 `settings.gradle` 與 wrapper），不加入根專案。

### 8.2 啟動器端的綁定方式

- Intent action：`com.android.launcher3.WINDOW_OVERLAY`
- data URI：`app://<啟動器套件名>:<啟動器 UID>?v=7&cv=9`（探測時 host 可能是外掛套件名，或只有 `app://<套件名>`）→ 外掛的 intent-filter **只能宣告 `scheme="app"`**，不可限制 host，不可宣告 mimeType。
- 啟動器會對同一個 service **綁定兩次**（不同 flags），外掛必須容忍。
- 外掛 service 必須 `exported="true"`、不要求權限，並宣告 `<meta-data android:name="service.api.version" android:value="7"/>`。

### 8.3 `onBind()` 可回傳的兩種介面

啟動器以 `IBinder.getInterfaceDescriptor()` 判斷：

- **(A) 直接**：`com.google.android.libraries.launcherclient.ILauncherOverlay`
- **(B) 代理**：`amirz.aidlbridge.IBridge`
  - #1 `oneway void bindService(in IBridgeCallback cb, in int flags)`
  - `IBridgeCallback`：#1 `oneway void onServiceConnected(in ComponentName name, in IBinder service)`、#2 `oneway void onServiceDisconnected(in ComponentName name)`

原本建議採 (B)：外掛收到 `bindService` 後，以自己的身分去綁 Google app（`com.google.android.googlequicksearchbox`，同一個 action，data URI 的套件名與 UID 換成外掛自己的），取得 binder 後經 `cb.onServiceConnected` 交給啟動器。

**實機結論（2026-09-20，Pixel 10 / Android 16）：(B) 行不通，正式採 (A)。** Google app 每一筆交易都重新驗證呼叫者；binder 交還啟動器之後 `Binder.getCallingUid()` 變成啟動器的 UID，Google app 就完全不回應（`getInterfaceDescriptor()` 回空字串，永遠等不到 `overlayStatusChanged`，捲動事件全被丟掉）。(A) 由外掛逐一轉送 17 個交易，所有呼叫都由外掛程序發出，實測可正常滑出 Discover。外掛的 `bridge_mode` meta-data 預設值因此改為 `proxy`；(B) 的程式碼保留供對照。

### 8.4 `ILauncherOverlay` 交易順序（順序即 transaction code，不可更動）

| # | 方法 | 型態 |
|---|---|---|
| 1 | `startScroll()` | oneway |
| 2 | `onScroll(in float progress)` | oneway |
| 3 | `endScroll()` | oneway |
| 4 | `windowAttached(in WindowManager.LayoutParams lp, in ILauncherOverlayCallback cb, in int flags)` | oneway |
| 5 | `windowDetached(in boolean isChangingConfigurations)` | oneway |
| 6 | `closeOverlay(in int flags)` | oneway |
| 7 | `onPause()` | oneway |
| 8 | `onResume()` | oneway |
| 9 | `openOverlay(in int flags)` | oneway |
| 10 | `requestVoiceDetection(in boolean start)` | oneway |
| 11 | `String getVoiceSearchLanguage()` | 阻塞 |
| 12 | `boolean isVoiceDetectionRunning()` | 阻塞 |
| 13 | `boolean hasOverlayContent()` | 阻塞 |
| 14 | `windowAttached2(in Bundle bundle, in ILauncherOverlayCallback cb)` | oneway |
| 15 | `unusedMethod()` | oneway（佔位，必須存在） |
| 16 | `setActivityState(in int flags)` | oneway |
| 17 | `boolean startSearch(in byte[] data, in Bundle bundle)` | 阻塞 |

`ILauncherOverlayCallback`：#1 `oneway void overlayScrollChanged(float progress)`、#2 `oneway void overlayStatusChanged(int status)`。
附著後 overlay 端必須回報 `overlayStatusChanged`，bit0 = 1，否則啟動器會丟棄所有捲動事件。

### 8.5 啟動器端需要的修改（WS-4）

- `FeedBridge.kt`：把 `app.openlauncher.feed` 加入內建 bridge 清單，自動解析為預設提供者；簽章雜湊放在 `lawnchair/res/values/bridge.xml`。**移除** Lawnfeed 與其他第三方白名單項目（只保留本專案外掛與 Google app）。
- `LauncherClient.java`：API 版本應從「實際要綁的套件」讀取；`ACTION_PACKAGE_ADDED` 也要監聽外掛套件，安裝後自動重連。
- debug 版啟動器也要能使用新聞頁（目前 `shouldUseFeed` 在 debuggable 時為 false）。
- 未安裝外掛時：設定頁的新聞頁開關顯示「需要安裝 Open Launcher Feed」，並提供前往本專案 GitHub Releases 的連結；桌面向右滑不應有任何異常。
- 簽章：debug 階段外掛以 debug keystore 簽署；release 簽章雜湊留待發佈階段填入（文件需說明如何取得）。

### 8.6 外掛 APK 要求

- `minSdk 26`、`targetSdk` 與啟動器一致、Kotlin、無第三方相依、無 INTERNET 權限、無 UI（只有一個說明用的極簡 Activity 或完全沒有 launcher icon 皆可）。
- 需要 `<queries>` 宣告 Google app 套件，才能在 Android 11+ 綁到它。
- 驗收條件：可獨立編譯出 debug APK；有單元測試覆蓋 URI 組裝與連線狀態機；實機階段能向右滑出 Discover。

## 9. 工作分流與檔案所有權

為避免平行開發互相覆蓋，每條工作流只能修改自己擁有的檔案。需要改別人檔案時，在回報中列出「檔案 → 變更」交由協調者處理。

| 工作流 | 內容 | 擁有的檔案 | 模型 |
|---|---|---|---|
| **WS-1** | 品牌、授權、網路清除 | `build.gradle`（flavor／applicationId／名稱／檔名區塊）、`settings.gradle`、圖示資源、`nightly/`、`github/`、`lawnchair/res/values*/strings.xml`、`ui/preferences/about/**`、`ui/preferences/data/liveinfo/**`、`bugreport/**`、`lawnchair/res/font/`、`font/**`、`LICENSE.txt`、`NOTICE`、`README.md` 等根目錄文件、`fastlane/`、`crowdin.yml`、`.github/`、`prebuilts/libs/libGoogleFeed.jar`、manifest 內的硬編碼 scheme／action | Sonnet |
| **WS-2** | 近原生預設值、設定頁修剪 | `lawnchair/res/values/config.xml`、`preferences/PreferenceManager.kt`、`preferences2/PreferenceManager2.kt`、`ui/preferences/navigation/**`、`destinations/PreferencesDashboard.kt`、`ui/popup/LauncherOptionsPopup.kt`、被移除 route 的 `destinations/*.kt` | Sonnet |
| **WS-3** | 抽屜分類與搜尋 | `allapps/**`、`flowerpot/**`、`lawnchair/assets/flowerpot/`、`util/AppCategorizationUtils.kt`、`search/**`、`data/folder/**`、`destinations/AppDrawer*.kt`、`SelectAppsForDrawerFolder.kt`、`components/search/**`、新檔 `strings_categories.xml` | Opus |
| **WS-4** | 新聞頁整合與外掛 APK | `feed/**`（新）、`lawnchair/aidl/com/google/**`、`lawnchair/src/com/google/android/libraries/launcherclient/**`、`FeedBridge.kt`、`nexuslauncher/OverlayCallbackImpl.kt`、`bridge.xml`、`components/FeedPreference.kt`、`destinations/HomeScreenPreferences.kt` 的新聞頁區塊 | Opus |

三個熱點檔（`config.xml`、`PreferenceManager.kt`、`PreferenceManager2.kt`）由 WS-2 獨佔。WS-3／WS-4 需要的預設值變更，以「key → 新值」清單回報。

## 10. 驗收與測試階段

1. **每條工作流**：`assembleLawnWithQuickstepGithubDebug` 編譯成功；受影響的單元測試通過；回報修改檔案清單。
2. **整合**：四條工作流合併到 `main` 後再次完整編譯；檢查 APK 內不含被移除的字型與 jar；檢查 manifest 權限。
3. **模擬器**（可選）：桌面布局、小工具、抽屜分類與搜尋。
4. **實機（最後階段）**：安裝啟動器與外掛，驗證 Discover 新聞頁。
