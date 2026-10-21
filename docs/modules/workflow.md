# 編排層（Workflow）說明

> 對象：`FoodDeliveryWorkflow`／`FoodDeliveryFailureWorkflow` 兩份定義檔，以及其所依賴的
> 9 個 worker。內容**逐條對照版控檔撰寫**（`conductor/workflows/*.json`、
> `src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java`、
> `src/main/java/io/orkes/example/saga/service/WorkflowService.java`），每個陳述都附出處。
> 若與程式碼不符，以程式碼為準。
>
> **本文與 `conductor/PROVENANCE.md` 的分工**：`PROVENANCE.md` 是**來源紀錄**（定義檔從哪裡來、
> 哪些欄位是推論、設計決定的理由、註冊與實測紀錄）；本文是**行為說明**（編排層怎麼運作）。
> 兩者互補：想知道「為什麼長這樣」讀 PROVENANCE，想知道「怎麼跑」讀本文。

## 1. 啟動：誰把什麼送進 workflow

`WorkflowService.startFoodDeliveryWorkflow`（`service/WorkflowService.java:22-60`）是唯一進入點
（REST `POST /triggerFoodDeliveryFlow`）：

- 名稱 `FoodDeliveryWorkflow`、version `1`、`correlationId` 固定 `api-triggered`（`:24-26`）。
- `taskToDomain`：讀 `conductor.worker.all.domain`（`application.properties:22` 設為 `saga`），
  非空時把 `"*"` 對應到該 domain（`:28-35`）——worker 只領取該 domain 的任務。
- 起始輸入 10 個 key（`:37-48`）：`customerEmail`、`customerName`、`customerContact`、
  `restaurantId`、`foodItems`、`additionalNotes`、`address`、`deliveryInstructions`、
  `paymentAmount`、`paymentMethod`。與主流程定義頂層宣告的 `inputParameters`
  （`FoodDeliveryWorkflow.v1.json:11-22`）逐一比對，由契約檢查 5 守住。

- 啟動失敗（`startWorkflow` 拋出例外）時，只把堆疊印到 stdout，並回傳
  `Map.of("error", "Order creation failure", "detail", ex.toString())`（`:51-57`）；
  控制層仍回 HTTP 200（`controller/OrderServiceController.java:24-26`），不使用 4xx／5xx。

## 2. 主流程：步驟順序與輸入／輸出來源

定義：`conductor/workflows/FoodDeliveryWorkflow.v1.json`；`tasks` 陣列即執行順序（schemaVersion 2
的線性排列），前後依賴靠 `${<taskReferenceName>.output.<key>}` 表達。

| # | task / ref | 類型 | 輸入來源（`inputParameters`） | 輸出 |
| --- | --- | --- | --- | --- |
| 1 | `order_food` / `order_food_ref` | SIMPLE | `customerEmail`、`customerName`、`customerContact`、`restaurantId` ← `${workflow.input.*}`；`items` ← `${workflow.input.foodItems}`；`notes` ← `${workflow.input.additionalNotes}`；`deliveryAddress` ← `${workflow.input.address}`；`deliveryInstructions` ← `${workflow.input.deliveryInstructions}`（`:28-37`） | `orderId` |
| 2 | `check_inventory` / `check_inventory_ref` | SIMPLE | `restaurantId`、`items` ← `${workflow.input.*}`（`:43-46`） | 無 |
| 3 | `make_payment` / `make_payment_ref` | SIMPLE | `orderId` ← `${order_food_ref.output.orderId}`；`amount` ← `${workflow.input.paymentAmount}`；`method` ← `${workflow.input.paymentMethod}`（`:52-56`） | `orderId`、`paymentId`、`paymentStatus`（失敗另帶 `error`） |
| 4 | `ship_food` / `ship_food_ref` | SIMPLE | `orderId` ← `${order_food_ref.output.orderId}`；`deliveryAddress` ← `${workflow.input.address}`；`deliveryInstructions` ← `${workflow.input.deliveryInstructions}`（`:62-66`） | `driverId` |
| 5 | `notify_fork` / `notify_fork_ref` | FORK_JOIN | 無輸入（`:72`），展開兩條平行分支 | — |
| 5a | └ `notify_driver` / `notify_driver_ref` | SIMPLE | `orderId` ← `${order_food_ref.output.orderId}`；`driverId` ← `${ship_food_ref.output.driverId}`；`dropOff` ← `${workflow.input.address}`（`:80-82`） | 無（placeholder，見 §3） |
| 5b | └ `notify_customer` / `notify_customer_ref` | SIMPLE | `orderId` ← `${order_food_ref.output.orderId}`；`customerEmail`、`customerName` ← `${workflow.input.*}`（`:92-94`） | `orderId`、`notified=false`（placeholder，見 §3） |
| 6 | `notify_join` / `notify_join_ref` | JOIN | `joinOn: [notify_driver_ref, notify_customer_ref]`（`:105-108`） | — |

