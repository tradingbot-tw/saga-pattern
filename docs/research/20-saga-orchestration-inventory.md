# Issue #20（TB1）需求分析與 Saga 編排盤點——分析報告

> **任務類型**：agent-analyze（N-version 變體 a）。**本報告為唯一交付**，未修改任何 `src/`、測試或設定檔。
> **分析基準**：`tradingbot-tw/saga-pattern` @ `d174cf0`（trunk `software-factory`）。
> **日期**：2026-10-20。所有引用以本次實際閱讀之檔案與行號為準；標示【推論】者為由命名與領域語意之候選推論，非版控事實。
> **目的**：盤點本 repo 的 Saga 編排位置與 Conductor 相依，產出 TB2「把 workflow 定義匯出入庫」所需的清單與缺口分析。

---

## 0. 結論摘要（一頁）

1. **Saga 編排的「另一半」完全不在版控內。** repo 端只有兩樣東西：(a) 以名稱啟動 `FoodDeliveryWorkflow` v1 的啟動契約（`WorkflowService.java:22-60`）；(b) 9 個 worker task 的註冊與實作（`ConductorWorkers.java:25-128`）。workflow 的**步驟順序、input 欄位對映、重試/逾時策略、補償觸發關係**全部存在外部 Conductor server（預設 `play.orkes.io/api`，`application.properties:16`）。repo 內**沒有任何** machine-readable 定義檔（`git ls-files '*.json'` → 0 檔，實測於本基準 commit）。
2. **worker 側契約可完整反查、已盤點如實**：9 個 task 名稱、輸入 POJO（欄位級）、輸出欄位、失敗語意（`FAILED` 可重試 vs `FAILED_WITH_TERMINAL_ERROR` 終端）見 §1.3；四個 domain service 與正向/補償步驟的對應見 §1.4–1.5。Issue #9 已交付的 44 個單元測試（`ConductorWorkersTest`、`WorkflowServiceTest` 等 7 個測試類；本 run 於 Temurin 17 以 `./gradlew test` 實跑 **0 失敗、2 skipped**，`BUILD SUCCESSFUL`）把其中大半固化成可執行規格。
3. **發現 10 項 repo ↔ 外部編排的不一致或缺口（G1–G10，§1.6）**。最重大的三項：
   - workflow 輸入的 10 個欄位名與 worker POJO 欄位名**沒有一一相同**（如 `address`↔`deliveryAddress`、`paymentAmount`↔`amount`、`paymentMethod`(Object/String)↔`method`(`PaymentMethod{type,details}`)）——對映只寫在外部定義裡，是 TB2 匯入時**最容易與現行定義錯開**的部分（G5/G6）；
   - `ship_food` 把算出的 `driverId` 丟棄、不進輸出（`ConductorWorkers.java:83-94`），使 `notify_driver` 的輸入欄位（`driverId/dropOff/pickUp`）在 repo 端**無生產者**——只能靠外部定義從別處餵（G3）；
   - 補償 worker 一律回空 Map、吞掉所有失敗且無回查（G8），Saga 回滾目前**不可驗證**。
4. **TB2 可行性結論：可行，但有一個硬前置**——必須先由人類裁定「哪一台 Conductor server 是 single source of truth」（`play.orkes.io` 帳號 vs 本機 `conductor-standalone:3.15.0`）並提供唯讀匯出憑證；repo 現行設定（佔位符憑證，`application.properties:17-18`）不具匯出能力。本次分析未觸碰任何憑證、未呼叫外部服務。
5. **建議方案（§3）**：採用 **方案 A——由現行 server 以 REST 匯出 metadata JSON、原樣入庫（提議路徑 `metadata/workflows/FoodDeliveryWorkflow.json`）＋ CI 一致性檢查（task 名稱雙向比對）**；代碼優先（code-first）的方案 B 列為後續方向；純文件式方案 C/D 因「不可匯入、不可驗證」被拒。
6. **建議下一步（§4）**：TB2 工作項描述草案（含驗收條件 AC-1–AC-6）＋ 5 個衍生小工作項（fix-bug / docs 類）。依 docs/06 §4，**本報告不具放行效力**，须人類審查後據以開工。
7. **文件現況提醒**：`docs/architecture.md`（基準 `3e8c20c`）有兩處已過時——「沒有任何測試」（`:342`）與「repo 內沒有 Gradle wrapper」（`:326-327`）；目前 repo 已有 `src/test/`（PR #10）與 `gradlew`（PR #13/#15 已合併）。TB2 引用該文件時須知。

