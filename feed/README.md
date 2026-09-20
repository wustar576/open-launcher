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
| `proxy`（**預設**，實機可用） | `ILauncherOverlay` 的 Stub | 外掛實作全部 17 個交易並逐一轉送。所有呼叫都由外掛的程序發出。 |
| `bridge`（實機失敗，僅供對照） | `amirz.aidlbridge.IBridge` | 外掛只負責「代綁」，把 Google 的 binder 交還啟動器，之後不在資料路徑上。 |

切換方式：改 `src/main/AndroidManifest.xml` 裡的

```xml
<meta-data android:name="app.openlauncher.feed.bridge_mode" android:value="proxy" />
```

把 `proxy` 改成 `bridge`（或反過來），重新編譯安裝即可，不需要改程式碼。

**實機結論（2026-09-20，Pixel 10 / Android 16 / Google app 正式版）**：Google app
**每一筆交易都重新驗證呼叫者**。`bridge` 模式把 binder 交還啟動器之後，
`getCallingUid()` 變成啟動器的 UID，Google app 就當作陌生人：

- 啟動器這端連 `IBinder.getInterfaceDescriptor()` 都拿到空字串；
- `windowAttached2()` 不會丟 `RemoteException`（oneway），但**永遠等不到
  `overlayStatusChanged`**，於是啟動器把所有捲動事件丟掉，向右滑完全沒反應。

`proxy` 模式所有交易都由外掛程序發出，實測可以正常滑出 Discover、捲動跟手、
Google app 被 `force-stop` 後會自動重連。因此 `proxy` 成為預設值，`bridge` 保留
在程式碼裡只是為了對照與未來 Google 若改變行為時可切換。

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
   - **release 版啟動器的預期**：桌面不會往右捲、沒有任何畫面、沒有當機。只是一般的
     「滑不動」。
   - **debug 版啟動器實測（2026-09-20）**：會直接滑出 Discover。因為 debug build 本身是
     debuggable，`FeedBridge.isPrivilegedClient` 成立，`LauncherClient` 直接綁
     `com.google.android.googlequicksearchbox`（logcat：`bindService(com.google.android.
     googlequicksearchbox, ...) = true`、`overlay api version 11`）。這是預期行為，不是
     bug，但代表**用 debug build 驗不到「沒有外掛時滑不動」這件事**。

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

### 4.3 綁定（proxy 模式，預設）✅ 已實機驗證

回到桌面並在第一頁向右滑。實機（Pixel 10 / Android 16）看到的 logcat：

```
I LauncherClient:       bindService(app.openlauncher.feed, data=app://<啟動器套件>:<uid>?v=7&cv=9, flags=..., bridge=true) = true
I OLFeed.Service:       onCreate: mode=OVERLAY_PROXY, package=app.openlauncher.feed, googleApp=available
I OLFeed.Service:       onBind: data=app://<啟動器套件>:<uid>?v=7&cv=9 -> OVERLAY_PROXY
I OLFeed.Upstream:      binding: com.android.launcher3.WINDOW_OVERLAY data=app://app.openlauncher.feed:<外掛 uid>?v=7&cv=9 pkg=com.google.android.googlequicksearchbox
I OLFeed.Upstream:      connected to ComponentInfo{com.google.android.googlequicksearchbox/....DrawerOverlayService}
I OLFeed.Proxy:         windowAttached2(keys=[layout_params, client_options, configuration])
I OLFeed.Proxy:         upstream ready (...), replaying pending state
I LauncherClientBridge: got overlay binder from ComponentInfo{app.openlauncher.feed/...OverlayBridgeService} (com.google.android.libraries.launcherclient.ILauncherOverlay)
I LauncherClient:       windowAttached2 sent (api 7, flags 15), waiting for overlayStatusChanged
I LauncherClient:       overlay status changed: 0x19 (scroll events accepted)
```

- **最關鍵的一行是最後一行**：`overlay status changed: 0x…`，bit0 = 1 才代表 Google
  接受了我們，捲動事件不會被丟掉。
- 啟動器會綁兩次，所以上面多數行會出現兩次；`OLFeed.Upstream: binding:` 只送一次。
- **實機畫面**：向右滑出 Discover 新聞頁，捲動跟著手指，向左滑回桌面，按 HOME 離開。

### 4.4 如果滑得出來但沒有內容／捲動被忽略

表示 overlay 沒有回報 `overlayStatusChanged` bit0 = 1（logcat 裡就是看不到
`LauncherClient: overlay status changed:`）。檢查：

- `OLFeed.Upstream: connected to ...` 有沒有出現；
- Google app 是否登入帳號、Discover 是否在 Google app 設定中開啟；
- 是不是被切回 `bridge` 模式了（見 4.5）。

### 4.5 bridge 模式：實機失敗的樣子（僅供對照）

把 `bridge_mode` 改回 `bridge` 重新安裝，實機會看到：

```
I LauncherClientBridge: got overlay binder from ComponentInfo{com.google.android.googlequicksearchbox/...} ()
W LauncherClientBridge: overlay binder from ... has no interface descriptor; the overlay will most likely not respond
I LauncherClient:       windowAttached2 sent (api 7, flags 15), waiting for overlayStatusChanged
（之後沒有任何 overlay status changed，向右滑沒反應）
```

空的 interface descriptor + 永遠不來的 `overlayStatusChanged` = Google app 每筆交易
都重驗呼叫者 UID，binder 交還啟動器之後就不認帳。這就是預設改用 `proxy` 的原因。

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

1. ~~**(B) bridge 模式的身分問題**~~ → **已實機證實會失敗**（2026-09-20，Pixel 10 /
   Android 16）：binder 交還啟動器之後，後續交易的 `Binder.getCallingUid()` 是啟動器的
   UID，Google app 每筆交易都重驗，於是完全不回應。預設因此改為 `proxy`。
2. **(A) proxy 模式的效能**：捲動事件每一筆都多一次 IPC。實機（Pixel 10）滑動跟手、
   看不出掉幀；低階裝置尚未驗證。
3. **協定本身沒有官方文件**：交易順序（spec §8.4）若與裝置上 Google app 實際使用的
   版本不同，呼叫會打到錯誤的方法。`TransactionOrderTest` 只能保證我們自己沒有改動
   順序，不能保證順序本身正確。
4. **Google app 可能隨時改掉或移除這個介面**，那是它的私有介面，沒有相容性承諾。
5. **release 簽章雜湊尚未填入**（見第 3 節），所以目前 release 版啟動器會拒絕外掛。
6. **debuggable 的外掛在部分企業 MDM 政策下可能被禁止安裝。**
7. **多使用者／工作資料夾**未測試；URI 內的 UID 是外掛在當前使用者下的 UID。
