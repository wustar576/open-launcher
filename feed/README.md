# Open Launcher Feed（新聞頁外掛）

`app.openlauncher.feed` — Open Launcher 的新聞頁（主畫面向右滑出的 Google Discover
「-1 頁」）外掛程式。

- 沒有桌面圖示、沒有權限、沒有網路存取、沒有任何第三方相依。
- 完全獨立的 Gradle 專案，**不**掛在啟動器的根專案底下。
- 授權：GPL-3.0-or-later（與本專案一致）。

---

## 1. 這是什麼，為什麼需要它

Google app 的 overlay 服務（`com.android.launcher3.WINDOW_OVERLAY`）只接受
**系統 app** 或 **debuggable app** 當客戶端。使用者自己從 GitHub 下載安裝的啟動器
兩者都不是，所以它自己去綁一定會被拒絕。

這個外掛的存在只為了跨過那道門檻：

```
Open Launcher ──bind──▶ Open Launcher Feed ──bind──▶ Google app
   (一般 app)              (debuggable)                (Discover)
```

1. 啟動器綁到外掛的 `OverlayBridgeService`。
2. 外掛**以自己的身分**（自己的套件名 + 自己的 UID）去綁 Google app。
3. 外掛把拿到的 binder 交還給啟動器。

### 為什麼必須是 debuggable

`android:debuggable="true"` 在 debug 與 release 兩種 build type 都是開的。這不是
忘了關掉的除錯開關，而是整個外掛存在的理由——見上一段。`build.gradle.kts` 的
`buildTypes` 兩邊都設了 `isDebuggable = true`（AGP 會用這個值覆寫 manifest），
manifest 裡也寫了一份並加上 `tools:ignore="HardcodedDebugMode"`，讓任何人閱讀或
反編譯時都看得出來是刻意的。

**安全性取捨**：debuggable 代表任何能用 adb 的人都可以附加到這個程序。因為本程式
不持有任何使用者資料、不要求任何權限、也沒有網路存取，風險僅限於「別人可以叫它去綁
Google app」，而那正是它公開提供的功能。請不要把這個 flag 套用到啟動器本體。

### 兩種設計，可切換

| 模式 | `onBind()` 回傳 | 說明 |
|---|---|---|
| `bridge`（預設） | `amirz.aidlbridge.IBridge` | 外掛只負責「代綁」，把 Google 的 binder 交還啟動器，之後不在資料路徑上。 |
| `proxy` | `ILauncherOverlay` 的 Stub | 外掛實作全部 17 個交易並逐一轉送。所有呼叫都由外掛的程序發出。 |

切換方式：改 `src/main/AndroidManifest.xml` 裡的

```xml
<meta-data android:name="app.openlauncher.feed.bridge_mode" android:value="bridge" />
```

把 `bridge` 改成 `proxy`，重新編譯安裝即可，不需要改程式碼。

**為什麼兩種都做**：Google app 在 `onBind()` 時會依 data URI 裡的套件名／UID 與
`Binder.getCallingUid()` 驗證客戶端身分——那一刻的呼叫者是外掛（debuggable），所以
兩種模式都能通過。但 `bridge` 模式把 binder 交還之後，**後續每一筆交易的
`getCallingUid()` 會變成啟動器的 UID**。如果 Google app 只在 bind 時驗證一次，
`bridge` 可行；如果它每筆交易都重驗，`bridge` 會失敗而 `proxy` 不會。在沒有實機可
驗證之前無法確定是哪一種，因此兩條路都實作好，實機階段改一行 manifest 就能對照。

（另一個兩者共通的風險：overlay 視窗是掛在啟動器傳過來的 window token 上的。
那個 token 在兩種模式裡都一樣是啟動器的，所以這一項不構成兩者的差異。）

---

## 2. 建置與安裝

需求：Android SDK（`compileSdk 37`、`buildTools 37.0.0`）、JDK 21。
專案根目錄已有 `local.properties`（指向 SDK），`feed/` 需要一份自己的複本
（此檔在 git 之外）：

```bash
cp ../local.properties ./local.properties     # 第一次才需要
```

建置與測試（Git Bash）：

```bash
cd feed
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleDebug testDebugUnitTest --no-daemon
```

產出：

```
feed/build/outputs/apk/debug/feed-debug.apk
```