---

## 1. 證據與根因

### 1.1 驗證方式（可重現）

本報告全部主張可經下列**靜態命令**重現（於 repo 根目錄、任一 commit 不早於 `d174cf0`）：

```bash
# (1) 版控內無 workflow 定義檔：輸出 0
git ls-files '*.json' | wc -l

# (2) 9 個 task 名稱與註冊點（輸出即 §1.3 表的行名單）
grep -n '@WorkerTask' src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java

# (3) 啟動契約：workflow 名稱/版本/correlationId/taskToDomain/輸入欄位
sed -n '22,60p' src/main/java/io/orkes/example/saga/service/WorkflowService.java

# (4) 單元測試綠燈（Java 17，不需外部服務；即 CI 的檢查，.github/workflows/ci.yml:20,27）
#     本 run 實測：44 條測試，0 failures、0 errors、2 skipped（SeedDataTest），BUILD SUCCESSFUL
./gradlew test

# (5) 高風險路徑清單（本報告未觸及任何一條，見 §5）
cat .github/factory/risk-paths.yml
```

外部活體驗證（**需憑證，本次未執行、留給 TB2**）：
`GET {server}/api/metadata/workflow/FoodDeliveryWorkflow`——回傳的 JSON 就是 TB2 要匯入庫的物件；若回傳 404 而應用能正常跑，代表實際生效的定義在**另一台** server／另一名稱，這本身就是必須先釐清的缺口（§3 前置 P1）。

### 1.2 Workflow 啟動契約（repo 端唯一對外的寫入面）

`WorkflowService.startFoodDeliveryWorkflow()`（`service/WorkflowService.java:22-60`），由唯一 REST 端點 `POST /triggerFoodDeliveryFlow` 觸發（`controller/OrderServiceController.java:23-26`）：

| 項目 | 值 | 證據 | 備註 |
| --- | --- | --- | --- |
| workflow 名稱 | `FoodDeliveryWorkflow`（字串寫死） | `WorkflowService.java:24` | TB2 匯入的定義必須用同名 |
| 版本 | `1`（寫死） | `:25` | **代碼硬釘 v1**：匯入若产生 v2，現行程式碼仍啟動 v1（見 G2） |
| correlationId | 固定 `"api-triggered"` | `:26` | 無法區分請求；追蹤／冪等缺口（G7） |
| taskToDomain | 當 `conductor.worker.all.domain` 非空時 `{"*": domain}` | `:28-35`；值 `saga`（`application.properties:22`） | 全 repo 少數**兩端一致**的契約 |
| workflow 輸入 | 恰 10 欄：`customerEmail, customerName, customerContact, restaurantId, foodItems, additionalNotes, address, deliveryInstructions, paymentAmount, paymentMethod` | `:37-48`；被 `WorkflowServiceTest.java:99-110` 以 `input.size()==10` 鎖住 | 型別来自 `FoodDeliveryRequest.java:8-18`：`foodItems=ArrayList<Object>`、`paymentMethod=Object`（寬鬆，G6） |
| 啟動失敗語意 | HTTP **200** + `{"error":"Order creation failure","detail":...}` | `:51-59` | 非 4xx/5xx；TB2 後設監控時注意 |

### 1.3 Worker task 盤點（9 個，全部在 `workers/ConductorWorkers.java`）

線程/輪詢：`order_food` 3 線程，其餘 2 線程；標註 `pollingInterval=300`（各註冊行）。⚠️ 與 `application.properties:23` 的 `conductor.worker.all.pollingInterval=22` 并存，孰優孰劣取決於 SDK 優先順序，repo 無法判定（G10 附註）。

