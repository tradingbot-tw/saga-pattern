# 外送（Shipment）模組

> 對照基準：`software-factory` @ `975bb43`（2026-10-21）。
> 本文描述**現行實作**，不是目標規格；與程式碼不一致時以程式碼為準。
> 範圍：`ShipmentService`、`ShipmentDAO`、`Shipment.Status`，以及 `ship_food`／
> `cancel_delivery` 兩個 worker 與其 task 定義。`docs/architecture.md` 的整體架構與
> Saga 流程不在本文重複。

## 1. 角色與檔案

| 檔案 | 職責 |
| --- | --- |
| `src/main/java/io/orkes/example/saga/service/ShipmentService.java` | 對外入口：`createShipment`、`cancelDelivery`、隨機指派司機 `findDriver` |
| `src/main/java/io/orkes/example/saga/dao/ShipmentDAO.java` | `shipments` 的 INSERT、確認、取消，以及 `drivers` 讀取 |
| `src/main/java/io/orkes/example/saga/pojos/Shipment.java` | 外送單 POJO 與 `Status` 列舉 |
| `src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java` | `shipFoodTask`／`cancelDeliveryTask`：把 service 回傳值轉成 `TaskResult` |
| `conductor/taskdefs/ship_food.json`、`cancel_delivery.json` | task 的輸入／輸出契約與重試策略 |
| `src/test/java/io/orkes/example/saga/service/ShipmentServiceTest.java` | service 層行為測試 |
| `src/test/java/io/orkes/example/saga/dao/ShipmentDAOTest.java` | DAO 層行為測試 |

`ShipmentService` 以 static 欄位持有連線字串寫死的 `ShipmentDAO`：
`jdbc:sqlite:food_delivery.db`（`ShipmentService.java:15`）。因此 service 層測試無法注入
資料庫，只能共用應用程式的 DB 檔並以唯一 `orderId` 隔離情境。

`shipments` 由 `BaseDAO.createShipmentTable()` 建立，`address`、`driverId`、`status`、
`createdAt` 為 `NOT NULL`（`BaseDAO.java:153-169`）；司機種子資料為 4 筆，id 1–4
（`BaseDAO.java:200-211`）。

## 2. `createShipment` 的四條路徑

`ShipmentService.createShipment(ShippingRequest)` 的流程（`ShipmentService.java:17-52`）：

1. 以 `orderId`、`deliveryAddress`、`deliveryInstructions` 建立 `Shipment`，並以
   `findDriver()` 取得 `driverId` 後寫入 POJO。
2. 呼叫 `shipmentDAO.insertShipment(shipment)`。**這一步一律先寫入一筆 `SCHEDULED`**；
   寫入失敗就記 log 並回傳 `0`。
3. 以 `readDriver(driverId, driver)` 讀取司機，`driver.name` 初始化為空字串。
4. 名稱為空白（查無司機、`driverId == 0`，或讀取時發生 SQLException）→
   呼叫 `shipmentDAO.cancelShipment(orderId)` 把狀態改為 `CANCELED`，回傳 `0`。
5. 名稱非空白 → 呼叫 `shipmentDAO.confirmShipment(orderId)` 改為 `CONFIRMED`，
   成功後回傳 `driverId`。確認失敗時 `confirmShipment` 會拋出 `DataAccessException`
   （SQLException，或 UPDATE 沒有命中任何列），例外直接往外拋，`createShipment`
   **不會**回傳 `driverId`。

| 路徑 | 條件 | 資料庫留下的結果 | 回傳值 |
| --- | --- | --- | --- |
| 有司機 | INSERT 成功，且該 `driverId` 在 `drivers` 有非空白 `name` | 1 筆外送單，`SCHEDULED` → `CONFIRMED` | `driverId`（實務上為 1–3） |
| 確認失敗 | INSERT 成功、司機存在，但 `confirmShipment` 的 UPDATE 失敗（SQLException 或 0 列） | 1 筆外送單，狀態停在 `SCHEDULED` | 不適用：拋出 `DataAccessException` |
| 找不到司機 | INSERT 成功，但 `drivers.name` 為空白（查無該 id，含 id 0） | 1 筆外送單，`SCHEDULED` → `CANCELED` | `0` |
| 寫入失敗 | `insertShipment` 回傳 `false`（SQLException，例如 `address` 為 `NULL` 違反 `NOT NULL`） | 沒有外送單紀錄（單一 INSERT 陳述式失敗） | `0` |

