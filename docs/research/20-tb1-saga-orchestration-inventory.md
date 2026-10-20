# TB1：Saga 編排位置與 Conductor 相依盤點（Issue #20）

> 任務類型：`agent-analyze`（唯讀分析，不含程式碼變更）。
> 分析基準：`tradingbot-tw/saga-pattern` @ `d174cf0`（trunk `software-factory`）。
> 本報告的目的：提供 TB2「把 workflow 定義匯出入庫」所需的**清單、接縫與缺口**，不代替 TB2 的實作，也不具放行效力。

**證據等級標記**

| 等級 | 意義 |
| --- | --- |
| **A** | 本次直接從 repo 檔案／測試／已下載依賴的位元組碼可驗證 |
| **B** | 依 repo 內既有文件或程式語意推論，尚未經 Conductor 定義對照 |
| **C** | 只有取得 Conductor server 上的 `WorkflowDef`／`TaskDef` 才能確認 |

---

## 1. 結論摘要

1. **repo 內只有 Saga 的「worker 半邊」。** 應用程式以名稱 `FoodDeliveryWorkflow`、version `1` 啟動 workflow（`src/main/java/io/orkes/example/saga/service/WorkflowService.java:24-25`），並註冊 9 個 `@WorkerTask`（`src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java:25-128`）；workflow 的步驟順序、輸入對應、重試、逾時與補償連結**都不在版控內**。
2. **TB2 要匯出的對象不是 repo 內的某個檔案，而是 Conductor server 上的 `WorkflowDef` + 9 個 `TaskDef`。** repo 內沒有任何 workflow 定義 JSON、沒有 `MetadataClient.getWorkflowDef(...)` 呼叫、也沒有定義的版本紀錄（A）。現有 `docs/architecture.md` 的 Saga 流程圖本身已標示為「推論」（`docs/architecture.md:145-146,210-212`）。
3. **已找到 12 個缺口（G1–G12）；其中三個是阻擋級：**
   - **G1**：`ship_food` 成功時沒有把 `driverId` 放進 output，但 `notify_driver` 的輸入需要 `driverId`（A）。
   - **G2**：`notify_customer` 的參數是 `Order` 物件，但沒有任何 task 產生 `Order`，workflow 起始輸入也沒有這個物件；該 worker 目前是空實作，會讓「有這個步驟」與「真的通知了」脫鉤（A）。
   - **G3**：三個補償 worker 都回傳 `Map`，SDK 會一律標成 `COMPLETED`；DAO 又把 SQL 例外吞掉，因此**補償失敗不會讓 workflow 失敗**（A）。
4. **兩個「測試綠燈但契約不一定成立」的原因**：單元測試直接 `new ConductorWorkers()` 呼叫方法、`WorkflowServiceTest` 只攔截 `startWorkflow`，兩者都不經過 Conductor 的 task input 綁定（A）。因此目前 42 passed 只能證明 worker 邏輯與起始請求，不能證明匯出的定義可執行。
5. **建議以「A 為 source、B 為 diff oracle、D 為 CI 防線」組合執行**：先從 live Conductor server 匯出（方案 A），再用由程式反推的清單做 diff（方案 B）與 CI 契約驗證（方案 D）。**不建議**只靠程式反推重建定義（方案 B 單獨使用）或維持現狀只寫文件（方案 C）。
6. **匯出前應先處理 G1–G3**，否則匯出的定義會引用不存在的輸出、或把失敗的補償標成成功；G4/G5（重試非冪等、部分寫入）則建議在 TB2 匯出後立刻另開修復工作項。
7. **基線測試**：以 JDK 17（與 `.github/workflows/ci.yml:20` 一致）執行 `./gradlew test`，結果 **44 tests／42 passed／2 skipped／0 failed**；2 個 skipped 是 Issue #9 已回報的種子資料缺陷（`src/test/java/io/orkes/example/saga/dao/SeedDataTest.java:73-84,93-104`），與本次分析無關。

---

## 2. 範圍、方法與限制

### 2.1 本次讀了什麼

- 啟動路徑：`SagaApplication`、`OrderServiceController`、`WorkflowService`、`application.properties`、`build.gradle`。
- worker 與 domain service：`ConductorWorkers`、`OrderService`、`InventoryService`、`PaymentService`、`ShipmentService`。
- 資料存取：`BaseDAO`、`OrdersDAO`、`PaymentsDAO`、`ShipmentDAO`、`InventoryDAO`。
- 契約模型：`FoodDeliveryRequest`、`OrderRequest`、`CheckInventoryRequest`、`PaymentRequest`、`ShippingRequest`、`DriverNotificationRequest`、`CancelRequest`、`Order`、`Payment*` 等 POJO。
- 既有文件與測試：`docs/architecture.md`、`src/test/java/**`、`.github/workflows/ci.yml`。
- 已下載依賴的位元組碼：`orkes-conductor-client:2.1.0` 內含的 `conductor-java-sdk:3.9.30-orkes`（用來確認 worker 回傳值與參數綁定行為）。

### 2.2 本次**無法**讀到什麼

- `FoodDeliveryWorkflow` v1 的實際 `WorkflowDef`：不在 repo，也沒有可用的 Conductor 憑證（`application.properties:17-18` 仍是 `<key>`／`<secret>` 佔位符）。
- 9 個 `TaskDef` 的 `retryCount`、`retryLogic`、`timeoutSeconds`、`responseTimeoutSeconds`、`retryDelaySeconds`、`ownerEmail` 等。
- workflow 定義中的 `inputParameters`、`outputParameters`、`decisionCases`、`forkTasks`、`loopOver`、`failureWorkflow` 等實際內容。

因此，本報告中凡涉及「哪個 task 失敗會觸發哪個補償」的地方，**一律標為 B（推論）**，不當成事實。TB2 匯出後必須以實際 JSON 覆核。

### 2.3 可重現的盤點命令

```bash
# 1) 9 個 worker task（預期 9 行）
grep -n '@WorkerTask' src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java

# 2) workflow 起始輸入的 10 個 key（預期 10 行）
grep -n 'inputData.put' src/main/java/io/orkes/example/saga/service/WorkflowService.java

# 3) repo 內沒有任何 workflow/task 定義 JSON（預期只看到 .github/workflows/ci.yml）
git ls-files | grep -Ei '(workflow|taskdef).*\.(json|ya?ml)$' || echo 'no workflow definition in repo'

# 4) 基線測試（CI 使用 JDK 17）
./gradlew test --no-daemon
```

