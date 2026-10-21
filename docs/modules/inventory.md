# inventory 模組：庫存檢查（`check_inventory`）的實際範圍

> 分析基準：`tradingbot-tw/saga-pattern` @ `975bb43`（trunk `software-factory`）。
> 本文為 `agent-write-docs` 產出，**只描述現行實作**；所有陳述均附「證據索引」（§9），
> 行號以該 commit 為準。若與程式碼不符，以程式碼為準。
> 相關但不同的文件：`docs/architecture.md`（系統整體架構）、
> `docs/research/20-tb1-saga-orchestration-inventory.md` §G12（缺口清單角度）。

## 0. 一句話結論（最容易被誤解的一點）

`InventoryService.checkAvailability(restaurantId, items)` **不做任何庫存檢查**。
它只回答一個問題：**`restaurants` 表裡是否存在一個 id 相符、且 `name` 不為空字串的餐廳**。

第二個參數 `items`（要點的食品品項與數量）**在方法體內從頭到尾沒有被讀取過**——
不碰它、不遍歷它、不對它做驗證，傳 `null` 也照常回傳結果（§2.3）。

因此模組名為 inventory、task 名為 `check_inventory`，但 repo 內
**沒有庫存表、沒有品項價格/數量欄位、沒有扣存或回存的寫入**。
本模組是**只讀的存在性檢查**（`conductor/taskdefs/check_inventory.json:3` 的
description 已如此寫：`Check restaurant availability (read-only).`）。

---

## 1. 職責與邊界

| 面向 | 現況 | 證據 |
| --- | --- | --- |
| 對外被誰呼叫 | 只有 `check_inventory` worker（`ConductorWorkers.checkInventoryTask`）呼叫本模組 | `workers/ConductorWorkers.java:44-60` |
| 輸入契約 | `CheckInventoryRequest`：`restaurantId: int`、`items: ArrayList<FoodItem>` | `pojos/CheckInventoryRequest.java:9-10` |
| 讀取什麼資料 | `restaurants` 表（`name`、`address`、`contact` 三欄，但判斷只用 `name`） | `dao/InventoryDAO.java:18` |
| 寫入什麼資料 | **無**。本模組不含任何 INSERT／UPDATE／DELETE | `dao/InventoryDAO.java:17-32`（只有 `executeQuery`） |
| 成功輸出 | **無** output（taskdef 的 `outputKeys` 是空陣列；worker 也不放 `outputData`） | `taskdefs/check_inventory.json:18`、`workers/ConductorWorkers.java:50-59` |
| 失敗語意 | `FAILED_WITH_TERMINAL_ERROR`（不可重試），原因字串固定 `Restaurant is closed` | `workers/ConductorWorkers.java:55-56` |
| 補償 | 不需要補償（沒寫任何資料）；失敗時補償鏈從**上游**的訂單開始收：`check_inventory_ref` → `cancel_order` | `workflows/FoodDeliveryFailureWorkflow.v1.json:38-47`、`conductor/PROVENANCE.md` §4 對照表 |

### 1.1 模組檔案清單（`factory/process.yaml` 的 inventory 模組）

| 檔案 | 角色 |
| --- | --- |
| `src/main/java/io/orkes/example/saga/service/InventoryService.java` | 判斷邏輯（全部 23 行） |
| `src/main/java/io/orkes/example/saga/dao/InventoryDAO.java` | 讀取 `restaurants` 的唯一 SQL |
| `src/main/java/io/orkes/example/saga/pojos/CheckInventoryRequest.java` | worker 的輸入 DTO |
| `src/main/java/io/orkes/example/saga/pojos/FoodItem.java` | `items` 的元素型別（`item`、`quantity` 兩欄；本模組不使用） |
| `src/main/java/io/orkes/example/saga/pojos/Restaurant.java` | 餐廳的 in-memory 載體（同時是 DAO 的「回填目標」） |

> `factory/process.yaml:22-32` 把 `pojos/FoodDeliveryRequest.java` 也列進 inventory 模組的
> paths（它是 REST 端點的輸入模型，其 `foodItems` 就是 `items` 的上游來源），
> 但那屬於 API 邊界（risk path H7），本檔不展開。

---

## 2. `InventoryService.checkAvailability` 的實際判斷邏輯

原始碼（`service/InventoryService.java:16-22`，全方法 7 行）：

