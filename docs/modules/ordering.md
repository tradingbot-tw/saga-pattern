# ordering 模組：訂單建立、端點契約與補償語意

> 本文描述 **現況（as-is）**：所有陳述均以當前 `software-factory` trunk 的程式碼為準，
> 附「檔案:行號」證據。若與實作不符，以程式碼為準。
> 涵蓋檔案：`OrderServiceController`、`WorkflowService`、`OrderService`、`OrdersDAO`、
> `ConductorWorkers`（`order_food`／`cancel_order`）與 `conductor/workflows/` 兩個定義檔。
> 系統層視圖見 [docs/architecture.md](../architecture.md)。

## 0. 完整路徑總覽

`ordering` 是系統唯一對外入口到 Saga 的第一步：

```
呼叫端 ──POST /triggerFoodDeliveryFlow──▶ OrderServiceController
                                              │（只轉發，不轉換）
                                              ▼
                                         WorkflowService ──startWorkflow──▶ Conductor
                                                                     │ FoodDeliveryWorkflow v1
                                                                     ▼
                                                        worker: order_food ──▶ OrderService.createOrder
                                                                              ▶ OrdersDAO（SQLite）
   任一正向步驟失敗時（由 FoodDeliveryFailureWorkflow 觸發）：
        worker: cancel_order ──▶ OrderService.cancelOrder ▶ OrdersDAO.cancelOrder
```

## 1. 對外端點 `POST /triggerFoodDeliveryFlow`

### 1.1 controller 只做轉發，不做轉換

`OrderServiceController.java:23-26`：端點將請求體反序列化為 `FoodDeliveryRequest` 後，
**原樣**（同一個物件參照）交給 `WorkflowService.startFoodDeliveryWorkflow()`，並把後者的
回傳值直接包成 `ResponseEntity.ok(...)` 回傳。controller 內：

- **沒有**任何欄位轉換、複製或重新組裝；
- **沒有**輸入驗證（無 `@Valid`、無必填檢查）；
- **沒有**錯誤處理——不會攔截例外、不會改寫回傳內容或 HTTP 狀態碼。

（若請求體不是合法 JSON、無法反序列化，則由 Spring Boot 的預設錯誤處理接手，
不屬於本文描述的應用行為。）

### 1.2 請求欄位（`pojos/FoodDeliveryRequest.java:9-18`）

| 欄位 | 型別 | 說明 |
| --- | --- | --- |
| `customerEmail` | String | 客戶 email（客戶去重的依據，見 §2.2） |
| `customerName` | String | 客戶姓名 |
| `customerContact` | String | 聯絡電話 |
| `restaurantId` | int | 餐廳 id |
| `foodItems` | ArrayList\<Object\> | 餐點項目；**型別寬鬆**，下游 worker 實際預期 `FoodItem` 清單，對齊依賴 Conductor 序列化 |
| `additionalNotes` | ArrayList\<String\> | 訂單備註 |
| `address` | String | 送餐地址 |
| `deliveryInstructions` | String | 外送指令 |
| `paymentAmount` | double | 付款金額 |
| `paymentMethod` | Object | 付款方式；同樣為寬鬆型別 |

### 1.3 回傳值（含啟動失敗的現況）

`WorkflowService.java:22-60` 組裝 `StartWorkflowRequest`（名稱 `FoodDeliveryWorkflow`、
version 1、`correlationId` 固定 `api-triggered`；若設定 `conductor.worker.all.domain`
非空則把所有 task 指向該 domain，`:28-35`），將上列 10 個欄位原樣放進 workflow input（`:37-48`），
然後啟動工作流。回傳有兩種內容，**兩者都是 HTTP 200**（現況，見 `:50-59` 與 controller 的
無條件 `ok()`）：

| 情境 | HTTP | 回應體 |
| --- | --- | --- |
| 工作流啟動成功 | 200 | `{"workflowId": "<Conductor 回傳的 id>"}`（`WorkflowService.java:59`） |
| 啟動拋出任何例外 | **200** | `{"error": "Order creation failure", "detail": "<例外 toString>"}`（`WorkflowService.java:54-56`；同時 `printStackTrace` 到 stdout） |