---

## 3. 盤點：Conductor 相依與啟動路徑

### 3.1 啟動與 wiring（A）

| 環節 | 位置 | 事實 |
| --- | --- | --- |
| 依賴 | `build.gradle:27` | `io.orkes.conductor:orkes-conductor-client:2.1.0`（傳遞依賴含 `conductor-java-sdk`） |
| Spring 掃描 | `src/main/java/io/orkes/example/saga/SagaApplication.java:12` | `@ComponentScan(basePackages = {"io.orkes"})` |
| 客戶端 bean | `orkes-conductor-client` 的 `io.orkes.conductor.client.spring` | `OrkesConductorClientAutoConfiguration`／`ApiClientAutoConfiguration` 是 `@Configuration`，被上述掃描載入，提供 `ApiClient`、`OrkesClients`、`WorkflowClient`、`MetadataClient` 等 bean |
| worker 啟動 | 同上 | `OrkesConductorWorkerAutoConfiguration` 是 `@Component`，在 `ContextRefreshedEvent` 時對所有 `AnnotatedWorkerExecutor` 呼叫 `startPolling()` |
| worker 設定 | `OrkesSpringWorkerConfiguration` | 讀 `conductor.worker.<task>.<prop>`，找不到時 fallback 到 `conductor.worker.all.<prop>`；因此 `conductor.worker.all.domain=saga`（`application.properties:22`）套用到全部 worker |
| workflow 分派 | `WorkflowService.java:28-35` | 啟動請求設定 `taskToDomain["*"] = "saga"`，與 worker 的 domain 對齊 |

**接縫意義**：`@ComponentScan("io.orkes")` 是整個 Conductor wiring 的隱含前提。若日後把掃描範圍縮小到 `io.orkes.example`，`WorkflowClient` bean 會消失、worker 也不會啟動，但編譯仍會成功。這一點應寫進 TB2 的匯出／匯入文件，避免被當成「只影響啟動」的小設定。

### 3.2 9 個 worker task 的 I/O 契約（A）

| Task | Worker 方法 | 輸入型別 | worker 實際讀取的欄位 | 成功 output | 失敗狀態 |
| --- | --- | --- | --- | --- | --- |
| `order_food` | `orderFoodTask`（`ConductorWorkers.java:25-41`） | `OrderRequest` | `customerEmail`、`customerName`、`customerContact`、`restaurantId`、`items`、`notes`、`deliveryAddress` | `{orderId}` | `FAILED`；失敗分支的 `output` map 沒有掛到 `TaskResult`，實際 output 為空（`ConductorWorkers.java:36-39`；測試 `ConductorWorkersTest.java:116-127` 斷言為空） |
| `check_inventory` | `checkInventoryTask`（`:43-59`） | `CheckInventoryRequest` | `restaurantId`；`items` **完全沒被使用**（`InventoryService.java:16-22`） | 無 | `FAILED_WITH_TERMINAL_ERROR`，原因固定 `Restaurant is closed` |
| `make_payment` | `makePaymentTask`（`:61-81`） | `PaymentRequest` | `orderId`、`amount`、`method`；`customerId` **沒被使用**（`PaymentService.java:22-50`） | `{orderId, paymentId, paymentStatus}`；失敗時多 `error` | `COMPLETED` 或 `FAILED_WITH_TERMINAL_ERROR` |
| `ship_food` | `shipFoodTask`（`:83-94`） | `ShippingRequest` | `orderId`、`deliveryAddress`、`deliveryInstructions` | **無**（`output` map 宣告於 `:86`，但從未 `setOutputData`） | `FAILED` |
| `notify_driver` | `checkForDriverNotifications`（`:96-100`） | `DriverNotificationRequest` | 無（空實作） | `{}` | `COMPLETED` |
| `notify_customer` | `checkForCustomerNotifications`（`:102-106`） | `Order` | 無（空實作） | `{}` | `COMPLETED` |
| `cancel_payment` | `cancelPaymentTask`（`:108-113`） | `CancelRequest` | `orderId` | `{}` | **永遠 `COMPLETED`**（回傳 `Map`，見 §4.3 G3） |
| `cancel_delivery` | `cancelDeliveryTask`（`:115-120`） | `CancelRequest` | `orderId` | `{}` | **永遠 `COMPLETED`** |
| `cancel_order` | `cancelOrderTask`（`:122-128`） | `CancelRequest` | `orderId` | `{}` | **永遠 `COMPLETED`** |

> 註：worker 方法參數沒有 `@InputParam` 時，SDK 會用 `ObjectMapper.convertValue(task.getInputData(), 參數型別)` 把**整個 task input map** 綁定到 DTO（以 `conductor-java-sdk:3.9.30-orkes` 的 `AnnotatedWorker.getParameters` 反組譯確認）。因此 DTO 欄位名必須與 Conductor `inputParameters` 的 key 完全一致，否則得到 `null`／預設值。

### 3.3 Workflow 起始輸入（A）

`WorkflowService.startFoodDeliveryWorkflow` 放入 10 個 key（`WorkflowService.java:37-48`；測試 `WorkflowServiceTest.java:99-110` 明確斷言恰好 10 個）：

| # | 起始輸入 key | 來源欄位 |
| --- | --- | --- |
| 1 | `customerEmail` | `FoodDeliveryRequest.customerEmail` |
| 2 | `customerName` | `customerName` |
| 3 | `customerContact` | `customerContact` |
| 4 | `restaurantId` | `restaurantId` |
| 5 | `foodItems` | `foodItems`（`ArrayList<Object>`） |
| 6 | `additionalNotes` | `additionalNotes`（`ArrayList<String>`） |
| 7 | `address` | `address` |
| 8 | `deliveryInstructions` | `deliveryInstructions` |
| 9 | `paymentAmount` | `paymentAmount`（`double`） |
| 10 | `paymentMethod` | `paymentMethod`（`Object`） |

