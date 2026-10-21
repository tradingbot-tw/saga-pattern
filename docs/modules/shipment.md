# 出貨／配送模組（shipment）

> 分析基準：本檔以 `src/main/java/io/orkes/example/saga/service/ShipmentService.java`、
> `src/main/java/io/orkes/example/saga/dao/ShipmentDAO.java` 與其測試的**現行程式碼**為準
> （trunk `software-factory`）。所有陳述附行號證據；若與實作不符，以程式碼為準。
> 目的：把「派車與外送單狀態機」——特別是**找不到司機時 ShipmentService 已自行取消出貨**
> 這條補償前提——寫成看程式的人找得到的文件。此前提原本只存在於
> `conductor/PROVENANCE.md` §4 的補償對照表。

## 1. 模組在 Saga 中的位置

`ship_food` worker 呼叫 `ShipmentService.createShipment`：回傳非 0 的 `driverId` 時 task
`COMPLETED` 並輸出 `driverId`（供下游 `notify_driver` 綁定）；回傳 `0` 時 task `FAILED`
（`src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java:91-103`）。
`ship_food` 的 `retryCount` 為 **0**（`conductor/taskdefs/ship_food.json:4`），理由是寫入不冪等、
重試會產生重複出貨（`conductor/PROVENANCE.md` §4 設計決定表）。

補償端 worker `cancel_delivery` 呼叫 `ShipmentService.cancelDelivery`，回傳受影響列數：
`0`（查無外送單）→ `FAILED_WITH_TERMINAL_ERROR`；資料庫例外 → `FAILED`（可重試）
（`workers/ConductorWorkers.java:165-179`）。

## 2. `createShipment` 的三條路徑

`createShipment`（`ShipmentService.java:16-47`）先組裝 `Shipment` POJO 並以 `findDriver()`
取司機 id（見 §3），再依序走三步：寫入 → 讀司機 → 確認或取消。回傳值 `0` 一律代表失敗。

| 路徑 | 觸發條件 | DB 結果 | 回傳值 |
|---|---|---|---|
| **有司機** | 寫入成功且 `driverId` 對應的司機列存在且 `name` 非空白 | 一筆 `CONFIRMED`（插入時為 `SCHEDULED`，隨後被 `confirmShipment` 更新） | 實際 `driverId`（1–3） |
| **找不到司機** | 寫入成功但 `readDriver` 讀到空白 `name`（含 `findDriver()` 回 0 的情況） | **先寫入再自行取消**：同一筆轉為 `CANCELED`，紀錄仍留在 DB | `0` |
| **寫入失敗** | `insertShipment` 回傳 `false`（如 `address` 缺失違反 NOT NULL（`BaseDAO.java:162`）→ `SQLException` 被捕捉（`ShipmentDAO.java:29-32`）） | **不留任何紀錄**（沒有部分寫入） | `0` |

關鍵時機（「先寫入才取消」）：`ShipmentService.java:27-30` 寫入失敗即 `return 0`，**不會**
執行後續的取消；只有在寫入成功之後、於 `ShipmentService.java:36-40` 發現司機名稱空白時，
才呼叫 `shipmentDAO.cancelShipment(orderId)` 並回 `0`。因此「找不到司機」失敗後 DB 中
**仍留有該訂單的外送紀錄**，狀態為 `CANCELED`；「寫入失敗」則查無紀錄。

「找不到司機」的偵測依據是**資料庫裡讀不到名字**，不是 `driverId == 0` 本身：
服務先以 `name` 空字串初始化 POJO（`ShipmentService.java:32-34`），`readDriver` 查無列時
不改動傳入物件（`ShipmentDAO.java:64-79`），再以 `isBlank()` 判斷
（`ShipmentService.java:36-40`）。所以即使 `findDriver()` 回傳 1–3，只要對應司機列不存在
（或名字為空），同樣走自我取消路徑；而 `driverId == 0` 必走此路徑，因為種子資料沒有
id 0 的司機（§3）。

## 3. `findDriver` 的實作特性

`findDriver()`（`ShipmentService.java:59-69`）：以 `Random.nextInt(4)` 抽 `0–3`，最多重抽
10 次（`while (counter < 10)`），抽到非 0 即回傳；10 次都抽到 0 時回傳 `0`。