```java
public static boolean checkAvailability(int restaurantId, ArrayList<FoodItem> items) {
    Restaurant restaurant = new Restaurant();
    restaurant.setId(restaurantId);
    restaurant.setName("");
    inventoryDAO.readRestaurant(restaurantId, restaurant);
    return !Objects.equals(restaurant.getName(), "");
}
```

### 2.1 判斷式逐行拆解

| 行 | 動作 | 為何重要 |
| --- | --- | --- |
| `:17-19`（`InventoryService.java`） | 新建立 `Restaurant`，把 `id` 設成傳入的 `restaurantId`、`name` 預設為**空字串** `""` | `name` 的這個預設值就是「查無資料」的哨兵值——回傳值實際上是問「`name` 還等於 `""` 嗎」 |
| `:20` | 呼叫 `inventoryDAO.readRestaurant(restaurantId, restaurant)`，**回傳型別是 `void`** | 呼叫端拿不到「查到了幾列」「是否出錯」的程式化訊號，只能靠 `restaurant` 物件有沒有被覆寫來推斷（§3） |
| `:21` | `return !Objects.equals(restaurant.getName(), "")` | 唯一的判斷：`name` **不等於空字串**就視為可用。比較的是空字串 `""`，不是 `null`、不是空白字串 |

### 2.2 判定矩陣（由上述實作直接推出）

| `restaurants` 查詢結果 | `name` 在判斷時的值 | 回傳 | 後續 worker 狀態 |
| --- | --- | --- | --- |
| 查不到該 id（例：999） | 維持預設 `""` | `false` | `FAILED_WITH_TERMINAL_ERROR` |
| 查到，且 `name` 是非空字串（種子：`Mikes`/`Tamarind`/`Thai Place`） | DB 回讀值 | `true` | `COMPLETED` |
| 查到，但 `name` 是空字串 `""` | `""` | `false` | `FAILED_WITH_TERMINAL_ERROR`（即「有餐廳列但名字為空」會被當成查無餐廳） |
| 資料庫連線失敗 | — | **不回傳值**：`DataAccessException`（`RuntimeException`）往上拋（§2.4） | 例外冒到 worker（worker 無 try/catch，不會回報任何 `TaskResult`；SDK 端如何處置不在 repo 可見範圍） |

> 現行種子資料讓「`name` 為空字串」這條路徑在真實環境難以觸發：`restaurants.name` 的 DDL 是
> `text NOT NULL`（`dao/BaseDAO.java:109`），而 `NOT NULL` 並不容許空字串以外的「空值」寫入，
> 但**空字串本身是合法值**——只要有人寫入一筆 `name = ''` 的餐廳，本判斷就會把它當成不存在。

### 2.3 `items` 參數**未被使用**（明確標示）

`checkAvailability` 的第二個參數 `items` 在方法體內的出現次數是 **0 次**：整個檔案裡 `items`
只出現在參數宣告（`service/InventoryService.java:16`），之後沒有任何一讀取、迴圈或驗證。
由此直接推出三個可觀察結果：

1. **品項內容不影響結論**：不管 `items` 是 `null`、空清單、或數量填 9999，結果只取決於餐廳是否存在。
   既有測試把這三件事逐一釘牢（§8）。
2. **沒有「售完」這回事**：repo 內沒有任何表存放品項與庫存量——`restaurants` 只有
   `id`、`name`、`address`、`contact` 四欄（`dao/BaseDAO.java:107-112`）。
3. **`FoodItem.quantity` 全 repo 無人讀取**：`grep getQuantity` 在 `src/main` 與 `src/test` 都無命中；
   品項的用途到 `order_food` 端只被 `toString()` 塞進 `orders_details.items` 當文字（`dao/OrdersDAO.java:22,51`）。

> ⚠️ 這是「task 名稱 ⟂ 實際語意」的落差，`docs/research/20-tb1-saga-orchestration-inventory.md` §G12
> 已把它列為缺口（標題即「`check_inventory` 不是庫存」）。引用本模組時**不要**把它描述成
> 「保留庫存」或「檢查品項可售量」；它描述的是「這間餐廳在資料庫裡存在且有名字」。

### 2.4 連線失敗 ≠ 查無資料（DAO 例外如何冒出來）

`InventoryService` 持有一個 **static final** 的 DAO，連線字串寫死（`service/InventoryService.java:14`）：