另外，請求層固定 `correlationId = "api-triggered"`（`WorkflowService.java:26`），**不是**每次請求唯一，不能拿來當冪等鍵或追蹤鍵。

### 3.4 四個 domain service 與 workflow 步驟的對應（A）

| Domain service | 主要方法 | 讀寫的表 | 對應 task |
| --- | --- | --- | --- |
| `OrderService` | `createOrder`（`OrderService.java:21-59`）、`getOrder`（`:61-65`）、`cancelOrder`（`:67-71`） | `customers`、`orders`、`orders_details`（`OrdersDAO.java:18-134`） | `order_food`、`cancel_order` |
| `InventoryService` | `checkAvailability`（`InventoryService.java:16-22`） | `restaurants`（`InventoryDAO.java:17-32`） | `check_inventory` |
| `PaymentService` | `createPayment`（`PaymentService.java:22-50`）、`cancelPayment`（`:52-58`） | `payments`（`PaymentsDAO.java:14-64`） | `make_payment`、`cancel_payment` |
| `ShipmentService` | `createShipment`（`ShipmentService.java:16-47`）、`cancelDelivery`（`:49-51`） | `shipments`、`drivers`（`ShipmentDAO.java:15-74`） | `ship_food`、`cancel_delivery` |

`notify_driver`／`notify_customer` 沒有對應的 domain service；兩者目前都是回傳空 `Map` 的空實作（`ConductorWorkers.java:96-106`）。

### 3.5 補償路徑與可補償的狀態（A + B）

repo 內只有「補償 primitives」，沒有「哪個失敗觸發哪個補償」的連結；下表左半是 A（程式碼事實），右半的觸發關係是 B（依命名與 `docs/architecture.md:194-212` 的推論）。

| 正向步驟 | 實際副作用 | 現有補償 primitive | 補償實際做的事 | 缺口 |
| --- | --- | --- | --- | --- |
| `order_food` | 新增 `customers`（必要時）、`orders`、`orders_details` | `cancel_order` | `OrderService.getOrder` → `cancelOrder` → `UPDATE orders SET status='CANCELLED' WHERE orderId=?`（`OrdersDAO.java:62-74`） | 只改 `orders.status`；不刪 `orders_details`、不回滾 `customers`；訂單不存在時 silent no-op |
| `check_inventory` | 無（只讀 `restaurants`） | 無 | — | 名為 inventory，實際只檢查餐廳是否存在；`items` 未使用（`InventoryService.java:16-22`） |
| `make_payment` | 新增／更新 `payments` | `cancel_payment` | `readPayment(orderId)` → `status='CANCELED'` → `UPDATE payments WHERE paymentId=?`（`PaymentService.java:52-58`、`PaymentsDAO.java:35-45,47-64`） | 無退款；只更新讀到的最後一筆 payment；payment 不存在時 silent no-op |
| `ship_food` | 新增 `shipments`、指派 `drivers` | `cancel_delivery` | `UPDATE shipments SET status='CANCELED' WHERE orderId=?`（`ShipmentDAO.java:35-45`） | 司機指派沒有補償狀態；shipment 不存在時 silent no-op |
| `notify_*` | 無 | 無 | — | 空實作，無副作用也無通知 |

**推論的補償鏈**（B，需以匯出的定義覆核）：`check_inventory` 失敗 → `cancel_order`；`make_payment` 失敗 → `cancel_payment` → `cancel_order`；`ship_food` 失敗 → `cancel_delivery` → `cancel_payment` → `cancel_order`。`order_food` 本身失敗時，repo 內沒有「取消已寫入 customer」的 primitive，也沒有把失敗的 `orderId` 輸出，因此若 `orders` 已寫入但 `orders_details` 失敗，該半成品訂單無法被既有補償鏈定址。

---

## 4. 證據與根因

### 4.1 為什麼說「編排不在 repo」

| 觀察 | 證據 |
| --- | --- |
| 啟動時只帶名稱與版本，沒有 definition 物件 | `WorkflowService.java:23-25` 只 `setName("FoodDeliveryWorkflow")`、`setVersion(1)` |
| repo 內沒有 workflow／task 定義檔 | `git ls-files` 僅有 `.github/workflows/ci.yml`；無 `*Workflow*.json`、無 `taskdefs/` |
| 沒有讀取定義的程式碼 | 全 repo 沒有 `getWorkflowDef`、`getAllTaskDefs`、`MetadataClient` 的使用（`grep` 無命中） |
| 既有架構文件自己標示為推論 | `docs/architecture.md:145-146`「task 之間的先後順序與補償觸發條件由 Conductor 上的 workflow 定義決定，不在本 repo」；`:210-212` 同一警語 |
| 測試不經 Conductor | `ConductorWorkersTest.java:56` 直接 `new ConductorWorkers()`；`WorkflowServiceTest.java:33-51` 用 recording client 攔截 `startWorkflow`，不啟動 worker |

**根因**：這個 repo 把 Saga 切成兩個獨立演化的半邊——repo 內的 participant/worker 半邊（task 名稱、DTO、狀態語意）與 Conductor server 上的 orchestrator 半邊（步驟圖、input mapping、retry、timeout、compensation）。兩者只靠**字串 task name**與**未明文化的 DTO 欄位名**耦合，沒有 schema、沒有契約測試、也沒有定義的版本紀錄。於是「程式碼改了但定義沒改」或「定義改了但 worker 沒改」都不會被 repo 的 CI 抓到；TB2 匯出時取得的 definition 可能與現行程式碼不相容。

### 4.2 契約接縫：起始輸入 ↔ 各 worker DTO（A）

下表把 `WorkflowService` 的 10 個 key 對到各 worker DTO 的欄位。空白表示「該 task 不需要」；`←` 表示必須由前一個 task 的 output 提供。