workflow 輸出（`:111-116`）：`orderId`、`paymentId`、`paymentStatus`、`driverId`，全部取自各 task
的 output。定義檔另宣告 `restartable: true`、`timeoutPolicy: ALERT_ONLY`／`timeoutSeconds: 0`、
`workflowStatusListenerEnabled: false`、`failureWorkflow: FoodDeliveryFailureWorkflow`（`:6-10`）。

`notify_driver` 的 `pickUp` 欄位在 repo 內**沒有任何來源可綁**（`DriverNotificationRequest` 有此欄位，
workflow 未綁定），屬 PROVENANCE §6 的待決問題 2。

## 3. 9 個 worker 的職責與失敗語意

實作全在 `workers/ConductorWorkers.java`，以 `@WorkerTask` 註冊；`threadCount`／`pollingInterval`
亦在該註解上（重試／逾時屬 taskdef 與 server 端，repo 內的 taskdef 見 `conductor/taskdefs/`）。

| Task | 方法 | 執行緒／輪詢 | 職責 | 成功 | 失敗 |
| --- | --- | --- | --- | --- | --- |
| `order_food` | `orderFoodTask:27-42` | 3／300ms | `OrderService.createOrder` 建立客戶與訂單 | `COMPLETED`，輸出 `orderId` | `orderId == null` → `FAILED`（taskdef `retryCount: 0`，寫入不冪等故不重試） |
| `check_inventory` | `checkInventoryTask:45-60` | 2／300ms | `InventoryService.checkAvailability`。**現況只查餐廳是否存在**（讀 `restaurants.name` 是否非空，`InventoryService.java:16-22`），**不檢查 `items` 庫存** | `COMPLETED`（無輸出） | 查無餐廳 → `FAILED_WITH_TERMINAL_ERROR`，原因 `Restaurant is closed`（不重試） |
| `make_payment` | `makePaymentTask:63-82` | 2／300ms | `PaymentService.createPayment` 建立付款。**付款為模擬**：只驗證信用卡到期日，通過即 `SUCCESSFUL`（`PaymentService.java:63-86`） | `COMPLETED`，輸出 `orderId`、`paymentId`、`paymentStatus` | 狀態非 `SUCCESSFUL` → `FAILED_WITH_TERMINAL_ERROR`，輸出加 `error` |
| `ship_food` | `shipFoodTask:92-104` | 2／300ms | `ShipmentService.createShipment` 隨機指派司機並建立外送單 | `COMPLETED`，輸出 `driverId` | `driverId == 0` → `FAILED`（可重試語意；但 taskdef `retryCount: 0`）；失敗時**不輸出** `driverId` |
| `notify_driver` | `checkForDriverNotifications:107-110` | 2／300ms | **Placeholder：不寄送任何通知，也不呼叫任何服務**——回傳空 `Map`，由 Conductor SDK 一律標為 `COMPLETED`（這種「回傳 Map 就當成功」的行為見 `ConductorWorkers.java:128-131` 的說明） | `COMPLETED`（無輸出欄位） | 不會失敗（無失敗分支） |
| `notify_customer` | `checkForCustomerNotifications:121-126` | 2／300ms | **Placeholder：不寄送任何通知**；輸出 `notified=false` 明確標示未寄送（Issue #24 / G2） | `COMPLETED`，輸出 `orderId`、`notified=false` | 不會失敗（無失敗分支） |
| `cancel_payment` | `cancelPaymentTask:150-163` | 2／300ms | `PaymentService.cancelPayment`：一次取消同一 `orderId` 的**所有**付款 | `COMPLETED` | 0 列（查無付款）→ `FAILED_WITH_TERMINAL_ERROR`；`DataAccessException` → `FAILED`（可重試，taskdef `retryCount: 3`） |
| `cancel_delivery` | `cancelDeliveryTask:166-179` | 2／300ms | `ShipmentService.cancelDelivery` 取消外送單 | `COMPLETED` | 0 列（查無外送單）→ `FAILED_WITH_TERMINAL_ERROR`；`DataAccessException` → `FAILED`（可重試） |
| `cancel_order` | `cancelOrderTask:182-195` | 2／300ms | `OrderService.cancelOrder` 把訂單狀態改為 `CANCELLED`（只更新狀態欄） | `COMPLETED` | 0 列（查無訂單）→ `FAILED_WITH_TERMINAL_ERROR`；`DataAccessException` → `FAILED`（可重試） |