```java
private static final InventoryDAO inventoryDAO = new InventoryDAO("jdbc:sqlite:food_delivery.db");
```

`BaseDAO.connect()` 在連線失敗時**丟出** `DataAccessException`（`RuntimeException`，`dao/BaseDAO.java:20-24`，
Issue #34 的產物）。`InventoryDAO.readRestaurant` 的 `catch` 只接 `SQLException`（`dao/InventoryDAO.java:29-31`），
因此 `DataAccessException` **不會被吞掉**，會穿過 `checkAvailability` 冒到 worker。
但 `ConductorWorkers.checkInventoryTask` **沒有** try/catch（`workers/ConductorWorkers.java:44-60`，
對比同檔 `cancel_*` 三個 worker 都有 `catch (DataAccessException)`，`:149-195`）。
淨結果：資料庫不可用時，`check_inventory` 不會回報可重試的 `FAILED`，而是讓例外冒出 worker（本 repo 內沒有任何程式碼捕獲它，因此該 worker 不會回報
任何 `TaskResult` 狀態；Conductor SDK 端如何處置這個例外不在 repo 可見範圍內）。
只有「查無資料／`name` 是空字串」才會走到 `FAILED_WITH_TERMINAL_ERROR` 那條路徑。

---

## 3. `InventoryDAO.readRestaurant` 的行為

原始碼只有 16 行（`dao/InventoryDAO.java:17-32`）。SQL：

```java
String sql = "SELECT name, address, contact FROM restaurants WHERE id = ?";
```

| 行為 | 說明 | 證據 |
| --- | --- | --- |
| 以 `id` 過濾 | `pstmt.setInt(1, restaurantId)`——用的是**方法參數**，不是傳入 `restaurant` 物件上的 `id` 欄位 | `dao/InventoryDAO.java:21` |
| 回填三欄 | 迴圈內依序 `setName`／`setAddress`／`setContact` | `dao/InventoryDAO.java:23-27` |
| **不覆寫 `id`** | SQL 沒選 `id`，所以呼叫端預先設定的 `restaurant.getId()` 原封不動保留（契約，由測試釘牢） | `dao/InventoryDAO.java:18`、`dao/InventoryDAOTest.java:59-68` |
| **查無資料時不拋例外** | `while (rs.next())` 不進迴圈，方法正常結束；回傳型別是 `void`，沒有「找到/沒找到」訊號 | `dao/InventoryDAO.java:23`、`InventoryDAOTest.java:71-79` |
| **查無資料時不覆寫呼叫端預設值** | 三欄都維持呼叫端設定的值（service 正依賴 `name` 保留 `""` 這個事實來判斷不存在） | `InventoryDAOTest.java:81-94` |
| `SQLException` 只印訊息 | `catch (SQLException e) { System.out.println(e.getMessage()); }`——不包裝、不上拋、不通知呼叫端 | `dao/InventoryDAO.java:29-31` |
| 即時資料 | 每次呼叫都重開連線查詢，無快取 | `InventoryDAOTest.java:96-111` |

> ⚠️ 「查無資料」與「SQL 執行階段出錯（例如 `restaurants` 表不存在）」在**呼叫端看到的結果相同**：
> 三欄維持預設值，`checkAvailability` 回傳 `false`，worker 回報 `Restaurant is closed`。
> 這是把 schema／遷移問題誤譯為「商家不存在」的既有缺陷（屬 H4 路徑，修復須由人類接手）。

> 附帶的既有缺陷（**不在本 Issue 修復範圍**，僅如實記錄）：種子資料的 `address`／`contact`
> 值被寫反（`INSERT INTO restaurants(name, address, contact) VALUES('Mikes','+12121231345','<地址>')`，
> `dao/BaseDAO.java:189-192`），因此 `readRestaurant` 回填的 `address` 實際是電話字串。
> 該缺陷由 `SeedDataTest` 的 `@Disabled` 紅燈測試揭露（`dao/SeedDataTest.java:93-110`），
> 對 `checkAvailability` 的回傳值**沒有影響**（判斷只看 `name`）。

---

## 4. 與 `check_inventory` worker 的關係

`@WorkerTask(value = "check_inventory", threadCount = 2, pollingInterval = 300)`
（`workers/ConductorWorkers.java:44-60`）的對應表：