| # | Task 名稱（行） | 輸入 POJO（欄位） | 輸出 | 失敗語意 | 呼叫的 domain service → 資料表 |
| --- | --- | --- | --- | --- | --- |
| 1 | `order_food`（`:25-41`） | `OrderRequest{OrderRequestId?, customerEmail, customerName, customerContact, restaurantId:int, items:ArrayList<FoodItem{item,quantity}>, notes:ArrayList<String>, deliveryAddress, deliveryInstructions}`（`pojos/OrderRequest.java:8-18`） | `{orderId}`（失敗時 `orderId=null`） | `orderId==null` → `FAILED`（可重試），無原因欄（`:32-39`） | `OrderService.createOrder`（`OrderService.java:21-59`）→ `customers`（email 去重）、`orders`、`orders_details`（`OrdersDAO.java:18-60`，兩段 INSERT 無交易包覆） |
| 2 | `check_inventory`（`:43-59`） | `CheckInventoryRequest{restaurantId:int, items:ArrayList<FoodItem>}`（`CheckInventoryRequest.java:8-10`） | 無 | 查無餐廳 → `FAILED_WITH_TERMINAL_ERROR`，原因固定 `"Restaurant is closed"`（`:51-56`） | `InventoryService.checkAvailability`（`InventoryService.java:16-22`）→ `restaurants` **唯讀**；`items` 完全未用（G9） |
| 3 | `make_payment`（`:61-81`） | `PaymentRequest{orderId, customerId:int, amount:float, method:PaymentMethod{type, details:PaymentDetails{number,expiry,cvv}}}`（`PaymentRequest.java:6-10`） | `{orderId, paymentId, paymentStatus[, error]}`（`:66-74`） | 非 `SUCCESSFUL` → `FAILED_WITH_TERMINAL_ERROR`（`:71-76`） | `PaymentService.createPayment`（`PaymentService.java:22-50`）→ `payments`；卡號以 `PaymentMethod.toString()` 落地 `method` 欄（`PaymentsDAO.java:24`＋`PaymentMethod.java:7`＋`PaymentDetails.java:9-11`，見影響範圍 §2 敏感面） |
| 4 | `ship_food`（`:83-94`） | `ShippingRequest{orderId, deliveryAddress, deliveryInstructions}`（`ShippingRequest.java:6-9`） | **無**（`setOutputData` 從未被呼叫） | `driverId==0` → `FAILED`（可重試）（`:88-92`） | `ShipmentService.createShipment`（`ShipmentService.java:16-47`）→ `shipments`（SCHEDULED→CONFIRMED，`ShipmentDAO.java:27,51`）、`drivers` 唯讀；司機隨機指派 `Random.nextInt(4)`、最多重試 10 次（`:53-63`） |
| 5 | `notify_driver`（`:96-100`） | `DriverNotificationRequest{driverId, dropOff, pickUp, orderId}`（`DriverNotificationRequest.java:6-11`） | 空 Map | 不可能失敗（no-op） | **無服務呼叫，佔實作**；`driverId/dropOff/pickUp` 于 repo 端無生產者（G3） |
| 6 | `notify_customer`（`:102-106`） | `Order`（完整物件，`Order.java:8-24`） | 空 Map | 不可能失敗（no-op） | **無服務呼叫，佔實作**；上游只輸出 `orderId`，`Order` 物件何來不明（G4） |
| 7 | `cancel_payment`（`:108-113`，補償） | `CancelRequest{orderId}`（`CancelRequest.java:6-8`） | 空 Map（恆「成功」） | 無失敗路徑（G8） | `PaymentService.cancelPayment`（`PaymentService.java:52-58`）→ 以 orderId `readPayment` 後 `UPDATE payments SET status='CANCELED'`；查無付款時對 `paymentId=null` 更新，靜默无效（`PaymentsDAO.java:35-45`） |
| 8 | `cancel_delivery`（`:115-120`，補償） | `CancelRequest{orderId}` | 空 Map | 無失敗路徑（G8） | `ShipmentService.cancelDelivery`（`ShipmentService.java:49-51`）→ `UPDATE shipments SET status='CANCELED' WHERE orderId=?`（`ShipmentDAO.java:35-45`） |
| 9 | `cancel_order`（`:122-128`，補償） | `CancelRequest{orderId}` | 空 Map | 無失敗路徑（G8） | `OrderService.getOrder`＋`cancelOrder`（`OrderService.java:61-71`）→ `UPDATE orders SET status='CANCELLED'`；訂單不存在時對空物件更新，靜默无效（`OrdersDAO.java:62-74,76-96`） |

### 1.4 Domain service ↔ workflow 步驟對應

