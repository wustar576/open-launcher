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
→ unbind → 重新解析 API 版本 → 重綁。外掛這端則保證「**還沒把 token 還回去就不會斷線**」：
`ConnectionStateMachine` 會在真正 unbind 之前先通知 `LauncherOverlayProxy.onUpstreamReleasing()`，
用還活著的 binder 送出 `windowDetached`（§4.11 之後還多了 1 秒緩衝，啟動器在那之內
重綁的話連線根本不會斷）。

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

這四個開關本來是 `LauncherOverlayProxy.kt` 最上面的三個常數（`REWRITE_CLIENT_IDENTITY`、
`WRAP_CALLBACK`、`DETACH_BEFORE_ATTACH`），目前**全部關閉**——也就是現在送出去的東西
跟當天唯一成功過的版本一模一樣，只是多了診斷 log。
**2026-09-20 23:00 起它們搬到 `FeedFlags.kt`，改成 `adb shell setprop` 即時切換，
不必重新建置（見 §4.11）。**

而且這四項的結論都必須打折扣：它們是在「一個掛在早就死掉的 debug 版啟動器 token 上的
陳舊 `GoogleDiscoverWindow` 還卡著」的狀態下測的，而且當時**啟動器和外掛兩個變數同時都換了**
（見下一節）。在 §4.12 步驟 A 做完之前，這張表只能當作「這樣做沒有立刻解決問題」，
不能當作「這個假說已經被推翻」。

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

#### 2026-09-20 23:00 這一輪：手機中途被拔線，**沒有任何新的實機結果**

這一輪原本要跑 E0–E4 的實驗矩陣，`adb install -r` 送出去的那一刻手機被拔掉，
之後整輪都是**純主機端**工作。下面的東西分成兩種，請不要混在一起看：

- **拔線前抓到的 `dumpsys`（是實機證據）**；
- **之後的所有程式修改（全部只有程式碼推論，一行都還沒在機器上跑過）**。

| # | 實驗 | 本輪結果 | 現在怎麼跑 |
|---|---|---|---|
| E0 | 基準線：開關全關、抓完整 trace ＋ **Google 程序自己的 log** | **未執行**（拔線）；`install -r` 停在 `- waiting for device -` | §4.12 步驟 B |
| E1 | `REWRITE_CLIENT_IDENTITY`（乾淨狀態下重測） | **未執行** | `setprop log.tag.OLFeedRewriteId DEBUG`，§4.12 步驟 C |
| E2 | 上游連線抖動 | **根因已用程式碼確認並修掉**（見下），但**未經實機驗證** | §4.12 共同迴圈的「E2 有沒有生效」 |
| E3 | 協定版本 | 啟動器端已用程式碼排除（api ≥ 8 無差別）；外掛→Google 的 `v`/`cv` **未執行** | `setprop log.tag.OLFeedV9` / `OLFeedNoCv`，§4.12 步驟 D |
| E4 | Google 程序在 attach 當下的 log | **未執行**——這是本輪最可惜的一項 | `logcat --pid=<:googleapp>`，§4.12 步驟 B |
| A | **2×2：啟動器 × 外掛版本**（新增，最優先） | **未執行**；5659722 的 APK 已預先建好 | §4.12 步驟 A |

##### 拔線前抓到的東西

Google app 的程序（`adb shell ps -A | grep googlequicksearchbox`）：

```
u0_a182  3942  com.google.android.googlequicksearchbox:interactor
u0_a182  3953  com.google.android.googlequicksearchbox:googleapp   ← overlay service 在這裡
u0_a182  4062  com.google.android.googlequicksearchbox:search
```

`adb shell dumpsys activity services com.google.android.googlequicksearchbox`（節錄）：