| 起始輸入 key | `order_food` | `check_inventory` | `make_payment` | `ship_food` | `notify_driver` | 備註 |
| --- | --- | --- | --- | --- | --- | --- |
| `customerEmail` | `customerEmail` | | | | | |
| `customerName` | `customerName` | | | | | |
| `customerContact` | `customerContact` | | | | | |
| `restaurantId` | `restaurantId` | `restaurantId` | | | | |
| `foodItems` | `items` | `items` | | | | **名稱不同**；`ArrayList<Object>` vs `ArrayList<FoodItem>` |
| `additionalNotes` | `notes` | | | | | **名稱不同** |
| `address` | `deliveryAddress` | | | `deliveryAddress` | | **名稱不同** |
| `deliveryInstructions` | （未使用） | | | `deliveryInstructions` | | `order_food` 不持久化此欄位 |
| `paymentAmount` | | | `amount` | | | **名稱不同**；`double` → `float` |
| `paymentMethod` | | | `method` | | | **名稱不同**；`Object` → `PaymentMethod` |
| `orderId` | （產出） | | ← `order_food.output.orderId` | ← `order_food.output.orderId` | ← `order_food.output.orderId` | `ship_food` 目前沒有 output，見 G1 |
| `customerId` | | | （DTO 有，但 service 未使用） | | | **沒有任何 task 產生** |
| `driverId` | | | | | ← `ship_food`？ | `ship_food` 沒有 output，見 G1 |
| `Order` 物件 | | | | | | `notify_customer` 需要，無來源，見 G2 |

**結論**：這張表的每一個非空白格，都必須在匯出的 `WorkflowDef` 裡以 `inputParameters` 明確表達（例如 `"deliveryAddress": "${workflow.input.address}"`、`"items": "${workflow.input.foodItems}"`）。repo 內沒有任何程式碼做這件事；若定義漏掉某一格，worker 會收到 `null`／`0`，輕則 silent no-op，重則 NPE（見 G6）。

### 4.3 缺口清單

嚴重度定義：**阻擋級**＝匯出的定義會引用不存在的資料或把失敗標成成功；**高**＝重試或部分失敗會造成資料不一致；**中**＝可運行但契約/可觀測性不足；**低**＝文件或欄位語意問題。

#### G1（阻擋級）`ship_food` 不輸出 `driverId`，`notify_driver` 無來源

- `shipFoodTask` 宣告了 `Map<String, Object> output`（`ConductorWorkers.java:86`），但成功與失敗分支都沒有 `result.setOutputData(output)`；成功時只 `setStatus(COMPLETED)`（`:88-90`）。
- `ShipmentService.createShipment` 其實有 `driverId`（`ShipmentService.java:24-46`），但只回傳給 worker，沒有進入 workflow。
- `notify_driver` 的 `DriverNotificationRequest` 需要 `driverId`、`dropOff`、`pickUp`、`orderId`（`DriverNotificationRequest.java:7-10`），其中 `driverId` 在 repo 內沒有任何生產者。
- **影響**：匯出的定義若把 `${ship_food.output.driverId}` 餵給 `notify_driver`，永遠是 `null`；若 definition 目前是這樣寫，則實際執行時通知步驟拿到空值（目前是 no-op，所以不會炸，但語意失效）。

#### G2（阻擋級）`notify_customer` 需要 `Order` 物件，但沒有生產者

- `checkForCustomerNotifications(Order order)`（`ConductorWorkers.java:102-106`）的參數是完整 `Order`（`Order.java:17-23`：`orderId`、`customer`、`restaurantId`、`deliveryAddress`、`createdAt`、`status`、`orderDetails`）。
- `order_food` 只輸出 `orderId`；起始輸入只有 customer 欄位與地址，沒有 `Order` 物件。
- **影響**：definition 只能傳 `null`、傳一個殘缺的 map、或另外加一個「組 Order」的 task（repo 內不存在）。目前 worker 忽略參數，所以測試不會紅；但這代表「通知顧客」這個 Saga 步驟實際上沒有契約。

#### G3（阻擋級）補償 worker 永遠 `COMPLETED`，失敗不會讓 workflow 失敗

- `cancel_payment`／`cancel_delivery`／`cancel_order` 都回傳 `Map<String,Object>`（`ConductorWorkers.java:108-128`），沒有回傳 `TaskResult`、沒有設定 `FAILED`。
- 以 `orkes-conductor-client:2.1.0` 內含的 `conductor-java-sdk:3.9.30-orkes` 反組譯確認：`AnnotatedWorker.setValue` 對回傳 `Map` 會 `putAll` 到 output 並設 `COMPLETED`；只有回傳 `TaskResult` 才能自訂 status。
- DAO 又把例外吞掉：`OrdersDAO.updateOrder`（`OrdersDAO.java:62-74`）、`PaymentsDAO.updatePaymentStatus`（`:35-45`）、`ShipmentDAO.cancelShipment`（`:35-45`）都只 `System.out.println`，不拋出、不回傳受影響列數。
- `OrderService.cancelOrder` 對不存在的訂單會先 `getOrder` 得到空物件，再 `setStatus(CANCELLED)` 並以 `orderId=null` 執行 UPDATE（`OrderService.java:67-71`、`OrdersDAO.java:76-96`）——0 rows affected，但回傳空 Map，SDK 標 `COMPLETED`。
- **影響**：補償失敗（DB 掛掉、訂單不存在、付款不存在）對 Conductor 而言都是成功；workflow 可以「補償全成功」結束，但實際上狀態沒回滾。這是 Saga 正確性最直接的漏洞。

#### G4（高）重試非冪等：每次 retry 都新增一筆訂單／付款／外送單

- `OrderService.createOrder` 每次呼叫都產生新的 UUID（`OrderService.java:22-23`），`OrdersDAO.insertOrder` 是純 INSERT（`OrdersDAO.java:32,47`），沒有 upsert／idempotency key。
- `PaymentService.createPayment` 每次呼叫都產生新 `paymentId` 並 INSERT 新 `payments` 列（`PaymentService.java:23-34`、`PaymentsDAO.java:18`）。
- `ShipmentService.createShipment` 每次呼叫都 INSERT 新 `shipments` 列（`ShipmentService.java:19-28`、`ShipmentDAO.java:19`）。
- 重試策略在 Conductor 定義內（B），所以「同一個 task 被重試幾次」目前不可知；但只要重試，就會累積重複資料。
- 更糟的是補償只處理一部分：`PaymentsDAO.readPayment` 用 `while (rs.next())` 讀同一 `orderId` 的所有付款，最後一筆覆蓋前幾筆（`PaymentsDAO.java:47-64`），而 `updatePaymentStatus` 只更新那個 `paymentId`（`:35-45`）——**多重付款只會取消一筆**。`ShipmentDAO.confirmShipment` 則是 `UPDATE ... WHERE orderId=?`（`:47-57`），會把同一個 orderId 底下**先前已取消**的 shipment 一起改回 `CONFIRMED`，等於復活舊嘗試的資料。