三種狀態的語意（Conductor 端）：

- `COMPLETED`：步驟成功，輸出可供後續步驟或補償引用。
- `FAILED`：**可重試**失敗，由 server 依 taskdef 的 `retryLogic`／`retryCount` 決定是否重試；重試耗盡後
  workflow 失敗並觸發補償（`retryCount: 0` 的 task 第一次 `FAILED` 就直接失敗）。
- `FAILED_WITH_TERMINAL_ERROR`：**不可重試**失敗，直接讓 workflow 失敗並觸發補償。

三個 `cancel_*` 從 Issue #34 起一律回傳 `TaskResult` 並把原因寫進 `reasonForIncompletion`
（`ConductorWorkers.java:128-147`），補償失敗不是 best-effort。

## 4. 補償流程：依失敗點只補償已完成的步驟

補償由主流程定義上的 `failureWorkflow: FoodDeliveryFailureWorkflow`（`FoodDeliveryWorkflow.v1.json:10`）
觸發：主流程失敗時，Conductor 自動啟動 `conductor/workflows/FoodDeliveryFailureWorkflow.v1.json`，
帶入 `workflowId`、`reason`、`failureStatus`、`failedWorkflow`（失敗 workflow 的完整物件），
以及原流程的 `customerEmail`、`customerName`（該定義 `:10-17` 宣告的輸入；此行為已在 server 實測確認，
見 PROVENANCE §7）。

流程共三步：

1. **`extract_failure_context`**（JSON_JQ_TRANSFORM，`:19-27`）：從 `${workflow.input.failedWorkflow}`
   擷取兩個欄位——
   - `orderId`：先取 `.parent.output.orderId`，取不到時退回 `.parent.tasks[]` 中
     `order_food_ref` 的 `outputData.orderId`（不依賴失敗時 output 是否已計算）。
   - `failedTaskName`：`.parent.failedReferenceTaskNames[0]`，即第一個失敗步驟的 **taskReferenceName**。