⚠️ **端點失敗時仍回 HTTP 200 + error map**：狀態碼不區分成功與失敗，呼叫端**必須檢查
回應體是否有 `error` 鍵**，不能只看狀態碼。這是現況，非設計標靶。

### 1.4 請求範例（假資料）

```bash
curl -X POST http://localhost:8081/triggerFoodDeliveryFlow \
  -H 'Content-Type: application/json' \
  -d '{
        "customerEmail": "jane@example.com",
        "customerName": "Jane Doe",
        "customerContact": "+12126781345",
        "restaurantId": 2,
        "foodItems": [{"item": "burger", "quantity": 2}],
        "additionalNotes": ["不要蔥"],
        "address": "43 West 4th Street, New York NY 10024",
        "deliveryInstructions": "放門口",
        "paymentAmount": 35.5,
        "paymentMethod": {"type": "CREDIT_CARD", "number": "4111111111111111",
                          "expiryDate": "12/30", "cvv": "123"}
      }'
```

以上全部為示範假資料（`example.com`／範例卡號）。另請留意：`customerEmail`／
`customerName`／`customerContact` 會被放進 workflow input 送往外部 Conductor server
（`WorkflowService.java:38-40`），變更此路徑時需注意資料外送面（見 architecture.md
「資料流」一节）。

## 2. `createOrder`：order_food worker 的訂單建立

### 2.1 呼叫鏈與輸入對應

工作流定義（`conductor/workflows/FoodDeliveryWorkflow.v1.json`）把端點輸入映射為
`order_food` task 的 `OrderRequest`：`foodItems`→`items`、`additionalNotes`→`notes`、
`address`→`deliveryAddress`。worker `order_food`（`ConductorWorkers.java:26-42`）呼叫
`OrderService.createOrder(orderRequest)`，依回傳值決定 task 結果：

| `createOrder` 回傳 | task 狀態 | task output |
| --- | --- | --- |
| 非 `null`（成功） | `COMPLETED` | `{"orderId": "<uuid>"}` |
| `null`（寫入失敗） | `FAILED` | `orderId` 為 `null`；狀態維持 `FAILED` 由 Conductor retry policy 接管 |

### 2.2 三個寫入步驟（`OrderService.java:21-59`）

`createOrder` 先生成 UUID 作為 `orderId`，依序做三件事（SQLite 檔
`jdbc:sqlite:food_delivery.db`，連線字串寫死於 `:19`）：

1. **建立客戶**：以 `customerEmail`／`customerName`／`customerContact` 組 `Customer`，
   呼叫 `OrdersDAO.insertCustomer()`（`OrdersDAO.java:122-158`）。該步驟是**依 email 的
   upsert**：先查 `customers` 表，email 已存在則沿用既有 id（冪等，重複請求不會建立
   第二位客戶）；不存在才 INSERT 再回讀 id。
   ⚠️ 現況：此內的 `SQLException` 只被 `System.out.println` 吞掉並回傳 `id = 0`，
   **不會**讓 `createOrder` 失敗——此時訂單列仍會以 `customerId = 0` 寫入。
2. **建立訂單**：組 `Order`（`restaurantId`、`deliveryAddress`，狀態固定為
   `Order.Status.PENDING`，`OrderService.java:39`），INSERT 進 `orders` 表。
3. **寫入明細**：`items` 與 `notes` 以列表 `toString()` 的字串形態 INSERT 進
   `orders_details` 表（`OrdersDAO.java:22-30,47-57`）。

### 2.3 失敗回傳 `null` 的契約

`OrdersDAO.insertOrder()` 成敗以**字串**回報：成功回空字串，任一 INSERT 拋
`SQLException` 時回傳錯誤訊息（`OrdersDAO.java:42-45,54-57`）。`createOrder` 只在
`insertOrder` 回非空字串時記錄 `log.error` 並**回傳 `null`**（`OrderService.java:48-56`），
讓 `order_food` worker 轉 `FAILED`（§2.1）。