#### G5（高）部分寫入沒有交易：失敗會留下孤兒資料，且沒有補償可定址

- `OrderService.createOrder` 依序做：`insertCustomer`（`OrderService.java:32`）→ `insertOrder`（`:48`）。兩者各自開連線，沒有 transaction。
- `OrdersDAO.insertOrder` 內部又分兩段：先 INSERT `orders`（`OrdersDAO.java:32-45`），再 INSERT `orders_details`（`:47-57`），各自開連線（`:34`、`:49`）。
- 若 `orders` 成功、`orders_details` 失敗，`insertOrder` 回傳錯誤字串 → `createOrder` 回傳 `null`（`OrderService.java:50-56`）→ `order_food` 回 `FAILED`。但 `orders` 那列已經存在，且失敗的 task 沒有輸出 `orderId`，所以 `cancel_order` 無法瞄準它。
- 若 customer INSERT 成功、order INSERT 失敗，會留下沒有訂單的 customer；repo 內沒有 `cancel_customer` primitive。
- **影響**：重試會持續製造新的半成品；補償鏈只能處理「有拿到 orderId 的成功訂單」。

#### G6（高）輸入型別／形狀未定，測試 fixture 與 worker DTO 不一致

- `FoodDeliveryRequest.foodItems` 是 `ArrayList<Object>`、`paymentMethod` 是 `Object`（`FoodDeliveryRequest.java:13,18`）。
- 但 worker 端期待 `ArrayList<FoodItem>`（`CheckInventoryRequest.java:10`、`OrderRequest.java:14`）與 `PaymentMethod`（`PaymentRequest.java:10`）。
- `WorkflowServiceTest` 的 fixture 卻傳字串：`setFoodItems(new ArrayList<>(Arrays.asList("burger", "fries")))`、`setPaymentMethod("Credit Card")`（`WorkflowServiceTest.java:76,81`）。
- 若 definition 把 `foodItems` 直接映射到 `items`，SDK 的 `convertValue` 會嘗試把 `"burger"` 轉成 `FoodItem`，失敗；`paymentMethod` 字串轉 `PaymentMethod` 也失敗。若 map 成 null，`PaymentService.makePayment` 的 `payment.getPaymentMethod().getType()` 會 NPE（`PaymentService.java:61`）。
- **影響**：匯出的定義必須包含「形狀轉換」的證據（例如 definition 內的 `JSON_JQ_TRANSFORM`／`INLINE` task，或 worker 端放寬型別）。目前 repo 內**沒有**任何轉換程式碼，所以這是一個必須在 TB2 對照時逐條確認的接縫，不能假設它已存在。

#### G7（中）必填欄位缺失時是 NPE／可重試失敗，不是可讀的失敗

- `OrdersDAO.insertOrder` 直接 `order.getOrderDetails().getItems().toString()`（`OrdersDAO.java:22`）與 `getNotes().isEmpty()`（`:26`）；`items` 或 `notes` 為 `null` 時 NPE。
- `PaymentService.createPayment` 的 `insertPayment` 會 `payment.getPaymentMethod().toString()`（`PaymentsDAO.java:24`）；`method` 為 `null` 時 NPE。
- worker 沒有 `@Valid`／必填檢查；SDK 把例外轉成 `FAILED`（可重試），不是 `FAILED_WITH_TERMINAL_ERROR`（以 `AnnotatedWorker.execute` 反組譯確認）。
- **影響**：若 definition 的重試次數很大或沒有上限，壞資料會造成長時間重試；匯出時必須把 `retryCount`／timeout 一併帶回，否則無法判斷實際行為。

#### G8（中）`order_food` 失敗時沒有 reason，也沒有 output

- 失敗分支只有 `output.put("orderId", null)` 與 `setStatus(FAILED)`（`ConductorWorkers.java:36-39`），沒有 `result.setOutputData(output)`、沒有 `setReasonForIncompletion`。
- 測試 `ConductorWorkersTest.java:116-127` 明確斷言失敗時 output 為空，等於把這個行為固定下來。
- **影響**：definition 無法依錯誤原因分支，也無法把失敗原因帶進補償；可觀測性只剩 Conductor 的 task log。

#### G9（中）task domain 是「兩邊都要對」的隱含契約

- 啟動端把 `taskToDomain["*"]` 設為 `saga`（`WorkflowService.java:31-35`），worker 端由 `conductor.worker.all.domain=saga`（`application.properties:22`）決定 polling domain。
- 兩邊目前一致，但這是兩個獨立設定；若 definition 內的 task 指定了不同 domain，或 worker 端 property 被覆寫，task 會永遠停在 SCHEDULED，且 repo 的單元測試不會發現。
- **影響**：TB2 匯出／匯入時應把 domain 視為定義的一部分（至少寫進 provenance 與驗證清單）。

#### G10（低）環境與 provenance 不明

- `application.properties:16` 預設 `https://play.orkes.io/api`，但 `README.md:11-13` 指示啟動本機 `conductor-standalone`（`:8080`）。
- `application.properties:17-18` 的 key/secret 是 `<key>`／`<secret>` 佔位符，repo 內沒有實際憑證。
- **影響**：TB2 匯出時必須記錄「從哪個 server、哪個 definition 版本、何時、由誰」匯出；否則無法判斷匯出的圖到底是 playground 的還是本機的。

#### G11（低）`customerId` 是裝飾性欄位

- `PaymentRequest.customerId`（`PaymentRequest.java:9`）沒有任何 task 產生，`PaymentService.createPayment` 也沒有使用它。
- **影響**：definition 若要求這個欄位才能通過 schema 驗證，會需要一個 dummy 值；匯出後應標註「未使用」。

#### G12（低）`check_inventory` 不是庫存