**「先寫入才取消」是這條補償路徑的關鍵**：司機檢查發生在 INSERT 之後，所以找不到司機
不是「什麼都沒留下」，而是留下一筆 `CANCELED` 的外送單。`ShipmentServiceTest` 明確斷言
這種情況下 `shipments` 仍有 1 筆、狀態為 `CANCELED`（見 §6）。

注意「找不到司機」的判定依據是 `drivers` 查詢結果，不是 `findDriver()` 的回傳值：
`findDriver()` 回 `0` 時 `readDriver(0, ...)` 查無資料、名稱維持空白，一樣走取消路徑。

## 3. `findDriver` 的實作特性與對測試的意義

`findDriver()`（`ShipmentService.java:64-74`）每次呼叫都建立新的 `Random`，最多抽 10 次
`nextInt(4)`（值域 0–3），抽到非 0 就停止並回傳；10 次都抽到 0 才回傳 `0`。

- 種子司機有 4 位（id 1–4），但 `nextInt(4)` 只會產生 0–3，**id 4 永遠不會被指派**。
- 10 次都抽到 0 的機率為 `(1/4)^10 = 1/1,048,576`，約 `9.54 × 10⁻⁷`（約 1e-6）。

對測試的意義：

- **happy path 不需要處理隨機性**：只要 id 1–3 至少有一位存在（種子資料即如此），
  `findDriver()` 幾乎必然回傳非 0，`readDriver` 也讀得到名字。唯一會誤判的機率就是上述
  約 1e-6，測試不需要為此讓步。
- **「找不到司機」不能靠連續抽 0 來模擬**（機率太低，會變成 flaky）。`ShipmentServiceTest`
  改用資料庫操作：暫時刪除 id 1–3 的司機列（id 0 本來就不存在），此時無論
  `findDriver()` 回 0 或 1–3，`readDriver` 都讀不到名字，取消路徑**確定**發生；測試在
  `finally` 還原司機列，避免污染共用 DB 的其他測試。
- 因此 service 測試驗證的是「司機資料不存在 → 自我取消」這個商業條件，而不是隨機數本身。

## 4. 外送單狀態機

`Shipment.Status` 定義四個值：`SCHEDULED`、`CONFIRMED`、`DELIVERED`、`CANCELED`
（`Shipment.java:7-12`）。實際由程式碼產生的轉移如下：

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED: insertShipment
    SCHEDULED --> CONFIRMED: confirmShipment
    SCHEDULED --> CANCELED: cancelShipment
    CONFIRMED --> CANCELED: cancelShipment
    state DELIVERED
    note right of DELIVERED: enum 有定義，但目前無程式碼會設定