```
* ServiceRecord{… /com.google.android.apps.gsa.nowoverlayservice.DrawerOverlayService c:app.openlauncher.feed}
  processName=com.google.android.googlequicksearchbox:googleapp
  createTime=-2h26m35s   lastActivity=-1m38s
  Bindings:
  * IntentBindRecord{… CREATE}: dat=app://com.teslacoilsw.launcherclientproxy:10364?v=9
      binder=BinderProxy@b80a292  hasBound=true
      ConnectionRecord … flags=0x21      ← BIND_AUTO_CREATE|BIND_WAIVE_PRIORITY
      ConnectionRecord … flags=0x41      ← BIND_AUTO_CREATE|BIND_IMPORTANT
  * IntentBindRecord{…}:        dat=app://app.openlauncher:10430?v=7&cv=9
      binder=null   requested=true received=true hasBound=false   ← 沒有任何 client
  * IntentBindRecord{… CREATE}: dat=app://app.openlauncher.feed:10429?v=7&cv=9
      binder=BinderProxy@60255de
      ConnectionRecord … flags=0x1       ← BIND_AUTO_CREATE，只有一條
  * IntentBindRecord{… CREATE}: dat=app://app.openlauncher.debug:10425?v=7&cv=9
      binder=BinderProxy@b1fe5d5
      ConnectionRecord … flags=0x21
```

三件以前沒寫進來的事實：

1. **release 版啟動器直連 Google app 時，`onBind` 回的是 `null`**
   （`dat=app://app.openlauncher:10430…` 那筆：`received=true` 但 `binder=null`）。
   這是「Google app 只服務系統／debuggable 客戶端」這個前提第一次有實機證據，而且證明
   **那道關卡是在 bind 的時候就擋掉**。走外掛時 bind 是成功的（拿得到 binder，
   `hasOverlayContent()` 也答得出來），所以「attach 之後石沉大海」跟「bind 被拒」
   **是兩套不同的機制**，不能拿前者的結論去解釋後者。
   （小心：`received=true` + `binder=null` 是「`onBind()` 回了 null」在 AMS 裡留下的樣子，
   這是推論不是直接看到的回傳值；那筆紀錄也是舊的——當下已經沒有任何 client 綁著。
   要確認的話，在 release 版啟動器把提供者切成「Google」直連，看它的 log 有沒有
   `onNullBinding` / `bindService … = true` 但永遠沒有 `onServiceConnected`。）
2. 同一台機器上另一家啟動器的外掛，綁的是 `?v=9`（**沒有 `cv`**），而且用**兩條**連線
   （`0x21` + `0x41`，正好對應啟動器自己那兩次 `bindService` 的 flags）；我們只綁**一條**
   `0x1`。這一軸從來沒試過，現在做成可即時切換的開關（§4.11）。
3. `DrawerOverlayService` 那個實例已經活了 2 小時 26 分、被多家客戶端共用。
   它裡面任何「以客戶端為單位」的狀態都會跨越我們每一次重新安裝而留下來。

##### 兩個變數從頭到尾沒有分離（這一輪最重要的發現）

唯一一次**實機成功**是：**debug 版啟動器 ＋ commit `5659722` 的外掛**（proxy 模式、
冷啟動、`0x19`、Discover 真的畫出來）。之後每一次失敗，**啟動器**（debug → release）
**和外掛程式碼**（`8eec4f5` 以後：`WindowAttachState`、`releaseWindow`、`CallbackRelay`、
診斷 log…）**同時都換了**。前一輪的實驗 #4 只是把 wire 行為「調回等價」，
**從來沒有真的把 5659722 那顆 APK 裝回去**。所以 2×2 的表其實只填了兩格：

| | 外掛 = 5659722 | 外掛 = 現在的 HEAD |
|---|---|---|
| **debug 版啟動器** | **成功**（唯一一次） | 沒測過 |
| **release 版啟動器** | 沒測過 | 失敗 |

補上另外兩格是下一次接上手機的**第一件事**（§4.12 步驟 A），在那之前
「release 版啟動器被 Google app 擋掉」只是假說，不是結論。
`5659722` 的外掛已經預先建好放在 `feed/build/ab-test/`（§4.12）。

