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
- **執行環境**：合併後註冊到 `https://developer.orkescloud.com/api`（tradingbot.tw 的 Orkes 帳號）。本機 `conductor-standalone` 僅供開發。
- 之後若在 Orkes UI 修改定義，必須匯出回本目錄並開 PR，否則版控與 server 會再次分離（這正是 TB1 揭露的根因）。

## 3. 依據（每個結構都可追溯）

| 結構 | 依據 |
|---|---|
| workflow 名稱 `FoodDeliveryWorkflow`、version 1 | `WorkflowService.startFoodDeliveryWorkflow`：`setName("FoodDeliveryWorkflow")`、`setVersion(1)` |
| 起始輸入 10 個 key | 同上，`inputData.put(...)` 的 10 個 key |
| 步驟順序 `order_food → check_inventory → make_payment → ship_food → FORK(notify_driver, notify_customer) → JOIN` | Baeldung〈Saga Pattern in a Microservices Architecture〉§5.1（https://www.baeldung.com/orkes-conductor-saga-pattern-spring-boot） |
| `failureWorkflow: FoodDeliveryFailureWorkflow`；補償為取消付款、取消訂單、通知使用者 | 同文 §5.3 |
| 9 個 task 名稱 | `ConductorWorkers` 的 `@WorkerTask` |
| 每個 task 的輸入 key | 對應 worker 參數 POJO 的欄位（`OrderRequest`、`CheckInventoryRequest`、`PaymentRequest`、`ShippingRequest`、`DriverNotificationRequest`、`CustomerNotificationRequest`、`CancelRequest`） |
| task 的輸出 key | `ConductorWorkers` 的 `output.put`／`result.put`（含 #24 新增的 `ship_food.driverId`、`notify_customer.notified`） |

## 4. 重建時的設計決定（原文沒有、由本次決定）

| 決定 | 理由 |
|---|---|
| `set_saga_context`（SET_VARIABLE）在 `order_food` 後保存 `orderId` | 補償 workflow 透過 `${workflow.input.failedWorkflow.variables.orderId}` 取得訂單；workflow 失敗時 `outputParameters` 不一定會被計算 |
| `order_food`／`make_payment`／`ship_food` 的 `retryCount: 0` | 三者的寫入不冪等（TB1 報告 G4）；重試會產生重複訂單、付款或出貨 |
| `check_inventory` 與三個 `cancel_*` 的 `retryCount: 3` | 唯讀或冪等的 UPDATE；`cancel_*` 在資料庫例外時回 `FAILED`（#34），重試有意義 |
| 補償 workflow 依 `cancel_delivery → cancel_payment → cancel_order` 的反向順序 | Saga 補償的慣例（與正向步驟相反） |
| timeout 數值（task 300 秒、response 60 秒；workflow `ALERT_ONLY`、0） | 原文沒有數值；採保守預設，實際值待營運資料調整 |
| 不寫入 `ownerEmail` | 避免把個資寫進版控；註冊時若 server 要求，由註冊程序補上，不回寫本目錄 |

## 5. 推論（未經 server 驗證，列為待確認）

| 項目 | 說明 |
|---|---|
| `cancel_delivery` 在補償 workflow 中 | worker 存在，但原文 §5.3 只列取消付款、取消訂單、通知；依 Saga 慣例補上 |
| 補償 workflow 的通知使用 `notify_customer` | 原文說「通知使用者失敗」，repo 只有這個客戶通知 worker |
| 補償 workflow 能讀到原 workflow 的 `customerEmail`、`customerName` | 依 Conductor 開源版行為：failure workflow 的輸入包含原 workflow 輸入，另加 `workflowId`、`reason`、`failureStatus`、`failedWorkflow` |

## 6. 待決問題（需人類裁定）

1. **早期失敗時的補償語意**：若主流程在 `check_inventory` 失敗，此時尚無付款與出貨；依 #34 的語意，`cancel_payment`／`cancel_delivery` 查無目標會回 `FAILED_WITH_TERMINAL_ERROR`，使補償 workflow 本身失敗。要（a）接受並以告警處理、（b）改用 `SWITCH` 依已完成的步驟決定補償範圍，或（c）讓補償 task 設為 `optional`？
2. **未綁定的 POJO 欄位**：`order_food.OrderRequestId`、`make_payment.customerId`、`notify_driver.pickUp` 沒有任何來源（TB1 報告 §9 第 6 題）。目前保持未綁定；兩個通知 worker 皆為 placeholder，不影響行為。
3. **`paymentMethod` 形狀**：起始輸入直接傳入 `PaymentMethod{type, details}` 物件（TB1 報告 G6）。

## 7. 驗證

- 本機驗證（2026-10-21）：所有 SIMPLE task 名稱皆對應 worker；`taskReferenceName` 無重複；每個 `${...}` 都解析到起始輸入、先前 task 的輸出，或主流程以 SET_VARIABLE 設定的變數；每個輸入 key 都是 worker POJO 的欄位；9 個 TaskDef 與 9 個 worker 一一對應；無 email、token、密鑰。
- 反向測試：故意改壞輸出 key 與 task 名稱，驗證會失敗。
- 正式的驗證器與契約測試由 TB3 實作（TB1 報告 §7.1 AC3、AC7）。

## 8. 檔案雜湊（SHA-256）

| 檔案 | SHA-256 |
|---|---|
| `workflows/FoodDeliveryFailureWorkflow.v1.json` | `f13bd5ae64c2f28df33bfe40e67dce9c6dbb0746278d24aa0dd10781111eae87` |
| `workflows/FoodDeliveryWorkflow.v1.json` | `6f4eb685caed48be19dad983be1c2ff543648930c7ce58c1d7922c49ecb7486a` |
| `taskdefs/cancel_delivery.json` | `9747760e866711d43b4bb95242435bf511b2c6887f065e9b47743541f1d6369f` |
| `taskdefs/cancel_order.json` | `f18f9b359b4e5c0de0aefcd73c7d3950a8d199401f267ee725093d417c48371c` |
| `taskdefs/cancel_payment.json` | `71b4fb474690757cfc139054571ef1e793d4ae2f59ce90da18609f81ed849da0` |
| `taskdefs/check_inventory.json` | `a779a52cdbf28839190d03c66c75c27e2defdd2d896a0aa136aef0b72320e5eb` |
| `taskdefs/make_payment.json` | `8f8054b57799bcfc1c2cd8ac04e1a47c4f1788a480d4d9c02ad6b366fc9a0c53` |
| `taskdefs/notify_customer.json` | `4c6c35c2c65fc1f59d3dbdb4cca80b8eb8b8db010bb19bb47d93bffe56ef73c6` |
| `taskdefs/notify_driver.json` | `88c740e619690e6373b35b1f2529c6e686e340c080cdc992e0d9f3a65705d574` |
| `taskdefs/order_food.json` | `c24a34cde4a7ca042b1bb5d5dee2e6ad2a68d0e1a573be3237122f5c3793c967` |
| `taskdefs/ship_food.json` | `671852df4a9e4fd7e2d8d7bca870e4e1a293fb99f9e46fe36284fc1581e400e7` |