| 條件 | task 狀態 | `reasonForIncompletion` | `outputData` |
| --- | --- | --- | --- |
| `checkAvailability` 回傳 `true` | `COMPLETED` | 無 | 未設定（空） |
| `checkAvailability` 回傳 `false` | `FAILED_WITH_TERMINAL_ERROR` | `Restaurant is closed`（寫死的字串） | 未設定（空） |
| `checkAvailability` 拋出 `DataAccessException` | 例外冒出 worker（該方法無 try/catch） | 不適用 | 不適用 |

worker 把 `request.getItems()` 讀出來並原封不動傳給 service（`workers/ConductorWorkers.java:47-48`）——
這是 `items` 在整條路徑上唯一被「經過」的地方；service 不看它（§2.3），所以綁定存在但**無語意**。

上游的資料綁定（`workflows/FoodDeliveryWorkflow.v1.json:40-46`）：

```json
{ "name": "check_inventory", "taskReferenceName": "check_inventory_ref", "type": "SIMPLE",
  "inputParameters": { "restaurantId": "${workflow.input.restaurantId}",
                       "items": "${workflow.input.foodItems}" } }
```

- `restaurantId` 来自 workflow 起始輸入（REST 端 `FoodDeliveryRequest.restaurantId`）。
- `items` 来自 `${workflow.input.foodItems}`——注意 `FoodDeliveryRequest.foodItems` 的宣告型別是
  `ArrayList<Object>`（`pojos/FoodDeliveryRequest.java:13`），而 worker 端綁定的
  `CheckInventoryRequest.items` 是 `ArrayList<FoodItem>`（`pojos/CheckInventoryRequest.java:10`）；
  兩端型別對齊依賴 Conductor 的序列化行為，**無編譯期保證**（`docs/architecture.md` 技術債 #5）。
  因 `items` 未被讀取，這條接縫目前**不會造成可觀察的失敗**，一旦真的開始讀 `items` 就會變成實際風險。

**為什麼 `retryCount: 3` 救不了失敗**：`check_inventory` 的 TaskDef 宣告
`retryCount: 3`／`retryLogic: FIXED`／`retryDelaySeconds: 10`
（`conductor/taskdefs/check_inventory.json:4-6`；`conductor/PROVENANCE.md` §4 的理由是本步驟唯讀）。
但 `FAILED_WITH_TERMINAL_ERROR` 的語意就是**不可重試**，重試次數只對 `FAILED` 生效——
查無餐廳永遠不會被重試「蒙對」，而是直接讓 workflow 失敗並進入 `failureWorkflow`
（`workflows/FoodDeliveryWorkflow.v1.json:10`），由 `check_inventory_ref` 分支觸發 `cancel_order`
（`workflows/FoodDeliveryFailureWorkflow.v1.json:38-47`）。
若把 Conductor 的逾時／poll 逾時算進來，`timeoutPolicy: TIME_OUT_WF`（`:9`）則會直接讓整個 workflow 失敗。

---

## 5. 已知限制與風險（就緒度）

本模組在 `factory/process.yaml:22-32` 被記為 **`readiness: R0`**，是五個模組中唯一的 R0
（其餘四個是 R1）。`R0` 的判定屬人類裁定（該檔開頭註明「readiness 是人類裁定（HG2）」）。