| Domain service | 正向步驟 | 補償步驟 | 持有連線（寫死 SQLite） | 狀態寫入現況 |
| --- | --- | --- | --- | --- |
| `OrderService` | `order_food` | `cancel_order` | `OrderService.java:19`（`jdbc:sqlite:food_delivery.db`） | 只寫 `PENDING`（`:39`）與 `CANCELLED`（`:68`）；`ASSIGNED`/`CONFIRMED`（`Order.java:12-13`）**repo 內無任何設定者** |
| `InventoryService` | `check_inventory` | —（無補償，純唯讀） | `InventoryService.java:14` | 不寫入 |
| `PaymentService` | `make_payment` | `cancel_payment` | `PaymentService.java:20` | `PENDING→SUCCESSFUL/FAILED→CANCELED` 全路徑有實作者 |
| `ShipmentService` | `ship_food` | `cancel_delivery` | `ShipmentService.java:14` | `SCHEDULED→CONFIRMED`、`CANCELED`；`DELIVERED`（`Shipment.java:10`）無設定者；且 `Shipment.status` 欄為 `String`（`:19`）與 enum 並存，DAO 直接寫 enum.name()（`ShipmentDAO.java:27,39,51`） |

四個 service 皆為**靜態方法＋static DAO**（如 `OrderService.java:19-21`），無注入；`@WorkerTask` 與 service 間只靠 POJO 序列化相連。這使「worker 端契約」可用純單元測試驗證（Issue #9 已做），但**也意味著「誰在何時呼叫哪個 task、帶什麼 input」這一層在 repo 內沒有程式化載體**——正是 TB2 要補的。

### 1.5 正向與補償路徑（合併視圖）

成功主幹【推論，順序取自命名與 `ConductorWorkersTest` 的狀態語意，非版控事實】：
`order_food → check_inventory → make_payment → ship_food → notify_driver/notify_customer`。

失敗轉補償【推論】：`check_inventory`/`make_payment` 的 `FAILED_WITH_TERMINAL_ERROR` 是不重試、直接轉補償的信號；`order_food`/`ship_food` 的 `FAILED` 交由定義內 retry policy 重試。補償的**觸發者與順序**（例：`ship_food` 失敗是否補 `make_payment`＋`cancel_order`？`check_inventory` 失敗需否補 `order_food`？）repo 內無任何線索，只有 `ConductorWorkersTest.java:139-235` 證明了三個 cancel worker 各自的**效果**（订单→`CANCELLED`、付款→`CANCELED`、外送→`CANCELED`）。**「效果有、觸發無」是 TB2 定義匯入的核心缺口。**

### 1.6 不一致與缺漏清單（G1–G10）