##### 上游連線抖動的根因（純程式碼分析，file:line）

22:48:24 那次 trace 裡的 `upstream gone (null)` → 40 毫秒後 `connected`，來源是這一串：

1. `adb install -r` 一次更新會送出**三個**廣播：`PACKAGE_REMOVED`（`EXTRA_REPLACING=true`）、
   `PACKAGE_ADDED`（`EXTRA_REPLACING=true`）、`PACKAGE_REPLACED`。
   `LauncherClient.googleInstallListener`（`lawnchair/src/com/google/android/libraries/launcherclient/LauncherClient.java`）
   以前三個都做一次 `reconnect()`——這就是 21:50:08 那兩行 `reconnect: releasing…` 的來源。
2. 每一次 `reconnect()` 都 `mBaseService.disconnect()` + `mLauncherService.disconnect()`
   （同檔 `reconnect()`），啟動器的**兩條** binding 一起消失。
3. 兩條都消失 → 外掛的 `OverlayBridgeService.onUnbind()` 被呼叫 → 舊碼在那裡
   `proxy.releaseWindow()` + `connector.detach(proxy)`，`ConnectionStateMachine.detach()`
   最後一個客戶端走掉就**立刻** `UNBIND`（舊碼 `ConnectionStateMachine.kt:105-109`）。
4. 啟動器 40 毫秒後重綁 → `onRebind` → `attach` → `BIND` → 新的 binder。

也就是說：**一次 `install -r` 會讓外掛對 Google app 的連線被拆掉重建兩到三次**，
而 `windowAttached2` 就在這段時間內被轉送出去——很可能送在一個馬上要作廢的 binder 上。
另外 `releaseWindow()` 會把暫存的 attach 清掉（`WindowAttachState.onDetach()`），
所以新的連線起來時 replay 的是「nothing」，能不能補上完全取決於啟動器自己會不會再送一次。

這件事**不需要**猜 Google app 在想什麼就知道是錯的，所以這一輪直接修掉（§4.11）。

##### E3（協定版本）的啟動器端結論：api ≥ 8 完全沒有差別

`LauncherClient` 只在四個地方看 `apiVersion`（同檔）：

| 條件 | 行為 | 位置 |
|---|---|---|
| `< 3` | 用 `windowAttached`，否則 `windowAttached2` | `exchangeConfig()` |
| `< 4` | 用 `onResume`/`onPause`，否則 `setActivityState` | `onResume()`／`onPause()`／`exchangeConfig()` |
| `>= 6` | 才會送 `startSearch` | `startSearch()` |
| `>= 7` | `redraw()` 才會重送一次 attach | `redraw()` |

**8、9、10、11 之間沒有任何分支。** 外掛在 manifest 宣告 `service.api.version=7`
或 11，啟動器送出來的東西**一模一樣**；直連時 log 裡那個 `api 11` 只是 Google app 自己
宣告的值，不代表走了不同的協定。而且兩條路徑送給 Google app 的 URI 都是 `?v=7&cv=9`
（`LauncherClient.getIntent()` 寫死 7／9）。
**所以 E3 真正還沒試過的只有一件事：外掛自己去綁 Google app 時用的 `v` / `cv`**——
那一項已經做成可即時切換（§4.11）。宣告給啟動器看的那個值要改仍然得重新建置，
但依上表，那是沒有意義的一軸。

### 4.11 實驗開關：不必重新建置就能切換（2026-09-20）

原本寫死在 `LauncherOverlayProxy.kt` 最上面的三個常數已經搬到 `FeedFlags.kt`，
改成讀 **`log.tag.*` 系統屬性**：

```bash
adb shell setprop log.tag.OLFeedRewriteId DEBUG   # 打開
adb shell setprop log.tag.OLFeedRewriteId INFO    # 關掉
adb shell am force-stop app.openlauncher.feed     # 讓外掛從乾淨狀態重來
```