| # | 限制／風險 | 現況說明 | 證據 |
| --- | --- | --- | --- |
| L1 | **沒有真正的庫存數量** | 只有「餐廳存在且有名字」的判斷；repo 內沒有品項庫存表或庫存欄位，也沒有扣存／回存邏輯 | `dao/InventoryDAO.java:18`、`dao/BaseDAO.java:107-112` |
| L2 | **品項售完不在檢查範圍** | `items` 完全未被讀取（含 `FoodItem.quantity`，全 repo 無人 `getQuantity()`） | `service/InventoryService.java:16`、§2.3 |
| L3 | **`process.yaml` 的 `readiness_evidence` 已過時** | 該欄寫「無任何測試涵蓋 InventoryService／InventoryDAO」，但 Issue #50 之後 `src/test` **已有** `InventoryServiceTest`（7 條）與 `InventoryDAOTest`（5 條）。R0 的**理由**需由人類重新校對（本文件不自行改判定） | `factory/process.yaml:31`、§8 |
| L4 | **`Restaurant is closed` 是誤導性文案** | 原因字串寫死，與實際判準（查無該 id／`name` 為空字串）不完全對應；對端上「餐廳倒店」與「餐廳 id 不存在」是兩回事，現行實作無法區分 | `workers/ConductorWorkers.java:55` |
| L5 | **例外語意不一致（本模組是 DAO 重整的倖存者）** | 其他 DAO 的更新路徑已把 `SQLException` 包裝成 `DataAccessException`（Issue #34）；`readRestaurant` 仍 `System.out.println` 吞掉，且 service/worker 都不分擔這段例外 | `dao/InventoryDAO.java:29-31`、對比 `dao/OrdersDAO.java:78,96` |
| L6 | **無輸入驗證、無效 id 靠查無資料回應** | `restaurantId` 為 0 或負數不報「參數錯誤」，而是查無資料 → 同一個 terminal error；`items` 為 null 也不報參數錯誤 | `InventoryServiceTest.java:57-62,71-76` |
| L7 | **連線字串與 DAO 皆寫死、難以注入** | `static final InventoryDAO`＋硬編碼 `jdbc:sqlite:food_delivery.db`；測試只能共用工作目錄下的同一個 DB 檔（不注入、不 mock） | `service/InventoryService.java:14`、`src/test/java/io/orkes/example/saga/SagaTestDb.java` 類別註解 |
| L8 | **觸及 H4 高風險路徑** | `src/main/java/**/dao/**` 命中 risk-paths H4（systems of record）：任何對 `InventoryDAO` 的修改都會被強制 `risk = 2` → 人類審查。本文件為唯讀產出，未觸碰 | `.github/factory/risk-paths.yml`（H4）、Issue #70 的事前提示 |
| L9 | **判斷依賴種子資料** | 「可通過檢查的餐廳」只有種子 3 間（id 1..3、`Mikes`/`Tamarind`/`Thai Place`）；種子本身是 demo 假資料，且 `address`/`contact` 值寫反（§3） | `dao/BaseDAO.java:183-197`、`docs/architecture.md` §種子資料與已知不一致 |

### 5.1 「要改成真正的庫存檢查」需要動什麼

Issue #70 明確要求把這件事寫清楚——它是最容易被憑空想像的一點。若要把 `items` 變成實際判準，
**至少要同步以下四處**，缺任何一處都會造成版控與執行期分離（TB1/TB2 的根因）：

1. **資料模型**（H4／H6 級風險，需人類決策）：目前 `restaurants` 表不帶品項或庫存量欄位
   （DDL：`id`／`name`／`address`／`contact`）。「品項 → 可銷售數量」需要新的表／欄位與
   寫入路徑（`dao/BaseDAO.java:105-118` 的內嵌 DDL 無遷移機制）。
2. **`InventoryService.checkAvailability`**：開始讀 `items`、回傳「不可用的品項」之類的結構化結果
   （目前是 `boolean`，無法表達「部分可用」）。
3. **worker 的失敗分級**：現在只有一種失敗（`FAILED_WITH_TERMINAL_ERROR`）。若改成需要重試語意的
   情境（例如庫存服務暫時不可用），必須區分 `FAILED`／`FAILED_WITH_TERMINAL_ERROR`，並補上
   `catch (DataAccessException)`（同檔 `cancel_*` 的既有模式，`workers/ConductorWorkers.java:149-195`）。
4. **Conductor 定義檔＋契約測試＋補償鏈**：
   - `conductor/taskdefs/check_inventory.json`（`inputKeys`／`outputKeys`：現在 `outputKeys: []`）
   - `conductor/workflows/FoodDeliveryWorkflow.v1.json:40-46`（`items` 綁定、是否新增輸出引用）
   - `WorkflowDefinitionContractTest` 的七項契約（例如契約 4 要求 task 的 `inputParameters` key
     必須是 worker 參數 POJO 的欄位，`:264-300`）
   - 一旦 `check_inventory` 開始**寫入**（扣存），它就變成需要補償的步驟，必須在
     `FoodDeliveryFailureWorkflow` 的 `check_inventory_ref` 分支加回存 task（現行只補 `cancel_order`，
     見 §1 表最後一列與 `conductor/PROVENANCE.md` 的補償對照表）。
   最後，定義檔變更須依 `conductor/PROVENANCE.md` §2 同步回 Orkes server——**版控是唯一權威來源**，
   只在 UI 改而不匯回，正是 TB1 揭露的原始根因。

