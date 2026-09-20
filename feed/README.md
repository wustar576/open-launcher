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
`lawnchair/res/values/bridge.xml` 的 `feed_bridge_signature_hash`（字串資源，內容是
`0x` 開頭的十六進位；存成字串是因為這個雜湊是有號 32-bit int，超過 `0x7FFFFFFF` 時
用 `<integer>` 資源可能解析失敗）。tracked 的預設值是佔位值 `0x0`，代表「尚未填入」：

- **debug 版啟動器**完全不驗簽章，所以用 debug keystore 簽的外掛可以直接使用。
- **release 版啟動器**會拒絕外掛，並在 logcat 印出實際看到的值。

**不需要手動編輯 `bridge.xml`。** 根目錄 `build.gradle` 會在編譯 release 變體時，
依序讀取 `-PfeedSignatureHash` Gradle 屬性 → 環境變數 `FEED_SIGNATURE_HASH` →
根目錄 `local.properties` 的 `feedSignatureHash`，用 `resValue` 覆寫該佔位值；三者
都沒有設定時就維持「尚未填入」的行為。填入步驟：

### 方法 A：直接用 keytool + jshell／小型 Java 程式算（推薦，不必先裝機）

這個雜湊是 `android.content.pm.Signature.hashCode()`，等於憑證 DER bytes 的
`java.util.Arrays.hashCode(byte[])`——**不是** SHA-256 指紋，`apksigner`／`keytool`
印出的指紋不能直接拿來用。用簽外掛的那把 keystore 匯出憑證，再自己算：

```bash
# 例如用 debug keystore（別名 androiddebugkey，密碼 android）
keytool -exportcert -keystore /path/to/your.keystore -alias <你的 alias> \
  -storepass <你的密碼> -file cert.der
```

再用同一份 JBR 的 `java`／`jshell` 對 `cert.der` 的 bytes 做
`java.util.Arrays.hashCode`，取得的有號 int 轉成十六進位（例如 `-377223519` →
`0xE98406A1`），就是要填入的值。把它放進根目錄 **`local.properties`**（該檔已被
git 忽略，每台機器自己維護）：

```properties
feedSignatureHash=0xE98406A1
```

然後照第 2 節重新編譯 release 版啟動器即可。

### 方法 B：先裝機看 logcat 回推（事後確認用）

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

4. 把該值填進 `local.properties` 的 `feedSignatureHash`（同方法 A 最後一步），
   然後重新編譯 release 版啟動器。

> 正式發佈換成真正的 release keystore 簽外掛時，**必須用同一把 keystore 重新算一次
> 雜湊**並重新注入——debug keystore 算出來的值只對本機測試有效。

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

### 4.9 執行中切換提供者（2026-09-20 修掉的 bug）

「設定 → 主畫面 → 新聞頁 → 提供者」可以在啟動器**還活著的時候**改掉。這條路徑原本是壞的：

- 舊的 `LauncherClient.reconnect()` 只做 `unbindService`，**沒有先送 `windowDetached`**。
  `unbindService()` 不會觸發 `onServiceDisconnected()`，所以前一個提供者完全不知道自己
  該放手，Google app 那邊還記著啟動器的 window token。
- `windowAttached2` 帶的就是那個 token。下一個提供者拿同一個 token 來 attach，Google app
  就當作沒看到——**永遠不回報 `overlayStatusChanged`**，向右滑一片空白。
- 雪上加霜：`mServiceState` 也沒有歸零，所以就算 Google app 回了一模一樣的 `0x19`，
  `setServiceState()` 的「沒變就不處理」也會把它連同那行 log 一起吃掉。

現在 `reconnect()` 會依序：送 `windowDetached` → 丟掉舊的 callback binder → `setServiceState(0)`
→ unbind → 重新解析 API 版本 → 重綁。外掛這端則在 `onUnbind` 時先呼叫
`LauncherOverlayProxy.releaseWindow()`，把 token 還給 Google app 之後才斷線。

測試（兩個方向都要做）：

1. 設定 → 新聞頁 → 提供者選「Google」，回桌面向右滑，確認看得到 Discover。
2. 回設定改選「Open Launcher Feed」，回桌面向右滑。
3. 再改回「Google」，回桌面向右滑。

每一次都應該出現：

```
I LauncherClient:  reconnect: releasing the current overlay before re-binding
I LauncherClient:  windowDetached sent to the previous overlay
I LauncherClient:  overlay status changed: 0x0 (scroll events dropped)
I LauncherClient:  bindService(<新的提供者>, ...) = true
I LauncherClient:  windowAttached2 sent (api …, flags 15), waiting for overlayStatusChanged
I OLFeed.Callback: overlayStatusChanged(0x19) from the Google app -> launcher (scroll events accepted)   ← 只有走外掛時
I LauncherClient:  overlay status changed: 0x19 (scroll events accepted)
```

`OLFeed.Callback` 這個 tag 是新加的：外掛現在把 `ILauncherOverlayCallback` 包一層再交給
Google app，代價是捲動回呼多一跳 oneway IPC，換到的是「Google app 到底有沒有回話」這件事
從此看得見。

### 4.10 release 版啟動器 + 外掛：查到哪裡（2026-09-20，**尚未解決**）