要點：

- **無交易包覆**：`orders` 與 `orders_details` 是分開的連線各自 INSERT；第一步成功、
  第二步失敗時 `orders` 列已留下（回傳仍是 `null`，但髒列不會被回滾）。
- 觸發失敗的實際例子：`deliveryAddress` 為 `null` 違反 `orders` 表的 `NOT NULL`；
  或 `orderId` 主鍵衝突（UUID 隨機，實務上幾乎不發生，但 DAO 測試以此注入）。
- 成功後可用 `OrderService.getOrder(orderId)`（`OrderService.java:61-65`）經
  `OrdersDAO.readOrder` 回讀（只讀 `orders` 表欄位，不含明細）。

## 3. `cancelOrder` 的補償語意

### 3.1 分層行為

| 層 | 位置 | 行為 |
| --- | --- | --- |
| DAO | `OrdersDAO.cancelOrder`（`OrdersDAO.java:88-98`） | `UPDATE orders SET status='CANCELLED' WHERE orderId=?`；回傳**受影響列數**；`SQLException` 包裝成 `DataAccessException` 丟出 |
| Service | `OrderService.cancelOrder`（`OrderService.java:73-76`） | 直通 DAO，回傳受影響列數：既有訂單 `1`、**查無訂單 `0`**（不拋錯） |
| Worker | `cancel_order`（`ConductorWorkers.java:181-195`） | 回傳值 `>0` → `COMPLETED`；`0` → `FAILED_WITH_TERMINAL_ERROR`（`"No order found for order ..."`）；`DataAccessException` → `FAILED`（可重試） |

這是 Issue #34 定的契約：補償**不是 best-effort**——「查無訂單」必須以 terminal error
讓流程如實失敗，不得當成補償成功。

### 3.2 補償 SQL 的細節

- **只改 `status` 欄位**：與 `OrdersDAO.updateOrder()`（會一併覆寫 `restaurantId`／
  `deliveryAddress`，`OrdersDAO.java:68-80`）不同，`cancelOrder` 不會用補償時不完整的
  `Order` 物件覆寫其他欄位。`updateOrder` 目前**沒有**生產呼叫端（僅 DAO 測試使用）。
- 「受影響列數」是 WHERE 命中的行數，SQL 未檢查原狀態：對**已 `CANCELLED`** 的訂單重複
  `cancel_order` 仍回傳 `1`（補償冪等，worker 仍回報 `COMPLETED`）。
- `WHERE orderId=?` 無狀態限制：任何狀態（含 `PENDING`／`ASSIGNED`／`CONFIRMED`）的
  訂單列都會被直接轉為 `CANCELLED`。

### 3.3 誰觸發 cancel_order

`FoodDeliveryWorkflow` 以 `failureWorkflow: FoodDeliveryFailureWorkflow` 掛上補償流
（`conductor/workflows/FoodDeliveryFailureWorkflow.v1.json`）。補償流先以
`extract_failure_context` 取出失敗 task 名稱與 `orderId`，再由 SWITCH 依失敗點決定補償
順序（由近而遠）：

| 失敗的 task | 依序執行的補償 |
| --- | --- |
| `order_food`（含 `defaultCase`） | 無（尚未有已完成步驟） |
| `check_inventory` | `cancel_order` |
| `make_payment` | `cancel_payment` → `cancel_order` |
| `ship_food` | `cancel_payment` → `cancel_order` |
| `notify_driver` / `notify_customer` | `cancel_delivery` → `cancel_payment` → `cancel_order` |

補償流在 SWITCH 之後一律再執行一個 `notify_customer` placeholder 步驟（非補償分支內、
為線性後續；實際**不寄送**通知，output 帶 `notified=false`，`ConductorWorkers.java:120-126`）。

## 4. 訂單狀態機

`Order.Status` 定義四個狀態（`pojos/Order.java:10-15`），以 `name()` 字串落地於
`orders.status` 欄：