> ⚠️ 以上為「若要改」的**影響清單**，不是本文件的規格提議，也不構成已實作的行為。
> 本模組目前（`975bb43`）**沒有**任何扣存/回存、品項級檢查或數量比較。

---

## 6. 與 `make_payment`／`ship_food` 的失敗分級對照（便於理解語意選擇）

| Task | 失敗時的 task 狀態 | TaskDef `retryCount` | 補償分支 |
| --- | --- | --- | --- |
| `check_inventory` | `FAILED_WITH_TERMINAL_ERROR` | `3`（`taskdefs/check_inventory.json:4`） | `cancel_order`（收掉上游已寫的訂單） |
| `make_payment` | `FAILED_WITH_TERMINAL_ERROR` | `0` | `cancel_payment` → `cancel_order` |
| `ship_food` | `FAILED`（`driverId == 0`） | `0` | `cancel_delivery` → `cancel_payment` → `cancel_order` |
| `order_food` | `FAILED`（`orderId` 為 null） | `0` | 無（`defaultCase: []`） |

> 注意**兩件事要分開讀**：`retryCount` 是 TaskDef 的宣告，`FAILED`／`FAILED_WITH_TERMINAL_ERROR`
> 是 worker 給的語意。terminal error 不受 `retryCount` 影響（§4），而 `ship_food`／`order_food`
> 雖然給的是可重試的 `FAILED`，但其 TaskDef 宣告 `retryCount: 0`——所以**現況下這四個 task
> 都沒有實際的重試**。`conductor/PROVENANCE.md` §4 記了這兩個數值的設計理由
> （寫入不冪等所以 `retryCount: 0`；唯讀所以 `check_inventory` 給 3）。

（對照來源：`workers/ConductorWorkers.java:26-104`、`conductor/taskdefs/*.json:4`、
`workflows/FoodDeliveryFailureWorkflow.v1.json:37-60`。）
`check_inventory` 選擇 terminal error 的理由在程式碼與測試註解中都講得很明確：
「餐廳不存在不該靠重試解決」（`ConductorWorkersTest.java:153-154`）。

---

## 7. 測試涵蓋總覽（誰保護哪一行為）

| 測試檔 | 涵蓋對象 | 保護的關鍵行為 |
| --- | --- | --- |
| `src/test/java/io/orkes/example/saga/service/InventoryServiceTest.java` | `InventoryService.checkAvailability` | 存在→`true`、不存在→`false`、**`items` 不影響結果**（空清單／null／大量品項） |
| `src/test/java/io/orkes/example/saga/dao/InventoryDAOTest.java` | `InventoryDAO.readRestaurant` | 三欄回填、**查無資料不拋例外且不覆寫呼叫端預設值**、不改寫 `id`、即時回讀 |
| `src/test/java/io/orkes/example/saga/workers/ConductorWorkersTest.java:134-156` | `check_inventory` worker | 存在→`COMPLETED`；不存在→`FAILED_WITH_TERMINAL_ERROR` 且 `reason == "Restaurant is closed"` |
| `src/test/java/io/orkes/example/saga/dao/SeedDataTest.java:57-71,93-110` | 種子資料 ↔ 判準 | id 1..3 查得到且 `name` 非空白；id 999 查無時 `name` 維持 `""`（即 §2.2 的第一列）；另以 `@Disabled` 紅燈記錄 address/contact 寫反（§3） |
| `src/test/java/io/orkes/example/saga/workers/WorkflowDefinitionContractTest.java` | `conductor/**` ↔ 程式碼 | task 名稱／input key／輸出 key 的七項契約；`check_inventory` 的 `items` 之所以「綁定但無語意」正是被這類契約允許的現況（無欄位即不會違規） |

### 7.1 各測試保護的具體行為（逐條）

**`InventoryServiceTest`（Issue #50 產出，7 條，全綠）**