```

| 轉移 | 方法 | 實作 | 回傳／錯誤 |
| --- | --- | --- | --- |
| （新建）→ `SCHEDULED` | `insertShipment` | `INSERT ... status='SCHEDULED'`；不讀取 POJO 的 `status` 欄位 | `false`（SQLException 時印訊息） |
| `SCHEDULED` → `CONFIRMED` | `confirmShipment` | `UPDATE ... SET status='CONFIRMED' WHERE orderId=?` | 受影響列數；SQLException 包成 `DataAccessException` |
| `SCHEDULED`／`CONFIRMED` → `CANCELED` | `cancelShipment` | `UPDATE ... SET status='CANCELED' WHERE orderId=?` | 受影響列數；SQLException 包成 `DataAccessException` |
| → `DELIVERED` | 無 | 只有 enum 值，repo 內沒有任何寫入者 | 不適用 |

現行實作有幾個必須知道的特性：

- **UPDATE 沒有狀態前置條件**：SQL 只以 `orderId` 為條件，不檢查目前狀態。因此
  `cancelShipment` 也能取消一筆 `DELIVERED`（若外部寫入過），`confirmShipment` 也能把
  已 `CANCELED` 的列改回 `CONFIRMED`；文件不把這些視為支援的狀態轉移。
- **同一個 `orderId` 的所有列都會被更新**：`WHERE orderId=?` 不是只更新一列。
- **`createShipment` 非冪等**：每次呼叫都 INSERT 新的一列。因此 `ship_food` 的
  `retryCount` 為 `0`（`conductor/taskdefs/ship_food.json:4`），避免重試產生重複外送單。
- **`confirmShipment` 的失敗會回報呼叫端**：SQLException 包成 `DataAccessException`；
  `createShipment` 在 UPDATE 回傳 `0` 時也視為失敗並拋出例外。兩者都不會回傳
  `driverId`，該列會停留在 `SCHEDULED`（service 不自動取消，見 §5）。
- **`DELIVERED` 目前不可達**：它只存在於 enum；`docs/architecture.md` 的資料模型也將它
  列為未被使用的狀態值。

## 5. 為什麼補償流程不重複呼叫 `cancel_delivery`

`conductor/PROVENANCE.md` §4 的補償對照表（欄位為「失敗的步驟／資料庫留下什麼／補償」）
對 `ship_food` 的記載是：

> | `ship_food` | 訂單、付款；出貨失敗時 `ShipmentService` 已自行 `cancelShipment` | `cancel_payment` → `cancel_order`（不重複取消出貨） |

對照 `FoodDeliveryFailureWorkflow.v1.json` 的 `ship_food_ref` 分支（`:66-83`），補償確實
只有 `cancel_payment` → `cancel_order`，沒有 `cancel_delivery`。原因要從
`createShipment` 的兩種失敗情況看：

- **寫入失敗**：外送單根本不存在。若此時排 `cancel_delivery`，`cancelDelivery` 會回傳
  `0`，worker 依 `cancelDeliveryTask` 的規則轉成
  `FAILED_WITH_TERMINAL_ERROR`（"No shipment found for order ..."），反而讓補償流程失敗。
- **找不到司機**：外送單已由 service 自行改成 `CANCELED`。再呼叫 `cancel_delivery`
  雖然會因為 UPDATE 不檢查狀態而回傳 1、回報 `COMPLETED`，但沒有任何額外效果，只是重複。
- **確認失敗**：INSERT 與司機查詢都成功，但 `confirmShipment` 的 UPDATE 失敗。
  外送單已寫入且停在 `SCHEDULED`；service 拋出 `DataAccessException`，worker 回報
  可重試的 `FAILED`。這個情況不在下面「不存在或 `CANCELED`」的保證內。

因此，在 `ship_food` 對每個 `orderId` 只執行一次的前提下，**既有兩種失敗**的保證是：
**外送單要嘛不存在，要嘛已經 `CANCELED`**。補償流程可以安全跳過 `cancel_delivery`，
直接取消付款與訂單。這條保證由 `createShipment` 的「先寫入、再自行取消」維持。

Issue #84 的**確認失敗**則是第三種情況，不在這條保證內：出貨單停在 `SCHEDULED`，
而 `FoodDeliveryFailureWorkflow` 的 `ship_food_ref` 分支目前仍只呼叫 `cancel_payment`
→ `cancel_order`，不會取消它；`ship_food` 的 `retryCount` 又是 0，所以補償完成後
這筆 `SCHEDULED` 出貨單會留在資料庫。這是已知缺口，需由後續工作項決定要讓補償流程
加回 `cancel_delivery`，或讓 service 在確認失敗時自行取消。

`cancel_delivery` 仍用於 `notify_driver_ref`／`notify_customer_ref` 失敗的分支
（`FoodDeliveryFailureWorkflow.v1.json:84-135`）：那時 `ship_food` 已成功，外送單確實
存在（通常為 `CONFIRMED`），才需要補償取消。

`ship_food` worker 的接縫（`ConductorWorkers.java:95-114`）：`createShipment` 回傳非 0
→ `COMPLETED` 且 output 帶 `driverId`；回傳 0 → `FAILED`，不輸出 `driverId`；
`createShipment` 拋出 `DataAccessException`（確認失敗）→ 同樣回 `FAILED`，並把例外
訊息放進 `reasonForIncompletion`，output 不含 `driverId`。由於 `ship_food` 的
`retryCount` 為 0，Conductor 不會重試，workflow 直接進入失敗補償。

## 6. 現有測試各保護哪一行為

### `ShipmentServiceTest`（service 層，7 個測試）

| 測試 | 保護的行為 |
| --- | --- |
| `createShipmentAssignsDriverAndConfirmsShipment` | 有司機時回傳非 0 的 `driverId`、回傳值與落庫的 `driverId` 一致、狀態由 `SCHEDULED` 轉為 `CONFIRMED` |
| `createShipmentCancelsItselfWhenNoDriverIsAvailable` | 找不到司機時回傳 `0`、**仍留下 1 筆**外送單、狀態為 `CANCELED`（「先寫入才取消」與自我補償） |
| `createShipmentReturnsZeroWithoutPersistingWhenInsertFails` | 缺少 `deliveryAddress` 導致 INSERT 失敗時回傳 `0`、沒有留下任何外送單、狀態查詢為 `null` |
| `cancelDeliveryCancelsExistingShipment` | `cancelDelivery` 對既有外送單回傳 1，狀態變為 `CANCELED` |
| `cancelDeliveryOnUnknownOrderReturnsZero` | 查無外送單時回傳 `0`，不拋錯也不留下紀錄（worker 據此回報 terminal error） |
| `createShipmentPropagatesWhenConfirmFails` | `confirmShipment` 的 UPDATE 拋出 SQLException 時 `createShipment` 拋出 `DataAccessException`，且狀態不是 `CONFIRMED` |
| `createShipmentPropagatesWhenConfirmUpdatesNoRows` | `confirmShipment` 更新 0 列時同樣拋出 `DataAccessException`，不得回傳 `driverId` |

輔助方法 `withoutAvailableDrivers` 先備份再刪除 id 1–3 的司機列，於 `finally` 還原；
它不動 `findDriver()` 的實作，讓「找不到司機」成為確定性的資料庫情境（見 §3）。

### `ShipmentDAOTest`（DAO 層，8 個測試）

| 測試 | 保護的行為 |
| --- | --- |
| `insertShipmentStartsAsScheduled` | 新外送單一律以 `SCHEDULED` 寫入；POJO 的 `status`（測試設為 `SENT`）不參與寫入 |
| `confirmShipmentMovesToConfirmed` | `confirmShipment` 把 `SCHEDULED` 轉為 `CONFIRMED` |
| `cancelShipmentMovesConfirmedToCancelled` | `cancelShipment` 可取消已確認的單，回傳 1 列 |
| `cancelShipmentMovesScheduledToCancelled` | `cancelShipment` 也可取消尚未確認（`SCHEDULED`）的單，回傳 1 列 |
| `cancelUnknownShipmentReturnsZero` | 取消不存在的 `orderId` 回傳 0，不再是 silent no-op |
| `insertShipmentFailsWhenAddressMissing` | `address` 為 `NULL` 時 `insertShipment` 回傳 `false`（讓 service 回傳 0） |
| `readDriverDistinguishesKnownFromUnknown` | 既有司機讀得到名字與 id；查無司機時名稱維持空字串——這正是 `createShipment` 判斷「無人可送」的條件 |
| `shipmentStatusEnumIsClosed` | 狀態列舉只有 `SCHEDULED`／`CONFIRMED`／`DELIVERED`／`CANCELED` 四種，且 `valueOf` 可還原 |

兩層測試互補：`ShipmentDAOTest` 固定 DAO 的 primitive（INSERT 起始狀態、UPDATE 的狀態
轉移與受影響列數、失敗訊號）；`ShipmentServiceTest` 固定 orchestration（四條路徑的回傳值、
寫入順序，以及 `ship_food` 補償前提所依賴的自我取消）。

`ConductorWorkersTest` 另以 SQLite TRIGGER 讓 `confirmShipment` 失敗，斷言
`shipFoodFailsRetryablyWhenConfirmFails` 回傳 `FAILED`、`reasonForIncompletion` 保留
資料庫訊息，且 output 不含 `driverId`。

## 7. 已知限制與變更時注意

1. `ShipmentService` 的 DAO 是 static、連線字串寫死，無法注入；service 測試共用
   `food_delivery.db`（`.gitignore` 已排除），以唯一 `orderId` 隔離。
2. 狀態 UPDATE 沒有前置條件，且以 `orderId` 批次更新；同一 `orderId` 有多列時會全部被改。
3. `createShipment` 非冪等，每次呼叫都新增一列；`ship_food` 因此不重試。
4. `confirmShipment` 失敗（SQLException 或 0 列）會讓 `createShipment` 拋出 `DataAccessException`，出貨單停在 `SCHEDULED`，service 不自動取消（見 §5 的已知缺口）。
5. `findDriver` 的值域是 0–3，種子司機 id 4 永遠不會被指派。
6. `DELIVERED` 只有 enum 定義，沒有生產者；若要啟用，需同時補上寫入端與測試。