為什麼是這個機制：外掛**刻意不要任何權限**，adb 寫不進它的私有目錄，`settings put`
屬於改系統設定（實機除錯時不該動），自訂 broadcast 又要多一個 exported 元件。
而 `Log.isLoggable(tag, DEBUG)` 是公開 API，底下讀的就是 `log.tag.<tag>` 系統屬性，
`setprop` 不需要任何權限、不改任何系統設定、**重開機自動消失**。
屬性沒設時預設是 INFO，所以 `isLoggable(…, DEBUG)` 回 false ＝**全部開關預設關閉**，
也就是與 2026-09-20 唯一成功過那版相同的 wire 行為。

| 屬性（前面都要加 `log.tag.`） | 打開之後 | 對應的舊常數／假說 |
|---|---|---|
| `OLFeedRewriteId` | `windowAttached*` 的 `layout_params.packageName` 與視窗標題改寫成外掛自己的 | `REWRITE_CLIENT_IDENTITY`（假說 #1／#2） |
| `OLFeedWrapCb` | 啟動器的 `ILauncherOverlayCallback` 包一層 `CallbackRelay`（看得到 Google app 有沒有回話；交給 Google app 的 binder 身分變成外掛的） | `WRAP_CALLBACK`（假說 #4） |
| `OLFeedDetachPre` | 每次 attach 前無條件補一發 `windowDetached(false)` | `DETACH_BEFORE_ATTACH`（假說 #3） |
| `OLFeedNoLinger` | 關掉「最後一個客戶端走了先等 1 秒」的緩衝，回到舊的「立刻 unbind」 | 用來**重現** 22:48 那次的上游抖動 |
| `OLFeedBindImp` | 綁 Google app 時多加 `BIND_IMPORTANT`（`0x41`） | 對照另一家外掛的兩條連線 |
| `OLFeedV9` / `OLFeedV11` | 外掛綁 Google app 的 URI 變成 `?v=9` / `?v=11` | E3 |
| `OLFeedNoCv` | URI 整個不帶 `cv` 參數（另一家外掛就是這樣） | E3 |

每一次 attach 與每一次綁上游都會把目前的開關印在同一行 log 裡，事後看 trace 就知道
那一筆是哪個變體送出去的：

```
I OLFeed.Upstream: binding: com.android.launcher3.WINDOW_OVERLAY data=app://app.openlauncher.feed:10429?v=7&cv=9 …
                   flags=0x1 | switches: rewriteId=false wrapCb=false detachPre=false linger=1000ms bindFlags=0x1 upstream=v7,cv9
I OLFeed.Proxy:    windowAttached2(keys=[…]) -> forwarding now | switches: …
```

#### 同一輪改掉的兩個真 bug（與上面的假說無關）

1. **外掛**：最後一個客戶端解除綁定後**不再立刻拆掉 Google app 的連線**，而是先等
   `FeedFlags.LINGER_MILLIS`（1 秒）。啟動器在那之內回來（換提供者、套件更新廣播）時，
   上游連線與 window session 完全不受影響（`ConnectionStateMachine` 新增 `LINGERING` 狀態）。
   真的要拆線時，會**先**用還活著的 binder 送 `windowDetached` 再 unbind
   （`ConnectionEffect.notifyReleasing`），順序由狀態機保證。
2. **啟動器**：一次 `install -r` 的三個廣播只做**一次** `reconnect()`
   （`EXTRA_REPLACING` 的 ADDED／REMOVED 直接略過，再加 200 毫秒去抖動）。

另外兩個比較小的：`ILauncherOverlay.Stub.asInterface()` 每次都 new 一個新物件，
所以上游 binder 的「是不是同一個」改用 `IBinder` 比對（否則會對同一個 binder 重送 attach）；
以及「新的 session（callback binder 換人了）接管同一個 window token 之前先還一次」——
只在 callback binder 真的換人時做，因為 `redraw()` 會用**同一個** callback 重送
`windowAttached2`，那種情況多送一發 detach 會把畫面拆掉。