| 測試（`:36-82`） | 釘住的行為 |
| --- | --- |
| `checkAvailabilityReturnsTrueForSeededRestaurant`、`...ForEverySeededRestaurant` | 種子餐廳 id 1..3 → `true`（判準走 DB，不是寫死的白名單） |
| `checkAvailabilityReturnsFalseForUnknownRestaurant` | id 999 → `false`，即「查無資料 → 觸發 terminal error」那條路徑 |
| `checkAvailabilityReturnsFalseForNonPositiveIds` | id 0 與負數 → `false`（邊界不另做參數驗證，L6） |
| `emptyItemListDoesNotAffectAvailability` | **空清單不影響結果**（L2 的正面宣言） |
| `nullItemListDoesNotAffectAvailability` | **傳 `null` 也不拋 NPE**——因為實作根本沒讀 `items`（§2.3） |
| `itemListDoesNotMakeUnknownRestaurantAvailable` | 品項**無法**把不存在的餐廳變成可用 |

> 該檔類別註解（`:15-21`）也說明了測試如何繞過 L7：service 寫死連線字串無法注入，
> 所以用 `SagaTestDb.createAppSchema()` 準備應用 DB 與種子餐廳，且**只讀不寫**以免干擾其他測試。

**`InventoryDAOTest`（Issue #50 產出，5 條，全綠）**

| 測試（`:44-111`） | 釘住的行為 |
| --- | --- |
| `readSeededRestaurantPopulatesColumns` | `name`／`address`／`contact` 三欄都被回填 |
| `readRestaurantLeavesCallerSuppliedIdUntouched` | SQL 不含 `id`，故**不得**改寫呼叫端的 `id`（§3） |
| `readUnknownRestaurantDoesNotThrowAndLeavesFieldsNull` | 查無資料 → `assertDoesNotThrow`，三欄維持未設定的 `null` |
| `readUnknownRestaurantKeepsCallerDefaults` | 查無資料 → 呼叫端預設值（`""`／`未設定`）**一律保留**；這是 service 能以 `name` 判存的基礎 |
| `readRestaurantReturnsLatestRowValues` | 更新 DB 後再讀取得最新值（無快取） |

**未涵蓋（誠實列出，目前只能靠閱讀程式碼確認）**：`name` 為空字串的餐廳列（§2.2 第三列）、
`SQLException` 被 `System.out.println` 吞掉後結果與「查無」不可區分（§3 的 ⚠️）、
以及 `DataAccessException` 冒出 worker 那條路徑（§2.4，worker 無 try/catch）。
若要補這些，屬**修改高風險路徑**的行爲，依 `factory-stop-rules` 需由人類接手。

---

## 8. 證據索引

