# Conductor 定義來源紀錄（TB2）

> **這份定義是重建的，不是從 server 匯出的。** 本檔說明為什麼、依據是什麼、哪些部分是推論。

## 1. 為什麼是重建

| 查證（2026-10-21） | 結果 |
|---|---|
| `developer.orkescloud.com`（tradingbot.tw@gmail.com，ADMIN 權限的 application key） | workflow 定義 0 筆、TaskDef 0 筆、執行紀錄 0 筆 |
| `play.orkes.io`（`application.properties` 與上游原文使用的 Playground） | 所有網址 302 轉址到 `developer.orkescloud.com`；Playground 已改為 Developer Edition，原公開範例定義未遷移 |
| 上游 `eugenp/tutorials` 的 `microservices-modules/saga-pattern` | 只有程式碼、`build.gradle`、`README.md`、`application.properties`，沒有附定義 JSON |

原始定義已無處可匯出。依 TB1 報告（`docs/research/20-tb1-saga-orchestration-inventory.md` §6）的方案 B，以程式碼與公開描述重建；重建結果自本次起即為權威定義。

## 2. 權威來源

- **版控**：本目錄 `conductor/**` 是唯一權威來源。
- **執行環境**：已於 2026-10-21 註冊到 `https://developer.orkescloud.com/api`（tradingbot.tw 的 Orkes 帳號）。本機 `conductor-standalone` 僅供開發。
- 之後若在 Orkes UI 修改定義，必須匯出回本目錄並開 PR，否則版控與 server 會再次分離（這正是 TB1 揭露的根因）。

## 3. 依據（每個結構都可追溯）

| 結構 | 依據 |
|---|---|
| workflow 名稱 `FoodDeliveryWorkflow`、version 2 | `WorkflowService.startFoodDeliveryWorkflow`：`setName("FoodDeliveryWorkflow")`、`setVersion(2)`（#136–#139 由 version 1 升版） |
| 起始輸入 10 個 key | 同上，`inputData.put(...)` 的 10 個 key |
| 步驟順序 `order_food → check_inventory → make_payment → ship_food → FORK(notify_driver, notify_customer) → JOIN` | Baeldung〈Saga Pattern in a Microservices Architecture〉§5.1（https://www.baeldung.com/orkes-conductor-saga-pattern-spring-boot） |
| `failureWorkflow: FoodDeliveryFailureWorkflow`；補償為取消付款、取消訂單、通知使用者 | 同文 §5.3 |
| 補償 workflow 的結構：`JSON_JQ_TRANSFORM` 取出業務 id 與失敗的 task → `SWITCH` 依失敗點只補償已完成的步驟；業務 id 取自 `failedWorkflow.output` | 同系列官方範例 `conductor-sdk/conductor-examples-saga-pattern` 的 `cab_service_saga_cancellation_wf.json`（https://github.com/conductor-sdk/conductor-examples-saga-pattern） |
| 各失敗點留下哪些資料、該補償什麼 | `OrderService.createOrder`、`PaymentService.createPayment`、`ShipmentService.createShipment`（見 §4 的補償對照表） |
| 9 個 task 名稱 | `ConductorWorkers` 的 `@WorkerTask` |
| 每個 task 的輸入 key | 對應 worker 參數 POJO 的欄位（`OrderRequest`、`CheckInventoryRequest`、`PaymentRequest`、`ShippingRequest`、`DriverNotificationRequest`、`CustomerNotificationRequest`、`CancelRequest`） |
| task 的輸出 key | `ConductorWorkers` 的 `output.put`／`result.put`（含 #24 新增的 `ship_food.driverId`、`notify_customer.notified`） |

## 4. 重建時的設計決定（原文沒有、由本次決定）

| 決定 | 理由 |
|---|---|
| `order_food`／`make_payment`／`ship_food` 的 `retryCount: 0` | 三者的寫入不冪等（TB1 報告 G4）；重試會產生重複訂單、付款或出貨 |
| `check_inventory` 與三個 `cancel_*` 的 `retryCount: 3` | 唯讀或冪等的 UPDATE；`cancel_*` 在資料庫例外時回 `FAILED`（#34），重試有意義 |
| `orderId` 取 `failedWorkflow.output.orderId`，取不到時改從 `failedWorkflow.tasks` 裡 `order_food_ref` 的輸出取 | 前者是官方範例的取法；後者是保險，不依賴 workflow 失敗時是否計算了 output |
| timeout 數值（task 300 秒、response 60 秒；workflow `ALERT_ONLY`、0） | 原文沒有數值；採保守預設，實際值待營運資料調整 |
| 不寫入 `ownerEmail` | 避免把個資寫進版控；註冊時若 server 要求，由註冊程序補上，不回寫本目錄 |

### 補償對照表（依程式碼判斷每個失敗點留下的資料）

| 失敗的步驟 | 資料庫留下什麼 | 補償（現行） |
|---|---|---|
| 任何步驟 | 不一定；逾時或資料庫例外時可能留下部分資料（含 `SCHEDULED` 出貨單） | `cancel_delivery` → `cancel_payment` → `cancel_order`，皆帶 `allowMissing=true`（查無資料為 no-op） |