### 4.12 下一次接上手機的實驗流程（RUNBOOK）

先設好：

```bash
ADB="C:\Users\starw\AppData\Local\Android\Sdk\platform-tools\adb.exe"
CUR=feed/build/outputs/apk/release/open-launcher-feed-release.apk
OLD=feed/build/ab-test/open-launcher-feed-5659722-release.apk      # 已預先建好，git 忽略
```

兩顆 APK 的 `applicationId`、`versionCode`（都是 1）、簽章（debug key，
SHA-256 `bbdcc66c…ee19`）完全相同，`android:debuggable` 也都是 true，
所以可以互相 `adb install -r` 蓋過去。萬一出現 `INSTALL_FAILED_VERSION_DOWNGRADE`，
加 `-d`。

**每一個實驗的共同迴圈**（不必重新建置，一次大約 30 秒）：

```bash
$ADB shell setprop log.tag.<開關> DEBUG        # 見 §4.11；不改開關就跳過
$ADB logcat -c
$ADB shell am force-stop app.openlauncher.feed # 外掛重來
$ADB shell am force-stop app.openlauncher      # 它是預設桌面，會自己馬上重開並重綁
# 等 15 秒
$ADB logcat -d -s OLFeed.Service OLFeed.Upstream OLFeed.Proxy OLFeed.Callback LauncherClient
```

- **成功**＝`I LauncherClient: overlay status changed: 0x19 (scroll events accepted)`
  （走外掛時前面還會有 `I OLFeed.Callback: overlayStatusChanged(0x19)`，
  但那一行只有 `OLFeedWrapCb` 打開時才會出現）。
- **失敗**＝停在 `I LauncherClient: windowAttached2 sent (api 7, flags 15), waiting for
  overlayStatusChanged`，之後沒有任何 `overlay status changed`。
- **E2 有沒有生效**：整段 trace 裡**不應該**再出現 `OLFeed.Upstream: upstream gone`
  夾在兩次 `connected` 之間，而且一次 `install -r` 只會有**一行**
  `LauncherClient: reconnect: releasing…`。

#### 步驟 A（最優先）：補上 2×2 的另外兩格

在跑任何假說之前先做，因為現在「啟動器換了」和「外掛換了」兩個變數是綁在一起的。

- **A-1　release 啟動器 ＋ 5659722 外掛**：`$ADB install -r $OLD`，然後跑共同迴圈。
  （release 版啟動器是預設桌面、永遠活著，裝完自己就會重連，不需要碰螢幕。）
- **A-2　debug 啟動器 ＋ 5659722 外掛**：debug 版啟動器**不是**預設桌面，我們不做輸入
  注入、不用 `am start`、也不改預設桌面 role，所以這一格**必須請使用者動手**：
  請他在 debug 版啟動器裡把「設定 → 主畫面 → 新聞頁 → 提供者」選成 Open Launcher Feed，
  然後把那個啟動器開起來（或由他自己暫時把它設為預設桌面）。
  之後一樣用 `$ADB logcat -d -s …` 收 log。
- **A-3　debug 啟動器 ＋ 現在的外掛**：`$ADB install -r $CUR`，重複 A-2 的人工步驟。

判讀表：

| A-1（release + 5659722） | A-3（debug + 現在的） | 結論 |
|---|---|---|
| 成功 | 任意 | **問題出在 `8eec4f5` 之後的外掛改動**，不是啟動器身分 → 用 `git archive` 逐個 commit 二分搜尋 |
| 失敗 | 成功 | 外掛沒壞，差別真的在**啟動器**（release vs debug）→ 繼續步驟 C（E1） |
| 失敗 | 失敗 | 兩邊都不成立 → 嫌疑最大的是**裝置當下的 Google app 狀態**（那個活了 2.5 小時、被多家客戶端共用的 service 實例）→ 直接跳步驟 E |
| 成功 | 失敗 | 只有「現在的外掛 ＋ release 啟動器」這一格壞 → 回到步驟 C／D |