- `InventoryService.checkAvailability` 只讀 `restaurants` 的 name 判斷餐廳是否存在（`InventoryService.java:16-22`、`InventoryDAO.java:17-32`）；`items` 參數完全沒用。
- 因此 repo 內沒有「扣庫存」與「回補庫存」的狀態，`check_inventory` 也不需要補償。
- **影響**：若匯出的 definition 或對外說明把這步描述成「保留庫存」，與實作不符；TB2 應在定義旁註明其實際語意。

#### 文件落差（非 Conductor 缺口）

`docs/architecture.md` 的「品質屬性」仍寫「沒有任何測試」（`:342`），「部署缺口」寫「沒有 Gradle wrapper」（`:326`）；但現在 repo 已有 `src/test/java/**`、`gradlew`、`gradle/wrapper/`（Issue #9／PR #14）。引用 `docs/architecture.md` 的現況描述時應以程式碼與 CI 為準。

---

## 5. 影響範圍

### 5.1 受影響模組

| 模組／檔案 | 受影響原因 |
| --- | --- |
| `service/WorkflowService` | 起始輸入的 10 個 key、`correlationId`、`taskToDomain` 是 definition 的入口契約 |
| `workers/ConductorWorkers` | 9 個 task 的名稱、輸入型別、輸出 key、狀態語意 |
| `service/OrderService`、`InventoryService`、`PaymentService`、`ShipmentService` | 每個 task 的實際副作用與補償 primitive |
| `dao/OrdersDAO`、`PaymentsDAO`、`ShipmentDAO`、`InventoryDAO`、`BaseDAO` | 部分寫入、silent failure、重試重複資料的根源 |
| `pojos/*` | DTO 欄位名與型別就是 Conductor `inputParameters` 的 schema |
| `application.properties` | domain、server URL、憑證佔位符 |
| `src/test/java/**` | 測試直接呼叫 worker，不驗證 Conductor 綁定；綠燈不等於契約成立 |
| `docs/architecture.md` | 目前唯一的 Saga 說明，但流程圖標示為推論，且部分現況描述已過時 |

### 5.2 對使用者與下游的影響

- **TB2**：沒有這份盤點就無法知道要匯出哪些 task、每個 task 需要哪些 input、哪些 output 被下游引用；直接匯出定義而不對照 G1–G6，很可能匯出一個「JSON 看起來完整、實際跑起來缺欄位」的定義。
- **維運／除錯**：G3 讓補償失敗不可見；G4/G5 讓重試後資料重複或留下孤兒；G8 讓失敗原因只存在 Conductor log。
- **資料一致性**：`orders`／`orders_details`／`payments`／`shipments` 可能出現重複或半成品；`cancel_payment` 只取消最後一筆付款；`confirmShipment` 可能復活已取消的 shipment。
- **安全與隱私**：workflow 起始輸入包含 `customerEmail`、`customerName`、`customerContact`（`WorkflowService.java:38-40`），會送到外部 Conductor；匯出的定義若含範例輸入或真實資料，可能把 PII 帶進版控。
- **可測試性**：目前 CI 的 42 個綠燈測試無法覆蓋 orchestration；任何「定義與程式碼不同步」的變更都會穿過 CI。

### 5.3 風險路徑

本次分析只新增 `docs/research/` 下的報告，沒有修改 `src/**`、`.github/**`、`catalog-info.yaml` 或 `application.properties`，因此不觸及 H1–H3（`.github/factory/risk-paths.yml:28-59`）。報告中引用的 `Payment*`／`Customer`／`PaymentDetails` 僅為唯讀證據，沒有變更。

---

## 6. 方案比較

| 方案 | 做法 | 優點 | 缺點／風險 | 判定 |
| --- | --- | --- | --- | --- |
| **A. 從 live Conductor 匯出** | 用既有的 `MetadataClient.getWorkflowDef("FoodDeliveryWorkflow", 1)` 與 `getAllTaskDefs()`（依賴已存在，`build.gradle:27`）或 Conductor metadata API，把 `WorkflowDef` + 9 個 `TaskDef` 存成 JSON | 取得**真正的**步驟圖、`inputParameters`、retry/timeout、補償連結；不需要猜 | 需要 read-only 憑證；要先確定哪個 server 是權威（G10）；匯出後仍會 drift，需 pin 版本／hash | **建議作為 source of truth** |
| **B. 由 repo 程式碼反推重建** | 依 9 個 task 名稱、DTO、狀態語意與 `docs/architecture.md` 的推論圖，手寫一份 `WorkflowDef` | 不需要 credentials；完全可 review；可作為文件 | 無法得知實際的 `inputParameters`、decision/fork/loop、retry/timeout、failureWorkflow；會把推論變成「事實」；與 server 上的定義可能完全不同 | **只作為 diff oracle／交叉檢查，不單獨使用** |
| **C. 維持現狀，只寫文件** | 不把定義入庫，只維護 `docs/architecture.md` 與本報告 | 零成本；不動既有流程 | 無法滿足 TB2「匯出入庫」的目標；定義與程式碼繼續各自漂移；新人仍無法從 repo 重建 Saga | **拒絕** |
| **D. A + B diff + CI 契約驗證** | 以 A 匯出；用 B 的清單比對 task 名稱／input／output；加一個 CI 驗證器（JSON schema + 引用解析 + worker 名稱比對），並在 provenance 記錄 server/版本/hash | 取得真實定義，又能抓到「定義引用了不存在的 output」與未來 drift；可在 PR 階段阻擋 | 需要一次性建立 exporter／validator；需要定義「可接受的 diff」規則 | **建議作為目標 operating model** |

**建議組合**：**A 為 source of truth，B 為 review 時的 diff oracle，D 為長期的 CI 防線**。C 不能達成 TB2 的目的，B 單獨使用則是把推論當規格，兩者都應排除。

---

## 7. 建議下一步（可直接開成工作項）

以下工作項的優先序：**7.1 → 7.2 → 7.3 → 7.4**。7.1 是 TB2 的主體；7.2 是匯出前的阻擋級修復；7.3 是匯出後立即應處理的資料一致性問題；7.4 是長期的防線。

### 7.1 TB2-export-workflow-def：匯出 `FoodDeliveryWorkflow` v1 與 9 個 TaskDef