2. **`switch_compensate_from`**（SWITCH，`evaluatorType: value-param`、`expression: failedTaskName`，
   `:28-138`）：依 `failedTaskName` 選分支，**只補償該失敗點之前已完成的步驟**，順序與正向相反：

   | SWITCH 分支（失敗點） | 已完成的建立動作 | 執行的補償 |
   | --- | --- | --- |
   | `check_inventory_ref` | 訂單 | `cancel_order` |
   | `make_payment_ref` | 訂單、付款（通常另有一筆 `FAILED` 付款列） | `cancel_payment` → `cancel_order` |
   | `ship_food_ref` | 訂單、付款 | `cancel_payment` → `cancel_order` |
   | `notify_driver_ref` | 訂單、付款、外送單 | `cancel_delivery` → `cancel_payment` → `cancel_order` |
   | `notify_customer_ref` | 訂單、付款、外送單 | `cancel_delivery` → `cancel_payment` → `cancel_order` |
   | 其他（`defaultCase`，`:137`） | — | **空陣列：不排程任何 `cancel_*`**（`order_food_ref` 失敗時資料庫沒留下訂單；`notify_fork_ref`／`notify_join_ref` 這類非 SIMPLE 任務的失敗也走同一分支） |

   **為什麼 `ship_food` 失敗不呼叫 `cancel_delivery`**：`ShipmentService.createShipment` 回傳 `0`
   只有兩種情況（`ShipmentService.java:27-40`）——外送單 `insert` 就失敗（資料庫裡根本沒有外送單），
   或指派不到司機時**已在該方法內自行 `shipmentDAO.cancelShipment(orderId)`**。因此 `ship_food` 轉成
   `FAILED` 時已沒有「仍然有效的外送單」可取消；若再排程 `cancel_delivery`，它查不到可取消的外送單
   會回 `FAILED_WITH_TERMINAL_ERROR`，反而讓整個補償流程失敗。
3. **`notify_customer`**（ref `notify_customer_failure_ref`，`:139-148`）：無條件在最後執行，輸入
   `orderId` ← `${extract_failure_context_ref.output.result.orderId}`，以及 `workflow.input` 的
   `customerEmail`、`customerName`。**注意：這個 worker 目前是 placeholder，不會真的寄送失敗通知**
   （§3）——它存在是為了保留「通知客戶失敗」的接縫，行為以 `notified=false` 為準。

補償流程自身的輸出（`:150-155`）：`orderId`、`failedTaskName`、`failedWorkflowId`、`reason`。

## 5. 契約測試保護什麼

`src/test/java/io/orkes/example/saga/workers/WorkflowDefinitionContractTest.java`（TB3／Issue #48）
把「worker 程式碼」與「`conductor/**` 定義檔」綁在一起，**不連線外部 Conductor server**：JSON 端用
Jackson 讀版控檔；程式碼端用反射讀 `@WorkerTask` 與參數 POJO 欄位、解析 `ConductorWorkers.java`
原始檔的 `output.put`／`result.put` 取得輸出欄位，並用記錄型 client 攔截
`WorkflowService.startFoodDeliveryWorkflow` 的 `StartWorkflowRequest`（不發網路請求）。

7 項檢查（各一個 `@Test`，檢查項目對應 Issue #48「怎麼做」1–7）：