- **機率**：每次抽到 0 的機率是 1/4，十次全中為 (1/4)^10 = 1/1,048,576 ≈ **1e-6**。
  換言之回傳 0 極罕見，`createShipment` 幾乎總是用 1–3 的 id 去查司機。
- **可及範圍**：`nextInt(4)` 永遠回傳 0–3。種子司機有 4 位、id 採 AUTOINCREMENT 為 1–4
  （`dao/BaseDAO.java:138-152, 200-211`），因此 **id 4（Tom Cruise）永遠不會被指派**，
  id 0 則本來就不存在——它是「查無司機」的保留值。
- **對測試的意義**：「找不到司機 → 自行取消」這條補償路徑（§2 第二列）**無法靠隨機重試
  穩定觸發**（約百萬分之一）。`ShipmentServiceTest` 因此改用資料操作觸發：暫時刪除
  `findDriver()` 可能抽到的司機列（id 1–3），讓每一次隨機挑選都查無名字，`finally` 還原
  （`src/test/java/io/orkes/example/saga/service/ShipmentServiceTest.java:75-108`）。
  這同時覆蓋「回傳 0」與「抽到 1–3 但列不存在」兩種情境。

## 4. 外送單狀態機

狀態定義於 `Shipment.Status`（`pojos/Shipment.java:7-12`）：`SCHEDULED`、`CONFIRMED`、
`DELIVERED`、`CANCELED` 四種（測試鎖定枚舉為閉集，`ShipmentDAOTest.java:129-136`）。
本 repo 的 DAO 實際寫入的轉換：

```
        insertShipment（一律寫入 SCHEDULED）
                │
                v
           SCHEDULED ──confirmShipment──> CONFIRMED
                │                              │
                │ cancelShipment               │ cancelShipment
                ▼                              ▼
                            CANCELED
```

- `insertShipment` 寫入時**固定** `status = SCHEDULED`，不讀 POJO 的 `status` 欄位
  （`ShipmentDAO.java:15-34`，第 27 行）。
- `confirmShipment`：`UPDATE ... SET status=CONFIRMED WHERE orderId`（`ShipmentDAO.java:52-62`）。
- `cancelShipment`：`UPDATE ... SET status=CANCELED WHERE orderId`，**回傳受影響列數**
  （`ShipmentDAO.java:40-50`）。SCHEDULED 與 CONFIRMED 都可被取消
  （`ShipmentDAOTest.java:75-96`）；回傳 `0` 代表查無可取消的外送單，不再 silent no-op
  （Issue #34）。
- `DELIVERED` 存在於枚舉中，但**現行 repo 沒有任何程式寫入此狀態**（無對應 DAO 方法）；
  送達確認不在本範例的實作範圍。

## 5. 為什麼補償流程不重複呼叫 `cancel_delivery`

補償 workflow `FoodDeliveryFailureWorkflow` 以 `SWITCH` 依失敗點只補償已完成的步驟。
`ship_food` 失敗的決策分支（`ship_food_ref`）只執行 `cancel_payment → cancel_order`，
**不含** `cancel_delivery`；相對地 `notify_driver_ref`／`notify_customer_ref` 分支的補償
序為 `cancel_delivery → cancel_payment → cancel_order`
（`conductor/workflows/FoodDeliveryFailureWorkflow.v1.json` 的 `decisionCases`）。

原因即 §2 的自我取消前提：**`ship_food` 失敗時，`ShipmentService` 已自行處理出貨紀錄**
——找不到司機時已寫入並取消（`CANCELED`），寫入失敗時根本沒有紀錄。補償流程若再呼叫
`cancel_delivery`，後者是 `UPDATE` 已取消列（冪等、無必要），或在寫入失敗情境下對
不存在的紀錄回傳 0、讓 worker 以 `FAILED_WITH_TERMINAL_ERROR` 中斷補償
（`workers/ConductorWorkers.java:165-179`）。
通知步驟失敗則不同：能走到 `notify_*` 代表出貨已成功 `CONFIRMED`，所以那兩個分支需要
`cancel_delivery`。