狀況：**release 版**啟動器（`app.openlauncher`，不是 debuggable、已設為預設桌面）配
release 版外掛（同一把 debug key，簽章檢查有過，沒有 `FeedBridge … rejected`）時，
`windowAttached2` 送出去之後**永遠等不到 `overlayStatusChanged`**，向右滑沒反應。
同一個外掛在 debug 版啟動器上（commit 5659722 的建置）當天稍早是可以滑出 Discover 的。

#### 轉送出去的 attach 到底長什麼樣（新加的診斷 log）

```
I OLFeed.Proxy: windowAttached2 payload: keys=[client_options, configuration, layout_params]
                | layout_params: packageName=app.openlauncher type=1 flags=0x81910100 token=present
                  title=app.openlauncher/app.lawnchair.LawnchairLauncher
                | client_options=0xf
                | configuration: orientation=1 density=540 smallestWidth=320dp locale=zh_TW_#Hant
```

bundle 就只有這三個 key。裡面唯一「寫著啟動器是誰」的東西是 `layout_params` 的
`packageName` 與視窗標題（`token` 一定得是啟動器的，overlay 視窗就掛在它上面）。

#### 試過、而且**沒有用**的四件事

| # | 假設 | 做法 | 結果 |
|---|---|---|---|
| 1 | Google app 拿 `layout_params.packageName` 去驗 debuggable | 轉送前改寫成外掛自己的套件名 | 沒有 `overlayStatusChanged` |
| 2 | 視窗標題也洩漏啟動器身分 | 標題一併改寫成 `app.openlauncher.feed/…OverlayBridgeService` | 同上 |
| 3 | Google app 還記著上一個 window token（§4.9 的老毛病） | attach 前先補一發 `windowDetached(false)` | 同上；`dumpsys` 裡那個陳舊視窗也沒消失 |
| 4 | 包一層 `CallbackRelay` 改掉了 callback binder 的身分 | 改回原樣轉交啟動器的 callback（＝唯一成功過那版的 wire 行為） | 同上 |

這四個開關留在 `LauncherOverlayProxy.kt` 最上面（`REWRITE_CLIENT_IDENTITY`、
`WRAP_CALLBACK`、`DETACH_BEFORE_ATTACH`），目前**全部關閉**，也就是現在送出去的東西
跟當天唯一成功過的版本一模一樣，只是多了診斷 log。

#### 最關鍵的一條新證據：Google app 其實有在跟外掛講話

外掛在上游連上之後會打一發**阻塞式**交易當 ping：

```
I OLFeed.Proxy: upstream ping: hasOverlayContent() = true
```

`windowAttached2` 是 oneway，對方不理我們時完全沒有回音；但 `hasOverlayContent()`
**答得出來**，代表 binder 是活的、Google app 願意處理外掛送去的交易。
**所以「因為啟動器不是 debuggable 所以被拒絕」這個說法，現有證據並不支持**——被拒絕的
不是外掛這個客戶端，而是這一次 attach 沒有變成視窗。

#### `dumpsys` 看到的東西

- `dumpsys window windows`：Google app 留了一個 `GoogleDiscoverWindow` 掛在**早就死掉的**
  debug 版啟動器活動 token 上（`mHasSurface=false`、`mWindowRemovalAllowed=false`），
  是當天稍早那次成功 session 留下來的。重新安裝 debug 版啟動器讓那個 token 消失之後，
  這個視窗才跟著不見——但 release 版啟動器的 attach 依然生不出新的視窗。
  順帶證實：Google app 會把客戶端送去的 `layout_params.packageName` 原樣寫進它建立的
  視窗屬性（那個陳舊視窗的 `package=app.openlauncher.debug`）。
- `dumpsys activity services com.google.android.googlequicksearchbox`：
  `DrawerOverlayService` 這個 service 實例已經活了 **1 小時 45 分**，橫跨當天所有 session
  都沒有被銷毀，而且**還有別的客戶端綁著**——`com.teslacoilsw.launcherclientproxy`
  （Nova Launcher 的同款外掛，`?v=9`）正連著。

#### 下一步（依序，第一步是決定性的）

1. **重啟 Google app 再測一次**：`adb shell am force-stop com.google.android.googlequicksearchbox`
   （或重開機），然後在 release 版啟動器的第一頁向右滑。上面兩條 `dumpsys` 證據都指向
   「那個 overlay service 實例卡住了」——它握過一個陳舊視窗、又同時被別家外掛綁著。
   如果重啟後就好了，那麼「release 版啟動器被拒絕」整件事是誤判，真正該寫進文件的是
   §4.6 那類「Google app 卡住 → 重連」的處理。
2. 若還是不行，再用**啟動器這一軸**做 A/B：把 debug 版啟動器設為預設桌面、提供者選外掛，
   同一個外掛再滑一次。成功＝問題真的在啟動器的身分（見下一段），失敗＝問題在裝置目前的
   Google app 狀態。
3. 如果最後真的證實 Google app 會去驗「視窗 token 屬於誰」或「目前的預設桌面是誰」，
   那就不是轉送層能解決的：外掛沒辦法替啟動器的視窗變出另一個身分，除非改成由**外掛自己
   持有 overlay 視窗**（需要 `SYSTEM_ALERT_WINDOW` 權限、自己處理捲動與層級，與「零權限」
   的設計前提直接衝突），或是接受啟動器本體 debuggable（安全上不可接受）。這兩條都不建議，
   應該先把第 1、2 步的證據拿到手。

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