安裝：

```bash
adb install -r feed/build/outputs/apk/debug/feed-debug.apk
```

確認安裝結果（沒有桌面圖示，只能這樣開說明畫面）：

```bash
adb shell am start -n app.openlauncher.feed/.FeedInfoActivity
```

---

## 3. 取得 release 簽章雜湊

啟動器的 release 版本會驗證外掛的簽章，期望值放在
`lawnchair/res/values/bridge.xml` 的 `feed_bridge_signature_hash`。
預設是佔位值 `0x0`，代表「尚未填入」：

- **debug 版啟動器**完全不驗簽章，所以用 debug keystore 簽的外掛可以直接使用。
- **release 版啟動器**會拒絕外掛，並在 logcat 印出實際看到的值。

填入步驟：

1. 用正式 keystore 簽出 release 版外掛並安裝到裝置上。
2. 安裝 **release 版**啟動器，開啟它，然後看 logcat：

   ```bash
   adb logcat -s FeedBridge
   ```

3. 會看到類似這樣的一行（`0x…` 就是要填的值）：

   ```
   W FeedBridge: Feed provider app.openlauncher.feed rejected: signature hash 0x1a2b3c4d does not match expected 0x0
   ```

   若尚未填入佔位值，會先出現：

   ```
   E FeedBridge: Feed provider app.openlauncher.feed rejected: the release signature hash in bridge.xml is still the placeholder. See feed/README.md for how to fill it in.
   D FeedBridge: Feed provider app.openlauncher.feed(0x1a2b3c4d) isn't whitelisted
   ```

4. 把該值填進 `lawnchair/res/values/bridge.xml`：

   ```xml
   <integer name="feed_bridge_signature_hash">0x1A2B3C4D</integer>
   ```

5. 重新編譯 release 版啟動器。

> 這個「雜湊」是 `android.content.pm.Signature.hashCode()`（也就是憑證 DER bytes 的
> `Arrays.hashCode`），不是 SHA-256 指紋。用 `apksigner`／`keytool` 看到的指紋**不是**
> 這個值，一定要從上面的 log 取得。

---

## 4. 實機測試計畫

沒有實機／模擬器之前，以下步驟都**尚未執行過**。請照順序做，並對照預期的 logcat。

### 4.0 準備

```bash
adb logcat -c
adb logcat -s OLFeed.Service OLFeed.Upstream OLFeed.Bridge OLFeed.Proxy OLFeed.Info \
             FeedBridge LauncherClient LauncherClientBridge
```

確認 Google app 存在且已啟用：

```bash
adb shell pm list packages | grep googlequicksearchbox
```

### 4.1 只裝啟動器（外掛還沒裝）

1. 安裝啟動器 debug APK，設為預設桌面。
2. 進入「設定 → 主畫面 → 新聞頁」。
   - **預期**：出現「取得 Open Launcher Feed」項目；點擊會開啟
     `https://github.com/wustar576/open-launcher/releases`。
   - debug 版啟動器本身是 debuggable，所以開關仍可操作（會直連 Google app）；
     release 版啟動器則會看到停用的開關與「需要安裝 Open Launcher Feed」。
3. 回到桌面，在第一頁**向右滑**。
   - **預期**：桌面不會往右捲、沒有任何畫面、沒有當機。只是一般的「滑不動」。

### 4.2 安裝外掛

```bash
adb install -r feed/build/outputs/apk/debug/feed-debug.apk
```

- **預期（不用重開啟動器）**：

  ```
  I LauncherClient: feed provider package changed: android.intent.action.PACKAGE_ADDED package:app.openlauncher.feed
  I LauncherClient: overlay api version 7 from app.openlauncher.feed
  ```

- 回設定頁，「取得 Open Launcher Feed」那一列應該消失。

### 4.3 綁定（bridge 模式，預設）

回到桌面並在第一頁向右滑。預期的 logcat 順序：