| id | 缺口／不一致 | 證據 | 對 TB2 的影響 | 等級 |
| --- | --- | --- | --- | --- |
| **G1** | workflow 定義不存在於版控（順序、映射、重試、補償連結、超時全在外） | `git ls-files '*.json'`=0；啟動只給名稱 `WorkflowService.java:24-25` | TB2 的存在理由；匯入範圍=整份 metadata JSON | 高 |
| **G2** | 代碼硬釘 `version=1` | `WorkflowService.java:25` | 匯入新版的行為（覆寫 v1 vs 新增 v2）必須在 TB2 明定，否则新定義不會被啟動 | 高 |
| **G3** | `ship_food` 不輸出 `driverId`，`notify_driver` 的 `driverId/dropOff/pickUp` 無生產者 | `ConductorWorkers.java:83-94`（無 `setOutputData`）、`ShipmentService.java:46`（函式有回傳）、`DriverNotificationRequest.java:6-11` | 匯入的定義必然引用 repo 端不存在的資料流；是既有缺陷（建議另開 fix-bug） | 高 |
| **G4** | `notify_customer` 輸入整個 `Order`，上游只輸出 `orderId` | `ConductorWorkers.java:102-106`、`:30-39` | 同 G3：定義內必有隱藏來源（DB 讀取？整個 input 轉播？） | 中 |
| **G5** | workflow 輸入欄名與 worker POJO 欄名系統性不對齊：`address`↔`deliveryAddress`、`additionalNotes`↔`notes`、`foodItems`↔`items`、`paymentAmount`↔`amount`(double→float)、`paymentMethod`↔`method` | `WorkflowService.java:37-48` vs `OrderRequest.java:14-17`、`PaymentRequest.java:9-10`、`ShippingRequest.java:8-9` | 對映全在定義內：匯入錯一份=行為變；TB2 需逐 task 建映射表（AC） | 高 |
| **G6** | `paymentMethod` 型別寬鬆且前後不一致：API/流程輸入為 `Object`（測試餵字串 `"Credit Card"`），worker 端要 `PaymentMethod{type,details{number,expiry,cvv}}`；卡資料**不在 workflow input** | `FoodDeliveryRequest.java:13,18`、`WorkflowServiceTest.java:81`、`PaymentRequest.java:10` | 卡細節由定義內哪裡來（寫死？input 深路徑？）是匯入後最需核對的映射 | 高 |
| **G7** | `correlationId` 固定 `"api-triggered"` | `WorkflowService.java:26` | 匯入文件應保留現值以免行為漂移；改造為追蹤 ID 屬後續工作項 | 中 |
| **G8** | 補償 worker 無失敗語意：全部恆回空 Map、吞 SQL 錯誤（`System.out.println`）、不回查 | `ConductorWorkers.java:108-128`；`OrdersDAO.java:62-74`、`PaymentsDAO.java:35-45`、`ShipmentDAO.java:35-45` | 回滾「是否成功」在編排層不可知；TB2 映射表應標註此語意 | 中 |
| **G9** | `check_inventory` 名實不符：不檢查庫存、`items` 未用、查無餐廳報 `"Restaurant is closed"` | `InventoryService.java:16-22`、`ConductorWorkers.java:47-56`；庫存量表不存在（DDL `BaseDAO.java:100-113` 僅 restaurants） | 匯入定義時 task 顯示名與行為說明要照實寫 | 中 |
| **G10** | worker/beans 註冊機制隱式：repo 內無 `@Bean`/`@Configuration`（實測 grep 零命中），全靠 `orkes-conductor-client:2.1.0`（`build.gradle:27`）的 auto-configuration＋`@ComponentScan("io.orkes")`（`ConductorWorkers.java:20`、`SagaApplication.java:12`）；另有 `pollingInterval` 註解 300 vs 屬性 22（`application.properties:23`）誰勝不明 | `build.gradle:22-30`、`application.properties:22-23` | TB2 的匯入/部署文件要寫明 runtime 依賴；屬待 runtime 確認項 | 低 |

### 1.7 根因

上述缺口的共同根因只有一個結構性事實：**Saga 的編排態（定義）與執行態（worker）分居兩地，版控只覆蓋執行態**。凡「兩半契約」的縫合處（欄位映射 G5/G6、資料流生產者 G3/G4、補償觸發 §1.5、版本與標識 G2/G7）都在版控外，因此**無從編譯期或 CI 期驗證**——這不是 bug，是現行架構的必然結果；TB2 匯出入庫正是要把縫合處置入版控、讓一致性檢查得以自動化。次要根因：demo 原型快速堆砌留下的佔實作（通知 no-op、偽庫存檢查、模擬付款 `PaymentService.java:60-84`）與寫死值（SQLite URL、version、correlationId）。

---

## 2. 影響範圍