補償順序與正向步驟相反（Saga 慣例）。最後一律以 `notify_customer` 通知失敗。

> **2026-10-22（#136–#139）**：原本的設計是用 `SWITCH` 依 `failedReferenceTaskNames[0]`
> 只補償「失敗點之前已完成」的步驟。這個前提不成立：該集合是 Java `HashSet`（順序不可依賴）、
> 含已被重試的失敗嘗試（`check_inventory` 的重試）、且**不含 `TIMED_OUT` 的 task**。
> 反例 A–D 見 `specs/saga.qnt`（`saga_switch`）與 `SagaCompensationDefectTest`。
> 現行設計改為不看失敗點、一律反向取消，並讓 `cancel_*` 對「查無資料」冪等。
> 同時 `order_food` 的 `orderId` 改由 `${workflow.workflowId}` 決定，讓補償在 `order_food`
> 逾時、`output` 尚未計算時仍能定址訂單。

## 5. 推論（未經 server 驗證，列為待確認）

> 2026-10-21 更新：原本的「補償 workflow 讀得到原 workflow 的 `customerEmail`／`customerName`」已在 server 上實測確認（見 §7），移出本節。

| 項目 | 說明 |
|---|---|
| 補償 workflow 的通知使用 `notify_customer` | 原文說「通知使用者失敗」，repo 只有這個客戶通知 worker |

## 6. 待決問題（需人類裁定）

1. ~~早期失敗時的補償語意~~ **已解決**（2026-10-22，改法更新）：不再依失敗點判斷，改成一律反向、冪等的取消（見 §4）。原本的 `SWITCH` 方案在 #136–#139 被移除，因為它依賴不可靠的 `failedReferenceTaskNames[0]`。
2. **未綁定的 POJO 欄位**：`make_payment.customerId`、`notify_driver.pickUp` 沒有任何來源（TB1 報告 §9 第 6 題）。目前保持未綁定；兩個通知 worker 皆為 placeholder，不影響行為。`order_food` 的欄位已改名為 `orderId` 並綁定 `${workflow.workflowId}`。
3. **`paymentMethod` 形狀**：起始輸入直接傳入 `PaymentMethod{type, details}` 物件（TB1 報告 G6）。

## 7. 驗證

- 本機驗證（2026-10-21）：所有 SIMPLE task 名稱皆對應 worker；`taskReferenceName` 無重複；每個 `${...}` 都解析到起始輸入、先前 task 的輸出，或補償 workflow 裡 JQ 擷取 task 的結果；SWITCH 的每個分支名稱都是主流程真實存在的 task ref；每個輸入 key 都是 worker POJO 的欄位；9 個 TaskDef 與 9 個 worker 一一對應；無 email、token、密鑰。
- 反向測試：故意改壞輸出 key、task 名稱、SWITCH 分支名稱、JQ 結果欄位，以及造成 ref 重複，驗證都會失敗。
- JQ 表達式以 `jq` 實測三種情況：output 有 `orderId`、output 為空但 tasks 有、在 `order_food` 就失敗（`orderId` 為 null，走 `defaultCase`）。
- 正式的驗證器與契約測試由 TB3 實作（TB1 報告 §7.1 AC3、AC7）。

### 註冊與 server 實測（2026-10-21）

- 註冊：9 個 TaskDef（`POST /metadata/taskdefs`）、`FoodDeliveryFailureWorkflow`、`FoodDeliveryWorkflow`（`PUT /metadata/workflow`）到 `https://developer.orkescloud.com/api`，皆 HTTP 200；不需要 `ownerEmail`。
- 讀回比對：本目錄 11 個檔案的每一個欄位，在 server 上的值完全一致。server 另加的欄位（建立／更新時間與建立者、`ownerEmail`、預設值）只存在 server，不回寫本目錄。
- 端到端實測（**2026-10-21，SWITCH 版定義，已過時**；現行定義的驗證見 §7.1）：
  - 主流程：`order_food` 收到正確對映的輸入 → 回 `orderId`；`check_inventory` 完成；`make_payment` 收到 `orderId` → 模擬 `FAILED_WITH_TERMINAL_ERROR`。主流程 `FAILED`，`output.orderId` 有值，`failedReferenceTaskNames = [make_payment_ref]`，並自動啟動補償流程。
  - 補償流程的輸入含原 workflow 的 `customerEmail`、`customerName`，另有 `workflowId`、`reason`、`failedWorkflow`。
  - JQ 擷取得到 `orderId = verify-order-1`、`failedTaskName = make_payment_ref`；`SWITCH` 走 `make_payment_ref` 分支：`cancel_payment` → `cancel_order` → `notify_customer`（帶 email 與姓名），**沒有**排程 `cancel_delivery`；補償流程 `COMPLETED`。
  - 執行紀錄：主流程 `ld3t5ead1f4f-cd0d-11f1-aff9-321d2204384c`、補償流程 `ld3t616ba4eb-cd0d-11f1-ba7a-56adf5b8ca14`（correlationId `tb2-verify`）。