| 主題 | 檔案:行號 | 佐證內容 |
| --- | --- | --- |
| 判斷式本體 | `src/main/java/io/orkes/example/saga/service/InventoryService.java:16-22` | `name != ""` 即視為可用；`items` 未被讀取 |
| DAO／連線字串寫死 | `src/main/java/io/orkes/example/saga/service/InventoryService.java:14` | `static final InventoryDAO`，`jdbc:sqlite:food_delivery.db` |
| 讀取 SQL | `src/main/java/io/orkes/example/saga/dao/InventoryDAO.java:18-21` | `SELECT name, address, contact FROM restaurants WHERE id = ?` |
| 查無資料的行為 | `src/main/java/io/orkes/example/saga/dao/InventoryDAO.java:23-27` | `while (rs.next())` 不進迴圈 → 不覆寫、不拋例外 |
| 例外只印訊息 | `src/main/java/io/orkes/example/saga/dao/InventoryDAO.java:29-31` | `catch (SQLException) { System.out.println(...) }` |
| 連線失敗丟可辨識例外 | `src/main/java/io/orkes/example/saga/dao/BaseDAO.java:20-24`、`dao/DataAccessException.java:11` | `connect()` → `DataAccessException`（`RuntimeException`） |
| `restaurants` DDL | `src/main/java/io/orkes/example/saga/dao/BaseDAO.java:105-118` | 只有 `id`／`name`／`address`／`contact`，無品項或庫存量 |
| 種子餐廳 | `src/main/java/io/orkes/example/saga/dao/BaseDAO.java:183-197` | 3 間（`Mikes`/`Tamarind`/`Thai Place`）；`address`/`contact` 值寫反 |
| `createTables("inventory")` 只建餐廳表 | `src/main/java/io/orkes/example/saga/dao/BaseDAO.java:45-47`、`SagaApplication.java:24` | inventory 組別的表 = `restaurants` 一張 |
| worker 狀態對應 | `src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java:44-60` | `true`→`COMPLETED`；`false`→`FAILED_WITH_TERMINAL_ERROR`＋`Restaurant is closed`；無 try/catch |
| `cancel_*` 的對照模式 | `src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java:149-195` | 有 `catch (DataAccessException)` → 可重試 `FAILED` |
| TaskDef | `conductor/taskdefs/check_inventory.json:3-9,14-18` | `read-only` 描述、`retryCount: 3`/`FIXED`/10 秒、`timeoutPolicy: TIME_OUT_WF`、`inputKeys: [restaurantId, items]`、`outputKeys: []` |
| 主流程綁定 | `conductor/workflows/FoodDeliveryWorkflow.v1.json:10,40-46` | `failureWorkflow` 掛載；`restaurantId`／`items` 來自 `${workflow.input.*}` |
| 補償分支 | `conductor/workflows/FoodDeliveryFailureWorkflow.v1.json:31,37-47` | `check_inventory_ref` → 只跑 `cancel_order` |
| 定義檔權威性 | `conductor/PROVENANCE.md` §2、§4 | 版控為唯一權威來源；`check_inventory` 唯讀所以 `retryCount: 3` |
| 輸入 DTO 欄位型別 | `pojos/CheckInventoryRequest.java:9-10`、`pojos/FoodItem.java:9-10`、`pojos/FoodDeliveryRequest.java:13` | `items: ArrayList<FoodItem>` vs `foodItems: ArrayList<Object>` |
| service 層測試 | `src/test/java/io/orkes/example/saga/service/InventoryServiceTest.java:15-83` | 7 條行為測試（§7.1） |
| DAO 層測試 | `src/test/java/io/orkes/example/saga/dao/InventoryDAOTest.java:21-112` | 5 條行為測試（§7.1） |
| worker 層測試 | `src/test/java/io/orkes/example/saga/workers/ConductorWorkersTest.java:134-156` | 狀態與 `reason` 字串 |
| 種子/判準測試 | `src/test/java/io/orkes/example/saga/dao/SeedDataTest.java:57-71,93-110` | id 1..3 可查、999 維持 `""`；address/contact 錯置為 `@Disabled` 紅燈 |
| 契約測試 | `src/test/java/io/orkes/example/saga/workers/WorkflowDefinitionContractTest.java:44-60,264-300` | 版控定義檔 ↔ 程式碼的七項契約 |
| 就緒度 R0 | `factory/process.yaml:22-32` | `readiness: R0` 與（已過時的）`readiness_evidence` |
| H4 高風險路徑 | `.github/factory/risk-paths.yml`（H4：`src/main/java/**/dao/**`） | 觸碰 `dao/**` → `risk = 2`，強制人類審查 |
| 缺口 G12 | `docs/research/20-tb1-saga-orchestration-inventory.md:262-266` | 「`check_inventory` 不是庫存；`items` 未使用」 |
| 架構文件對照 | `docs/architecture.md:120,136,194-196,433,440` | 元件職責、task 清單、失敗語意、證據索引中的庫存條目 |

---

## 9. 如何驗證本文件的描述（可重現）

```bash
# 1) items 在 checkAvailability 方法體內出現幾次（預期：0 次——只在參數宣告行）
grep -n "items" src/main/java/io/orkes/example/saga/service/InventoryService.java
#   → 只命中 :16（方法簽章）

# 2) 品項數量是否在任何地方被讀取（預期：無命中）
grep -rn "getQuantity" src/main src/test

# 3) inventory 組別實際建的表（預期：只建 restaurants）
grep -n -A2 'case "inventory"' src/main/java/io/orkes/example/saga/dao/BaseDAO.java

# 4) check_inventory 的唯一失敗訊號（預期：FAILED_WITH_TERMINAL_ERROR）
grep -n -B2 -A2 "Restaurant is closed" src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java

# 5) 保護上述行為的測試（需 JDK 17；不碰需外部 Conductor 的 E2E）
JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 \
  ./gradlew test --no-daemon \
  --tests "*InventoryServiceTest" --tests "*InventoryDAOTest" --tests "*ConductorWorkersTest"
```

*本文件不新增、也不暗示任何尚未實作的行為（庫存數量檢查、品項售完、扣存/回存皆未實作）。*
*修改 `InventoryDAO`／`InventoryService` 的行為屬 H4 高風險路徑，必須由人類審查與裁定。*