#### 步驟 B：E0 基準線（現在的外掛、開關全關）＋ **Google 程序的 log**

```bash
$ADB shell ps -A | grep googlequicksearchbox          # 記下 :googleapp 的 pid
$ADB logcat -c
$ADB install -r $CUR
# 等 20 秒
$ADB logcat -d --pid=<googleapp 的 pid>               # ★ attach 當下 Google 自己說了什麼
$ADB logcat -d -s OLFeed.Service OLFeed.Upstream OLFeed.Proxy OLFeed.Callback LauncherClient
$ADB shell dumpsys window windows | grep -i -B2 -A25 discover
```

`--pid` 那一行是這一輪**沒能抓到、但最有價值**的證據。要找的是 Google 程序在 attach
瞬間有沒有：`BadTokenException`、`token … is not valid; is your activity running?`、
`permission denied for window type`、`Unable to add window`、
`W WindowManager` / `E ViewRootImpl` 之類。

- 有 → Google **試著**建視窗但被 WMS 擋掉 ⇒ 問題在 window token 的歸屬，轉送層救不了。
- 完全沒有 → Google **根本沒去建視窗** ⇒ 它在更早的地方就決定不理這次 attach，
  那還是「它在檢查什麼」的問題，繼續步驟 C／D。

#### 步驟 C：E1（改寫客戶端身分）

```bash
$ADB shell setprop log.tag.OLFeedRewriteId DEBUG
```
然後跑共同迴圈。log 裡要看到
`layout_params.packageName rewritten app.openlauncher -> app.openlauncher.feed`。
沒用的話再加 `$ADB shell setprop log.tag.OLFeedWrapCb DEBUG` 一起試
（兩個都開＝前一輪 #1+#2+#4 的組合，但這次是在乾淨狀態下）。
測完記得 `setprop log.tag.OLFeedRewriteId INFO` 關回去。

#### 步驟 D：E3（協定版本／連線 flags）

```bash
$ADB shell setprop log.tag.OLFeedV9 DEBUG      # 上游 URI → ?v=9&cv=9
$ADB shell setprop log.tag.OLFeedNoCv DEBUG    # 再拿掉 cv → ?v=9（＝另一家外掛的形狀）
$ADB shell setprop log.tag.OLFeedBindImp DEBUG # 綁上游時多加 BIND_IMPORTANT
```
確認生效：`OLFeed.Upstream: binding: … data=app://app.openlauncher.feed:10429?v=9 …`。
宣告給**啟動器**看的 `service.api.version`（manifest 裡那個 7）改不了、也沒有意義，
理由見 §4.10 的 `LauncherClient` 版本分支表。

#### 步驟 E（最後手段，**要先問使用者**）：重啟 Google app

```bash
$ADB shell am force-stop com.google.android.googlequicksearchbox
```

這會影響使用者正在用的 app（而且會順便踢掉另一家啟動器的外掛連線），
**未經同意不要做**。做完之後重跑步驟 B。如果重啟後就好了，那整件事是
「overlay service 實例卡住」，該寫進文件的是 §4.6 那類重連處理，而不是身分問題。

#### 如果最後真的證實 Google app 會驗「視窗 token 屬於誰」

那就不是轉送層能解決的：外掛沒辦法替啟動器的視窗變出另一個身分。剩下的兩條路
——**外掛自己持有 overlay 視窗**（要 `SYSTEM_ALERT_WINDOW`，與「零權限」的設計前提衝突）
或**啟動器本體 debuggable**（安全上不可接受）——都不建議，應該先把步驟 A～E 的證據拿到手。

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