### 7.1 #136–#139 的驗證（2026-10-22）

- 本機：`SagaCompensationDefectTest` 以版控中的 JSON 執行補償流程（Conductor `Workflow`/`Task` 物件、
  jackson-jq、真實 `cancel_*` worker 與 SQLite，資料庫錯誤以 SQLite trigger 注入）；同一組測試在
  SWITCH 版定義下有 5 個情境失敗，在現行定義下全部通過。
- **升版為 version 2**：`FoodDeliveryWorkflow.v2.json` 與 `FoodDeliveryFailureWorkflow.v2.json`
  （`WorkflowService` 啟動 version 2；version 1 保留在 server 上，讓進行中的舊 workflow 用它跑完）。
  註冊：9 個 TaskDef（`POST /metadata/taskdefs`）與兩份 workflow（`PUT /metadata/workflow`）皆 HTTP 200；
  讀回確認 server 上的 v2 `order_food.inputParameters.orderId` 為 `${workflow.workflowId}`。
- 雲端 E2E（`OrkesCloudE2EVerificationTest`，4 個情境全部通過，以 API 扮演 worker、各自使用獨立 task domain）：

| 情境 | 主流程狀態 | 補償流程 | 主 workflow id |
|---|---|---|---|
| 付款被拒（`FAILED_WITH_TERMINAL_ERROR`） | `FAILED` | `cancel_delivery`（no-op）→ `cancel_payment` → `cancel_order` | `ld3tdceb7c74-ce1c-11f1-89ad-1a5d2f4e051b` |
| A：`check_inventory` 失敗後重試成功、`ship_food` 失敗 | `FAILED` | 同上；`failedReferenceTaskNames = [check_inventory_ref, ship_food_ref]` 仍完整取消 | `ld3td309cdb0-ce1c-11f1-aff9-321d2204384c` |
| B：`make_payment` 未回報（task `timeoutSeconds` 300 秒逾時） | `TIMED_OUT`（實測約 300 秒） | 同上 | `ld3te1c92e62-ce1c-11f1-9071-46362399c705` |
| D：`confirmShipment` 資料庫錯誤留下 `SCHEDULED` 出貨單 | `FAILED` | 同上，出貨單也被取消 | `ld3tdf7dac0d-ce1c-11f1-ba7a-56adf5b8ca14` |

每個情境都另外斷言 server 傳給 worker 的 `order_food.orderId` 等於 `workflowId`、三個
`cancel_*` 的 `allowMissing` 為 `true`，且補償後資料庫無 `SUCCESSFUL` 付款、無
`SCHEDULED`/`CONFIRMED` 出貨單、訂單為 `CANCELLED`。

> B 的等待時間必須大於 `make_payment` 的 `timeoutSeconds`（300 秒）：`responseTimeoutSeconds`
> 那條路徑不會提早終止 workflow，實測是 task 逾時後才轉 `TIMED_OUT`。

## 8. 檔案雜湊（SHA-256）

| 檔案 | SHA-256 |
|---|---|
| `workflows/FoodDeliveryFailureWorkflow.v2.json` | `1d368759d121efc05187a4f63233534bdf4e16d50f15212e4488c853072fe0f2` |
| `workflows/FoodDeliveryWorkflow.v2.json` | `d73a43aecfd85bd97bf0a9ee502e83469a085e1064fbad7447b4b9384516873c` |
| `taskdefs/cancel_delivery.json` | `9747760e866711d43b4bb95242435bf511b2c6887f065e9b47743541f1d6369f` |
| `taskdefs/cancel_order.json` | `f18f9b359b4e5c0de0aefcd73c7d3950a8d199401f267ee725093d417c48371c` |
| `taskdefs/cancel_payment.json` | `71b4fb474690757cfc139054571ef1e793d4ae2f59ce90da18609f81ed849da0` |
| `taskdefs/check_inventory.json` | `a779a52cdbf28839190d03c66c75c27e2defdd2d896a0aa136aef0b72320e5eb` |
| `taskdefs/make_payment.json` | `8f8054b57799bcfc1c2cd8ac04e1a47c4f1788a480d4d9c02ad6b366fc9a0c53` |
| `taskdefs/notify_customer.json` | `4c6c35c2c65fc1f59d3dbdb4cca80b8eb8b8db010bb19bb47d93bffe56ef73c6` |
| `taskdefs/notify_driver.json` | `88c740e619690e6373b35b1f2529c6e686e340c080cdc992e0d9f3a65705d574` |
| `taskdefs/order_food.json` | `c24a34cde4a7ca042b1bb5d5dee2e6ad2a68d0e1a573be3237122f5c3793c967` |
| `taskdefs/ship_food.json` | `671852df4a9e4fd7e2d8d7bca870e4e1a293fb99f9e46fe36284fc1581e400e7` |