**描述**：用現有 `MetadataClient` 或 metadata API，從權威 Conductor server 匯出 `WorkflowDef`（name=`FoodDeliveryWorkflow`, version=`1`）與 9 個 `TaskDef`，存成可 review 的 JSON；加上 provenance manifest 與唯讀匯出命令。不得把 key/secret 或真實 PII 寫入版控。

**驗收條件草案**

- [ ] AC1：`conductor/workflows/FoodDeliveryWorkflow.v1.json` 存在，`name=="FoodDeliveryWorkflow"`、`version==1`；原樣保留 `schemaVersion`、`timeoutPolicy`、`timeoutSeconds`、`failureWorkflow` 等欄位。
- [ ] AC2：`conductor/taskdefs/` 下含全部 9 個 task 的 `TaskDef`（`order_food`、`check_inventory`、`make_payment`、`ship_food`、`notify_driver`、`notify_customer`、`cancel_payment`、`cancel_delivery`、`cancel_order`），且 `retryCount`／`retryLogic`／timeout 欄位與 server 一致。
- [ ] AC3：驗證器通過——每個 `type=="SIMPLE"` 的 task name ∈ 9 個 worker 名稱；每個 `taskReferenceName` 唯一；每個 `inputParameters` 的 `${...}` 都解析到 `workflow.input.<已知 key>` 或先前 task 的 `output.<已知 key>`（允許清單需含 §3.3 的 10 個 key 與 §3.2 的 output）。
- [ ] AC4：provenance manifest 記錄 server URL、匯出時間、匯出者、`WorkflowDef` 與每個 `TaskDef` 的 SHA-256；同一份 manifest 說明「哪個 server 是權威」與「本機 standalone 僅供開發」。
- [ ] AC5：`grep`／secret scanner 對 `conductor/**` 與 manifest 無命中（不得出現 `conductor.security.client.*` 的值、token、真實 email／電話／卡號）。
- [ ] AC6：匯出→匯入 round-trip 後，normalize 的 JSON 與原檔相等（或差異只限 server 產生的非語意欄位，需在 PR 說明）。
- [ ] AC7：`./gradlew test` 綠燈；新增的驗證器測試（若以 Java 實作）涵蓋「task 名稱不存在」「input 引用不存在」兩個 negative cases。

### 7.2 TB1-contract-fix：修正 G1–G3 的 worker 契約

**描述**：這是程式碼變更工作項（不是本分析）。讓 `ship_food` 輸出 `driverId`（及 `shipmentId` 若需要）；讓 `notify_customer` 有明確可綁定的輸入（最小 DTO 或明確定義為 no-op）；讓三個補償 worker 回傳 `TaskResult`，並在 rollback 失敗時回 `FAILED`／`FAILED_WITH_TERMINAL_ERROR`；DAO 需回傳受影響列數或拋出可辨識的例外，不得 silent no-op。

**驗收條件草案**

- [ ] AC1：`shipFoodTask` 成功時 output 含 `driverId`；`notify_driver` 的契約測試能從 `ship_food` output 綁定到 `DriverNotificationRequest.driverId`。
- [ ] AC2：`notify_customer` 的參數型別與 definition 的 `inputParameters` 一致；若維持 no-op，須在程式與文件明確標示「不寄送通知」，不得讓定義誤以為已通知。
- [ ] AC3：`cancel_*` 成功回 `COMPLETED`、目標不存在或 DB 失敗回非 `COMPLETED`；每個補償起碼有一個成功與一個失敗的單元測試。
- [ ] AC4：`cancel_order` 對不存在的 `orderId` 不再 silent success；`cancel_payment` 能處理同一 `orderId` 的多筆付款（全數取消或明確回報）。
- [ ] AC5：既有 42 個綠燈測試不減少斷言；新增測試涵蓋上述行為。

### 7.3 TB1-idempotency-and-transactions：重試安全與交易邊界

**描述**：為 forward worker 加入冪等鍵（建議由 workflowId + taskReferenceName + orderId 組成，實際設計由人類決定）；`order_food` 的 customer/order/order_details 寫入包在同一個 transaction；`payments`／`shipments` 每個 orderId 只保留一筆有效資料或改用 upsert；`confirmShipment`／`cancelShipment` 不得批次改到舊嘗試的資料。

**驗收條件草案**

- [ ] AC1：同一個冪等鍵重複呼叫 `order_food` 只產生一筆 `orders` 與一筆 `orders_details`。
- [ ] AC2：`orders_details` 寫入失敗時，`orders` 不留下半成品（transaction rollback）。
- [ ] AC3：同一 orderId 重複 `make_payment`／`ship_food` 不會產生多筆有效 `payments`／`shipments`；補償能取消全部相關資料。
- [ ] AC4：新增的重試測試（至少 3 次連續呼叫）在 CI 綠燈，且不依賴外部 Conductor。

### 7.4 TB2-validate-workflow-def-in-ci：定義與程式的契約驗證

**描述**：把 7.1 的驗證器接進 CI，當 worker task 名稱、DTO 欄位或 definition JSON 變更時，自動檢查兩者是否仍對齊；失敗時 PR 紅燈並指出哪個 task／哪個 input 對不上。

**驗收條件草案**

- [ ] AC1：CI 新增一個 job／step 執行 `verify-workflow-def`（名稱可調整），對 `conductor/**` 與 `src/main/java/**/workers/ConductorWorkers.java` 做比對。
- [ ] AC2：故意把一個 `@WorkerTask` 名稱改掉（測試 PR）會讓驗證失敗，訊息指出缺少的 task；還原後綠燈。
- [ ] AC3：故意把一個 `inputParameters` 改成不存在的 `${...}` 會讓驗證失敗；還原後綠燈。
- [ ] AC4：驗證器不需要 Conductor server／憑證即可執行（純靜態 JSON + 原始碼掃描）。

---

## 8. 驗證方式與基線結果