原始依據為 `conductor/PROVENANCE.md` §4 補償對照表：`ship_food` 列「出貨失敗時
`ShipmentService` 已自行 `cancelShipment`……（不重複取消出貨）」。該表是 Conductor 定義的
權威重建紀錄；本節把它展開成程式碼層面的說明，兩處必須一起維護。

## 6. 現有測試涵蓋

### `ShipmentServiceTest`（`src/test/java/io/orkes/example/saga/service/ShipmentServiceTest.java`，Issue #54）

| 測試 | 保護的行為 |
|---|---|
| `createShipmentAssignsDriverAndConfirmsShipment`（`:110-124`） | 有司機路徑：回傳非 0 `driverId`、與落庫值一致、狀態 `CONFIRMED` |
| `createShipmentCancelsItselfWhenNoDriverIsAvailable`（`:126-138`） | 自我取消前提：回傳 0、**仍留下一筆** `CANCELED` 紀錄（先寫入再取消）——§5 補償設計直接依賴此行為 |
| `createShipmentReturnsZeroWithoutPersistingWhenInsertFails`（`:140-152`） | 寫入失敗路徑：回傳 0 且**零紀錄**（無部分寫入） |
| `cancelDeliveryCancelsExistingShipment`（`:154-169`） | 補償 worker 的 service 端：取消既有外送單回傳 1、狀態 `CANCELED` |
| `cancelDeliveryOnUnknownOrderReturnsZero`（`:171-180`） | 補償邊界：查無外送單回傳 0、不拋錯（worker 據此報 terminal error） |

### `ShipmentDAOTest`（`src/test/java/io/orkes/example/saga/dao/ShipmentDAOTest.java`，Issue #9）

| 測試 | 保護的行為 |
|---|---|
| `insertShipmentStartsAsScheduled`（`:53-62`） | 寫入一律 `SCHEDULED`，POJO `status` 欄位不參與 |
| `confirmShipmentMovesToConfirmed`（`:64-73`） | `SCHEDULED → CONFIRMED` |
| `cancelShipmentMovesConfirmedToCancelled`（`:75-85`） | `CONFIRMED → CANCELED`，回傳受影響列數 1 |
| `cancelShipmentMovesScheduledToCancelled`（`:87-96`） | `SCHEDULED → CANCELED`（尚未確認也可撤銷） |
| `cancelUnknownShipmentReturnsZero`（`:98-103`） | 查無列回傳 0（不再只印訊息） |
| `insertShipmentFailsWhenAddressMissing`（`:105-112`） | NOT NULL 違反 → `false`，讓 service 走 §2 寫入失敗路徑 |
| `readDriverDistinguishesKnownFromUnknown`（`:114-127`） | 種子司機讀到名字；查無時 `name` 維持空字串，使 `isBlank()` 判斷成立（§2 自我取消的偵測機制） |
| `shipmentStatusEnumIsClosed`（`:129-136`） | 狀態枚舉恰好 4 值（§4） |

兩組測試皆不需外部服務：走 SQLite 測試資料庫（service 層因連線字串寫死而沿用應用資料庫檔
並以唯一 orderId 隔離，`ShipmentServiceTest.java:34-35`）。

## 7. 證據索引

| 陳述 | 位置 |
|---|---|
| 三條路徑與「先寫入才取消」 | `ShipmentService.java:16-47`（`:27-30` 寫入失敗回 0；`:36-40` 自我取消；`:41-46` 確認回傳 driverId） |
| `findDriver` 隨機特性 | `ShipmentService.java:59-69` |
| 狀態寫入 | `ShipmentDAO.java:15-34`（SCHEDULED）、`:40-50`（CANCELED，回列數）、`:52-62`（CONFIRMED） |
| 狀態枚舉 | `Shipment.java:7-12` |
| 司機種子資料與 id 範圍 | `BaseDAO.java:138-152, 200-211` |
| worker 狀態對應 | `ConductorWorkers.java:91-103`（ship_food）、`:165-179`（cancel_delivery） |
| 補償分支不含 cancel_delivery | `conductor/workflows/FoodDeliveryFailureWorkflow.v1.json`；`conductor/PROVENANCE.md` §4 補償對照表 |