| 檢查 | 測試方法 | 保護的性質 |
| --- | --- | --- |
| 1 | `everySimpleTaskNameHasAWorker:172` | 每個 `SIMPLE` task 的 `name` 都有對應 `@WorkerTask`（不會排程到沒人領的任務） |
| 2 | `everyWorkerIsUsedAndRegistered:200` | 每個 `@WorkerTask` 至少被一個 workflow 使用，且 `conductor/taskdefs/<name>.json` 存在、檔內 `name` 一致 |
| 3 | `taskReferenceNamesAreUnique:239` | `taskReferenceName` 在同一 workflow 內不重複（含 FORK／SWITCH 分支內） |
| 4 | `taskInputKeysMatchPojoFields:269` | `SIMPLE` task 的 `inputParameters` key 都是該 worker 參數 POJO 的實例欄位 |
| 5 | `everyExpressionResolves:313` | 每個 `${...}` 都解析得到：`workflow.input.<key>` 必須在 `WorkflowService` 實際放入的起始輸入中（非 `WorkflowService` 啟動的補償流程則以其自身宣告的 `inputParameters` 為準）；`<ref>.output.<key>` 必須是該 worker 實際輸出的欄位；對 `JSON_JQ_TRANSFORM` 的引用必須以 `result` 開頭，且其 sub-key 要能從 `queryExpression` 解析出來（解析不出來時**回報違規而不是略過**） |
| 6 | `compensationBranchesMatchMainRefs:413` | 補償 workflow 的 SWITCH 分支名稱，都是主 workflow 真實存在的 `taskReferenceName` |
| 7 | `startNameAndVersionMatchDefinition:458` | `WorkflowService` 啟動的名稱與 version，和 JSON 的 `name`／`version` 一致 |

另有 4 個負面測試驗證「檢查本身會紅燈」：`check1FailsOnUnknownTaskName`、`check5FailsOnUnknownOutputKey`、
`check5FailsOnUnknownJqResultKey`、`check5ReportsUnparseableJqExpression`（`:485-551`）；`setUp` 也會先比對
原始檔解析出的 `@WorkerTask` 全集與位元組碼是否一致（`:129-131`），解析器失靈時直接紅燈。

**修改定義時要跑的測試**（`./gradlew test --no-daemon` 全量即可，不需外部服務）：

- `WorkflowDefinitionContractTest`：定義檔 ↔ 程式碼的欄位契約（改 JSON 必跑）。
- `ConductorWorkersTest`：worker 回報給 Conductor 的狀態語意（改 worker 必跑；用 SQLite 測試資料）。
- `WorkflowServiceTest`：啟動請求的組裝與 `taskToDomain`（改 `WorkflowService` 必跑）。

## 6. 重試與逾時從哪裡來

- worker 端只決定**狀態**；重試與否由 taskdef 的 `retryLogic`／`retryCount`／`retryDelaySeconds`
  決定（`conductor/taskdefs/*.json`）。現況：`order_food`、`make_payment`、`ship_food`、
  `notify_driver`、`notify_customer` 為 `retryCount: 0`（寫入不冪等或 placeholder），
  `check_inventory` 與三個 `cancel_*` 為 `retryCount: 3`（唯讀或冪等 UPDATE）。
  理由見 PROVENANCE §4。
- `check_inventory` 的 worker 在查無餐廳時回報 `FAILED_WITH_TERMINAL_ERROR`（不可重試），因此它的
  `retryCount: 3` 對終端錯誤不會生效——重試與否同時存在 taskdef 層與 worker 層兩套設定時，
  解讀以 worker 回報的狀態為準。
- 逾時：task 層 `timeoutSeconds: 300`／`responseTimeoutSeconds: 60`、`timeoutPolicy: TIME_OUT_WF`；
  workflow 層 `ALERT_ONLY`／`timeoutSeconds: 0`（不中斷）。數值為 TB2 採的保守預設，非實測而來
  （PROVENANCE §4）。

## 7. 已知限制（勿當成已完成的功能）

- **兩個通知 worker 都是 placeholder**：`notify_driver` 不做事（空輸出）、`notify_customer` 不寄送
  （`notified=false`）。repo 內沒有任何寄送通知的程式。
- **`check_inventory` 不檢查菜品庫存**：只判斷餐廳存在與否（`items` 帶進去了但未被使用，
  `InventoryService.java:16-22`）。
- **付款是模擬**：只驗證信用卡到期日，不涉及任何真實金流（`PaymentService.java:63-86`）。
- 定義檔是**重建**的（原始定義已無處可匯出），權威來源為版控 `conductor/**`；在 Orkes UI 上改定義
  必須匯出回本目錄並開 PR，否則版控與 server 會分離（PROVENANCE §1、§2）。