### 8.1 本次已執行

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew test --no-daemon
```

結果：`BUILD SUCCESSFUL`；7 個 test suite，**44 tests／42 passed／2 skipped／0 failed**。2 個 skipped 是 `SeedDataTest` 中已用 `@Disabled` 標記的種子資料缺陷（`SeedDataTest.java:73-84,93-104`），不是本次分析造成。

> 註：本次在沙箱內以 workspace-local `GRADLE_USER_HOME` 執行；CI 使用 `.github/workflows/ci.yml:20` 的 JDK 17 與 `./gradlew test`（`:26-27`），指令與版本一致。

### 8.2 TB2 匯出後應追加的驗證（草案）

```bash
# 必要檔案存在
test -f conductor/workflows/FoodDeliveryWorkflow.v1.json
test "$(ls conductor/taskdefs/*.json | wc -l)" -eq 9

# 定義的 name/version 正確
jq -e '.name=="FoodDeliveryWorkflow" and .version==1' conductor/workflows/FoodDeliveryWorkflow.v1.json

# 所有 SIMPLE task 的名稱都在 worker 清單內（worker 清單由 grep 產生）
jq -r '.. | objects | select(.type?=="SIMPLE") | .name' conductor/workflows/FoodDeliveryWorkflow.v1.json | sort -u

# 不得有 secret 佔位符或憑證
! grep -RInE '(<key>|<secret>|client-secret|Bearer )' conductor/
```

（最後兩條的完整比對邏輯由 7.1／7.4 的驗證器實作；本報告只定義預期行為。）

---

## 9. 未決問題（需人類或具權限者回答）

1. **哪個 Conductor server 是權威？** `application.properties:16` 指向 `play.orkes.io`，README 指向本機 `conductor-standalone`（`README.md:11-13`）。TB2 要從哪一個匯出？是否需要唯讀憑證？
2. **定義目前有版本控管嗎？** 除了 `FoodDeliveryWorkflow` v1 之外，是否有其他版本／別名／環境（dev/prod）？匯出時應以哪個為準？
3. **retry／timeout 放在哪裡？** 是在 `WorkflowDef` 的 task 上，還是 `TaskDef` 上？數值是多少？（這決定 G4/G7 的實際風險。）
4. **補償失敗的語意是什麼？** 應該讓 workflow 失敗並告警，還是 best-effort 後繼續？G3 的修正方向取決於此。
5. **重複訂單／付款／外送單可以接受嗎？** 若不行，冪等鍵要用什麼（workflowId、correlationId、orderId、customerEmail）？
6. **`customerId` 是否為必要欄位？** `PaymentService` 目前沒用到；若 definition 要求，需定義來源。
7. **`paymentMethod` 的 JSON 形狀是什麼？** 字串（測試 fixture）還是物件（worker DTO）？這決定 G6 要不要在 definition 加轉換 task。
8. **`notify_customer`／`notify_driver` 是否真的要寄送通知？** 目前是空實作；若 TB2 只匯出定義、不補實作，應在定義與文件標明其為 placeholder。

---

## 10. 附錄：證據索引

| 主題 | 檔案:行號 | 佐證 |
| --- | --- | --- |
| 啟動 workflow 名稱／版本 | `service/WorkflowService.java:24-25` | `FoodDeliveryWorkflow` v1 |
| 固定 correlationId | `service/WorkflowService.java:26` | `api-triggered`（非唯一） |
| task domain | `service/WorkflowService.java:28-35`、`application.properties:22` | `*` → `saga`；worker 端 `conductor.worker.all.domain=saga` |
| 起始輸入 10 keys | `service/WorkflowService.java:37-48`、`WorkflowServiceTest.java:99-110` | 10 個 key，測試斷言 size=10 |
| 9 個 worker | `workers/ConductorWorkers.java:25-128` | `@WorkerTask` 名稱與方法 |
| `order_food` 失敗無 output | `workers/ConductorWorkers.java:36-39`、`ConductorWorkersTest.java:116-127` | output map 未掛上；測試斷言空 |
| `ship_food` 無 output | `workers/ConductorWorkers.java:86-93` | output 宣告但從未 set |
| 補償 worker 回傳 Map | `workers/ConductorWorkers.java:108-128` | 三個 `cancel_*` |
| SDK 對 Map 回傳值設 COMPLETED | `conductor-java-sdk:3.9.30-orkes` 的 `AnnotatedWorker.setValue`（依賴位元組碼） | `Map` → `putAll` + `COMPLETED` |
| SDK 參數綁定 | 同上 `AnnotatedWorker.getParameters` | 無 `@InputParam` 時 `convertValue(task.getInputData(), 型別)` |
| 訂單建立流程 | `service/OrderService.java:21-59` | customer → order → details |
| 訂單／明細分段寫入 | `dao/OrdersDAO.java:32-57` | 兩段各自開連線，無 transaction |
| 訂單不存在時 silent no-op | `service/OrderService.java:67-71`、`dao/OrdersDAO.java:76-96` | `getOrder` 空物件 → UPDATE `orderId=null` |
| 付款取消只更新一筆 | `service/PaymentService.java:52-58`、`dao/PaymentsDAO.java:35-45,47-64` | 讀到最後一筆，按 paymentId 更新 |
| 外送單確認會批次更新 | `dao/ShipmentDAO.java:47-57` | `WHERE orderId=?` 會改到舊嘗試 |
| 庫存檢查只看餐廳 | `service/InventoryService.java:16-22`、`dao/InventoryDAO.java:17-32` | `items` 未使用 |
| 付款僅模擬 | `service/PaymentService.java:60-84` | 只驗到期日即 `return true` |
| 種子資料缺陷 | `dao/BaseDAO.java`（seed 區段）、`SeedDataTest.java:73-84,93-104` | 2 個 skipped 的原因 |
| Conductor wiring | `SagaApplication.java:12`、`build.gradle:27` | `@ComponentScan("io.orkes")` + `orkes-conductor-client:2.1.0` |
| 定義不在 repo | `grep getWorkflowDef/getAllTaskDefs` 無命中；`docs/architecture.md:145-146,210-212` | 流程圖為推論 |
| CI 測試指令 | `.github/workflows/ci.yml:20,26-27` | JDK 17、`./gradlew test` |

---

*本文件為 `agent-analyze` 產出，只涵蓋唯讀分析可證實的內容；標示 B／C 者須由取得 Conductor 定義的人或後續 TB2 工作項確認，不得逕行視為規格。*