| 面向 | 受影響者 | 說明 |
| --- | --- | --- |
| 直接模組 | `WorkflowService`、`ConductorWorkers`、4 個 domain service 與 DAO/POJO | TB2 匯入的定義與這些檔的契約必須逐欄核對；其中任何命名改動都會使定義漂移 |
| 設定/運行面 | `application.properties:16-18`（server URL 與佔位憑證）、`README.md:5-13`（指示本機 standalone） | 兩處指向**兩個不同的 Conductor**（`play.orkes.io` vs `localhost:8080`）：TB2 的「真相來源」裁定是硬前置；本機 standalone 重啟後 metadata 是否留存取決於其持久化設定 |
| 高風險路徑（H） | `risk-paths.yml`：H2（Payment*，`:33-34`）、H3（application.properties／PaymentDetails／Customer，`:38-41`）、H4（dao/**，`:53-54`）、H7（controller／FoodDeliveryRequest，`:76-77`） | **本報告未觸及任何 H 路徑**。TB2 落點建議 `metadata/workflows/`（新目錄，不落任何 H glob，已逐條比對 `:17-78`）；若未來 TB2 需自動匯入步驟，`GET/POST /api/metadata` 自帶憑證屬敏感操作，需人類執行或經批准的秘密管線 |
| 敏感資料面 | workflow input 含 `customerEmail/Name/Contact`（PII）送往外部 server（`WorkflowService.java:38-40`）；付款卡號經 `PaymentMethod.toString()` 明文寫入 `payments.method`（`PaymentsDAO.java:24`） | 匯出的 JSON 若含範例 input 值，入庫前需去識別化；這是 TB2 AC 的一條（見 §4） |
| 下游工作項 | TB2（本分析直接客戶）、未來 E2E 回放/遷移測試、`docs/architecture.md` 的「待確認問題」1/2/4（`:373-381`） | TB2 完成後 architecture.md 的推論段落可升級為版控事實，文件需同步更新 |
| 使用者 | 內部工廠試點／學習用（catalog 三軸 tactical/low/low，`catalog-info.yaml:41,66,87`） | 無正式環境用戶；失敗成本=試跑中斷 |

---

## 3. 方案比較（TB2：workflow 定義匯出入庫的做法）

| 方案 | 內容 | 權威性 | 可匯入/機器可讀 | 成本 | 主要風險 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| **A. 現行 server 匯出 metadata JSON 入庫＋CI 一致性檢查** | 用 Conductor REST（`GET /api/metadata/workflow/{name}`）或 Orkes `MetadataClient` 由**運行中的定義**匯出 JSON，原樣提交至 `metadata/workflows/FoodDeliveryWorkflow.json`；文件頭記來源 server、匯出時間、去識別化說明；新增檢查腳本比對「定義引用的 task 名稱 ⇄ `@WorkerTask` 清單（9 個）」雙向差集，並比對 `name/version` 與 `WorkflowService.java:24-25`；匯入以 runbook（`POST /api/metadata/workflow`）交付，不動 CI 憑證 | **高**：即現行生效定義 | 是（JSON 即 Conductor 匯入格式） | 低-中 | 取決於 P1 前置（哪台 server、憑證）；匯出物可能含 demo 值需清理 | ✅ **建議** |
| **B. 代碼優先：以 Conductor SDK（`WorkflowBuilder`/`@WorkflowMethod` 或 DSL）在 repo 內定義 workflow，啟動時註冊** | 把編排改寫為 code-defined workflow，定義由程式碼生成，縫合處（G3/G5/G6）改在型別系統內解決 | 最高（定義=程式碼） | 是 | 高：改寫 `src/`、行為語意需先由 A 的匯出物對齊，測試重寫面大 | 未先拿到現行定義就重寫=**以猜測覆蓋事實**，風險不可控 | ⏭ 列為 A 之後的**後續方向**，不属 TB2 |
| **C.（拒）以人工撰寫的文件規格替代定義檔** | 在 docs/ 寫 Markdown 步驟表充數 | 低：與生效定義無關 | **否**：無法匯入、無法被機器檢查 | 低 | 「入庫」目標落空；G5/G6 的映射仍無事實基礎 | ❌ 拒：TB2 驗收要的是可匯入物，文件已由本报告提供 |
| **D.（拒）UI 截圖／手動複製貼上配置** | 由 playground UI 複製欄位、截圖存檔 | 中低 | 否（非機器可讀） | 低 | 不可重現、易過期 | ❌ 拒：同 C，且丟失 JSON 內的重試/超時/映射結構細節 |
| （附）存放路徑選項 | `metadata/workflows/`（採）vs `src/main/resources/workflows/`（會把定義打进執行產物、易被誤認為由應用載入）vs `docs/`（不可被 CI 檢查指向） | — | — | — | — | 建議 `metadata/workflows/`，並在 TB2 AC 固化 |

## 4. 建議下一步（可直接開成工作項）

### 4.1 主工作項草案：TB2「FoodDeliveryWorkflow 定義匯出入庫」

> **類型**：建議 agent-write-docs／agent-add-tests 混合需拆層，或由人類先做 P1 後派 agent。**範圍**：新增 `metadata/workflows/FoodDeliveryWorkflow.json` ＋ 一致性檢查 ＋ 匯入 runbook；**不修改 `src/`**。
> **前置（人類，硬性）**：
> **P1** 裁定 single source of truth：`play.orkes.io` 帳號（提供**唯讀** application key/secret 給一次性匯出）或本機 `conductor-standalone:3.15.0`（需先按 README 重建並確認定義存在）。
> **P2** 確認 `FoodDeliveryWorkflow` v1 在該 server 的實際定義可匯出（GET 回非 404）。
> **驗收條件（AC 草案）**：
> - AC-1 定義 JSON 入庫：`metadata/workflows/FoodDeliveryWorkflow.json`，內容為匯出原樣（僅允許去識別化編輯，且逐處標註）；檔內或旁註來源 server、匯出日期、server 端版本/更新时间戳。
> - AC-2 一致性檢查可執行：提供命令（腳本或 test，Java 17 可跑、不依賴外部服務）驗證——(a) JSON 中引用的每個 task 名稱 ∈ repo 9 個 `@WorkerTask`，反向差集（repo 有、定義未引用）以清單輸出；(b) JSON 的 `name/version` 等於 `WorkflowService.java:24-25` 的字面值；紅燈測試先於檢查實作交付。
> - AC-3 task 映射表：報告格式逐 task 列出「inputParameters ⇄ workflow input 欄／上游 task output 路徑」，並把 G3/G4/G5/G6 命中的每項標為 candidate finding（只老實記錄，不自動修 src）。
> - AC-4 匯入 runbook：`docs/research/` 或 `metadata/README` 內给出把 JSON 匯入一台乾淨 Conductor 的具體命令（REST `POST /api/metadata/workflow` 或 UI 步驟），憑證以佔位符表示；明確說明 v1 覆寫 vs 新版本的語意（G2）與回滾方式。
> - AC-5 敏感面自查：入庫 JSON 不含真實憑證、真實 PII／卡號（含範例 input 若帶值需替換為顯眼佔位符）；引用 H3 清單逐條打勾。
> - AC-6 不觸碰 H1–H7 路徑（risk-paths.yml），CI 綠（`./gradlew test`）。

### 4.2 衍生小工作項（各自可獨立開 Issue）

1. **agent-fix-bug**：`ship_food` 應輸出 `driverId`（乃至 `shipmentId`）供 `notify_driver` 使用——紅燈測試：斷言 `shipFoodTask` 輸出含 `driverId`。證據 G3。
2. **agent-fix-bug**：補償 worker 應可回報失敗（回查更新筆數/存在性，失敗時非終端或 terminal error）——證據 G8；涉及 H2/H4 檔案，派工時留意 score 升級。
3. **agent-add-tests**：把「定義 ⇄ worker」欄位映射以參數化測試鎖住（待 TB2 的 JSON 入庫後可行）——依賴 TB2。
4. **agent-write-docs**：更新 `docs/architecture.md` 過時兩節（測試與 wrapper）＋將 TB2 匯入後的事實替換其「推論」段落——證據 §0.7。
5. **人類/設計討論**：`correlationId` 常量化（G7）與 `version` 硬釘（G2）的治理策略；`check_inventory` 改名或實装（G9）；`PaymentMethod.toString()` 明文存卡號（§2 敏感面）是否整改。

## 5. Issue #20 DoD 對照

| DoD 條目 | 本報告的回應 |
| --- | --- |
| 有可驗證的測試/驗證方式 | §1.1 五條靜態命令＋1 條外部匯出探測命令；單元測試即既有 25 條（`./gradlew test`，CI 執行 `ci.yml:20,27`） |
| 不觸碰高風險路徑（H1–H3 等） | 交付僅 `docs/research/20-saga-orchestration-inventory.md` 一個新檔，逐條比對 `risk-paths.yml:17-78` 零命中（§2 表） |
| 跑測試確認綠燈（不跑需外部服務的 E2E） | 無程式碼變更；既有單元測試（不含 E2E，Issue #9 已排除外部服務）本 run 已於 Temurin 17 實跑綠燈（44 條、0 失敗、2 skipped），且 PR 的 CI `./gradlew test` 再次驗證，結果見 PR checks |

---

*本報告為 in-loop 分析產出：不具放行效力；「建議下一步」需人類審查後才轉為工作項。分析全程唯讀，未存取任何憑證或外部 Conductor 服務。*