```
（建立）──createOrder──▶ PENDING ──▶ ASSIGNED ──▶ CONFIRMED
                              │                        │
                              └──── cancelOrder ◀──────┘──▶ CANCELLED（終態）
```

- **宣告意圖**：`PENDING → ASSIGNED → CONFIRMED` 為正向線性推進，`CANCELLED` 為補償終態。
- **現況（重要，勿據 enum 推論實作）**：repo 內只有 `PENDING`（建立時，
  `OrderService.java:39`）與 `CANCELLED`（補償，`OrdersDAO.cancelOrder`）會被實際寫入；
  **沒有任何程式碼路徑會設定 `ASSIGNED` 或 `CONFIRMED`**（同 architecture.md 技術債 #4）。
  `ship_food` 階段的「已指派」語意落在 `shipments` 表的 `Shipment.Status`，不是訂單狀態。
- 轉換無守衛：如 §3.2 所述，`UPDATE` 不檢查原狀態，任何行都能被改成 `CANCELLED`；
  正向推進若要實作，需另開工作項。

## 5. 現有測試各保護哪些行為

三個測試皆為 JVM 單元測試（SQLite 暫存檔／應用 DB 檔），**不需要**外部 Conductor，
`./gradlew test` 即可執行。

| 測試檔 | 保護的行為 |
| --- | --- |
| `OrderServiceTest`（service 層契約，4 條） | ① happy path：`createOrder` 回傳 `orderId`，訂單以 `PENDING` 落庫、客戶與明細（`orders_details`）同步行列、`getOrder` 回讀一致；② 寫入失敗（`deliveryAddress=null` 觸發 `NOT NULL`）→ `createOrder` 回傳 `null`（§2.1 worker 契約）、且不留下該客戶的訂單列；③ `cancelOrder` 對既有訂單回傳 `1` 且 DB 狀態轉 `CANCELLED`；④ 查無訂單回傳 `0` 且不寫入任何狀態 |
| `OrderServiceControllerTest`（端點契約，3 條） | 以手寫 `WorkflowService` 替身驗證 controller「只轉發不轉換」：① 請求物件**同一參照**恰好交給對方一次；② 成功時 200 + 回傳 map **同一物件**原樣透出；③ 失敗（error map）時**仍是 200**、內容不改寫（§1.3 現況的防回歸鎖） |
| `OrdersDAOTest`（DAO 層，8 條） | ① `insertOrder` 成功回空字串、`readOrder` 全欄位回讀（含 `PENDING`、`createdAt`、明細行列）；② `updateOrder` PENDING→CANCELLED 回傳 1；③ 更新不存在訂單回傳 0（不得 silent no-op）；④ `cancelOrder` 只改 status——`restaurantId`／`deliveryAddress` 不得被覆寫，查無回 0；⑤ 狀態列舉恰好 4 值、可經 DB 文字往返；⑥ `insertCustomer` 依 email 冪等（重複請求沿用同一 id、表內僅一列）；⑦ 重複 `orderId` → `insertOrder` 回非空錯誤訊息（§2.3 service 據此回 `null` 的依據）；⑧ `readOrder` 查無訂單不拋錯、欄位保持未設定 |

對應關係：端點契約（§1）由 `OrderServiceControllerTest` 鎖住；`createOrder` 回傳 `null`
的契約（§2.3）由 `OrderServiceTest` + `OrdersDAOTest` 的 ⑦ 兩層鎖住；補償語意（§3.1）由
`OrderServiceTest` ③④ + `OrdersDAOTest` ④ 鎖住 service/DAO 兩層的 `1`/`0` 回報。
worker 層（`ConductorWorkersTest`）與工作流定義契約（`WorkflowDefinitionContractTest`）
另屬 workers 模組，不在本文範圍。

---

*本檔為 agent-write-docs（Issue #73 / TB4）產出，描述現況而非目標規格；
文中行號以提交時的 trunk 為準，行為描述以程式碼為準。*