```
I LauncherClient:       bindService(app.openlauncher.feed, data=app://<啟動器套件>:<uid>?v=7&cv=9, flags=...) = true
I OLFeed.Service:       onCreate: mode=BRIDGE, package=app.openlauncher.feed, googleApp=available
I OLFeed.Service:       onBind: data=app://<啟動器套件>:<uid>?v=7&cv=9 -> BRIDGE
I LauncherClientBridge: bound to bridge ComponentName{...OverlayBridgeService}, asking it to connect on our behalf
I OLFeed.Bridge:        bindService(flags=...) from android.os.BinderProxy@..., now 1 client(s)
I OLFeed.Upstream:      binding: com.android.launcher3.WINDOW_OVERLAY data=app://app.openlauncher.feed:<外掛 uid>?v=7&cv=9 pkg=com.google.android.googlequicksearchbox
I OLFeed.Upstream:      bindService returned true, waiting for onServiceConnected
I OLFeed.Upstream:      connected to ComponentName{com.google.android.googlequicksearchbox/...}
I OLFeed.Bridge:        handing Google overlay binder to android.os.BinderProxy@...
I LauncherClientBridge: got overlay binder from ... (com.google.android.libraries.launcherclient.ILauncherOverlay)
```

- 啟動器會綁兩次，所以 `OLFeed.Bridge: bindService(...) ... now 2 client(s)` 應該出現
  第二次，但 `OLFeed.Upstream: binding:` **只能出現一次**。
- **預期畫面**：向右滑出 Discover 新聞頁，可以跟著手指移動。

### 4.4 如果滑得出來但沒有內容／捲動被忽略

表示 overlay 沒有回報 `overlayStatusChanged` bit0 = 1。檢查：

- `OLFeed.Upstream: connected to ...` 有沒有出現；
- Google app 是否登入帳號、Discover 是否在 Google app 設定中開啟。

### 4.5 如果 4.3 連得上但滑動無反應 → 換 proxy 模式

這正是 (B) 失敗、(A) 可能成功的情況（Google app 每筆交易都重驗身分）。

1. 把 `src/main/AndroidManifest.xml` 的 `bridge_mode` 改成 `proxy`。
2. 重新 `assembleDebug` 並 `adb install -r`。
3. 重開啟動器（`adb shell am force-stop <啟動器套件>`）後再滑一次。
4. 預期會看到 `OLFeed.Service: onCreate: mode=OVERLAY_PROXY`，以及
   `OLFeed.Proxy: windowAttached2(keys=[...])`、`OLFeed.Proxy: dropping onScroll: ...`
   （只在上游還沒連上時）等等。

### 4.6 斷線與重連

```bash
adb shell am force-stop com.google.android.googlequicksearchbox
```

- **預期**：`W OLFeed.Upstream: disconnected from ...`，接著再滑一次時 Android 自動
  重連並出現 `I OLFeed.Upstream: connected to ...`。桌面不應該當機。

### 4.7 移除外掛

```bash
adb uninstall app.openlauncher.feed
```

- **預期**：`I LauncherClient: feed provider package changed: ...PACKAGE_REMOVED...`，
  向右滑回到 4.1 的無反應狀態，沒有當機。

### 4.8 Google app 被停用

```bash
adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox
```

- **預期**：
  `E OLFeed.Upstream: com.google.android.googlequicksearchbox is missing or disabled; feed will stay empty`，
  向右滑沒有反應，沒有當機。測完記得 `pm enable`。

---

## 5. 已知風險

1. **(B) bridge 模式的身分問題（最大的未知數）**：binder 交還啟動器之後，
   後續交易的 `Binder.getCallingUid()` 是啟動器的 UID。Google app 若每筆交易都驗證，
   就會失敗。應對方式見 4.5（切到 `proxy`）。**尚未實機驗證。**
2. **(A) proxy 模式的效能**：捲動事件每一筆都多一次 IPC。雖然是 oneway，仍可能在
   低階裝置上造成掉幀。**尚未實機驗證。**
3. **協定本身沒有官方文件**：交易順序（spec §8.4）若與裝置上 Google app 實際使用的
   版本不同，呼叫會打到錯誤的方法。`TransactionOrderTest` 只能保證我們自己沒有改動
   順序，不能保證順序本身正確。
4. **Google app 可能隨時改掉或移除這個介面**，那是它的私有介面，沒有相容性承諾。
5. **release 簽章雜湊尚未填入**（見第 3 節），所以目前 release 版啟動器會拒絕外掛。
6. **debuggable 的外掛在部分企業 MDM 政策下可能被禁止安裝。**
7. **多使用者／工作資料夾**未測試；URI 內的 UID 是外掛在當前使用者下的 UID。
