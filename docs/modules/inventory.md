# inventory 模組：庫存檢查

> 本文件描述 **現行實作**，對應 Issue #70；Issue #85 已把「餐廳可用」判斷
> 改為真正的空白檢查（見 §2）。最後核對的程式碼基準為
> `software-factory` 分支上 Issue #85 的修復。
>
> 一句話：`InventoryService.checkAvailability` **不是庫存數量檢查**，而是
> 「餐廳名稱可用」檢查——只讀 `restaurants.name`，名稱非 `null` 且不是整串
> 空白才算可用；傳入的 `items` 完全沒有被讀取。這個模組沒有庫存表、沒有數量、沒有保留或扣減。

## 1. 模組邊界與位置

| 元件 | 位置 | 職責 |
| --- | --- | --- |
| `InventoryService` | `src/main/java/io/orkes/example/saga/service/InventoryService.java` | 唯一對外入口：`checkAvailability(restaurantId, items)` |
| `InventoryDAO` | `src/main/java/io/orkes/example/saga/dao/InventoryDAO.java` | 讀取 `restaurants` 的 `name`／`address`／`contact` |
| `CheckInventoryRequest` | `src/main/java/io/orkes/example/saga/pojos/CheckInventoryRequest.java` | `check_inventory` worker 的輸入 DTO：`restaurantId`、`items` |
| `ConductorWorkers.checkInventoryTask` | `src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java:44-60` | 把 service 的 boolean 轉成 Conductor task 狀態 |
| `check_inventory` TaskDef | `conductor/taskdefs/check_inventory.json` | task 名稱、重試與 timeout 設定 |
| `FoodDeliveryWorkflow` | `conductor/workflows/FoodDeliveryWorkflow.v1.json:39-47` | 把 workflow 輸入映射成 `restaurantId`／`items` |
| `FoodDeliveryFailureWorkflow` | `conductor/workflows/FoodDeliveryFailureWorkflow.v1.json:37-47` | `check_inventory_ref` 失敗時的補償分支 |

`factory/process.yaml:22-32` 把 inventory 模組的範圍列為
`InventoryService`、`InventoryDAO`、`CheckInventoryRequest`、`FoodDeliveryRequest`。
`docs/architecture.md` 只在元件表寫了一句「檢查餐廳是否存在（庫存檢查）」；
本文件補充其餘行為與限制，**不修改** `docs/architecture.md`。

## 2. `checkAvailability` 的實際判斷邏輯

`src/main/java/io/orkes/example/saga/service/InventoryService.java:15-37`：

```java
private static final InventoryDAO inventoryDAO =
        new InventoryDAO("jdbc:sqlite:food_delivery.db");

public static boolean checkAvailability(int restaurantId, ArrayList<FoodItem> items) {
    Restaurant restaurant = new Restaurant();
    restaurant.setId(restaurantId);
    restaurant.setName("");
    inventoryDAO.readRestaurant(restaurantId, restaurant);
    return hasUsableName(restaurant.getName());
}

// 「餐廳可用」判斷（Issue #85）
static boolean hasUsableName(String name) {
    return name != null && !name.isBlank();
}
```

逐步如下：

1. 建立一個空的 `Restaurant`，只設定 `id = restaurantId`。
2. **先把 `name` 設為空字串 `""`**，作為「查無資料」的哨兵值。
3. 呼叫 `inventoryDAO.readRestaurant(restaurantId, restaurant)` 回填資料。
4. 以 `hasUsableName(restaurant.getName())` 判斷：名稱非 `null` 且
   `String.isBlank()` 為 `false`（去除空白後仍有字元）才回 `true`。

### 2.1 判斷結果一覽

| 資料庫狀態 | `restaurant.getName()` 最後的值 | 回傳值 | 說明 |
| --- | --- | --- | --- |
| `restaurants` 沒有該 `id` | `""`（步驟 2 的預設值） | `false` | DAO 不會覆寫，名稱維持空字串 |
| 有該 `id`，`name = 'Mikes'` | `"Mikes"` | `true` | 一般種子餐廳 |
| 有該 `id`，`name = ''` | `""` | `false` | 名稱是空字串，被視為不存在 |
| 有該 `id`，`name = '   '` | `"   "` | `false` | 判斷式為 `name != null && !name.isBlank()`；只有空白視為不可用（Issue #85） |
| 有該 `id`，`name = NULL` | `null` | `false` | `null` 不可用；但 DDL 是 `name text NOT NULL`，無法插入 NULL 餐廳列，此輸入由 `hasUsableName` 直接測試驗證 |
| 查詢發生 `SQLException` | 通常是 `""`（尚未回填） | `false` | DAO 吞掉例外、只印訊息，service 無法區分「查詢失敗」與「查無餐廳」 |

> 自 Issue #85 起「非空白」是精確描述：判斷式為
> `name != null && !name.isBlank()`（package-private seam
> `InventoryService.hasUsableName`）。修前只比對空字串
> （`!Objects.equals(name, "")`），空白字串與 `null` 都會被誤判為可用。

### 2.2 `items` 參數目前的狀態：完全未使用

`checkAvailability` 的方法體內**沒有任何一行讀取 `items`**：

- `items` 是 `null`、空清單、一個品項或一百個品項，對 service 的結果都沒有影響。
- 沒有比對品項、沒有查詢數量、沒有扣庫存、沒有保留（reservation）。
- 因此「品項售完」、「數量不足」、「同一品項重複」都不在本模組的判斷範圍。
- `InventoryServiceTest` 已用空清單、`null` 清單與「items 不能讓不存在的餐廳變成可用」
  三種案例把這個現行行為固定下來（見 §5）。

> 注意 **worker 綁定層**仍會先把 `items` 轉成 `ArrayList<FoodItem>`。
> `CheckInventoryRequest.items` 的型別是 `ArrayList<FoodItem>`，而上游
> `FoodDeliveryRequest.foodItems` 是 `ArrayList<Object>`；若送入的形狀無法轉換，
> 失敗會發生在進入 `checkAvailability` 之前。service 不讀 `items`，不等於
> Conductor 端可以接受任意形狀的 `items`。

### 2.3 資料庫連線與例外行為

| 情況 | 行為 | 對 `checkAvailability` 的影響 |
| --- | --- | --- |
| `DriverManager.getConnection` 失敗 | `BaseDAO.connect()` 丟出 `DataAccessException`（`dao/BaseDAO.java:20-25`） | service 沒有攔截，例外往外拋；`check_inventory` worker 不會回傳 `false` |
| 連線成功、SQL 執行失敗（`SQLException`） | `InventoryDAO.readRestaurant` 的 `catch (SQLException)` 只 `System.out.println(e.getMessage())` | 例外被吞掉，`name` 通常維持 `""`，service 回 `false` |
| 查詢成功但沒有資料列 | 不進 `while (rs.next())`，不呼叫任何 setter | `name` 維持 `""`，service 回 `false` |

**風險**：SQL 執行失敗與「餐廳不存在」在 service 回傳值上無法區分，兩者都會讓 worker
回 `FAILED_WITH_TERMINAL_ERROR`。連線失敗則走另一個路徑（例外），兩者的可觀測性不一致。

## 3. `InventoryDAO.readRestaurant` 的行為

`src/main/java/io/orkes/example/saga/dao/InventoryDAO.java:17-32`：

```sql
SELECT name, address, contact FROM restaurants WHERE id = ?
```

- 只查 `name`、`address`、`contact` 三個欄位，**SQL 不含 `id`**。
- 有資料列時，逐列呼叫 `setName`、`setAddress`、`setContact`（`id` 主鍵，正常只有一列）。
- 查無資料時，`while` 迴圈一次都不執行，**不拋例外、不呼叫 setter**；
  呼叫端原本設定的值原封不動。
- 每次呼叫都重新查詢，沒有快取；DB 更新後可讀到最新值。
- `SQLException` 被捕捉後只印到 stdout，方法正常返回，不通知呼叫端。

### 3.1 「不覆寫呼叫端預設值」的意義

`readRestaurant` 是「有資料才覆寫」的 read-into-object 介面，不是「查無資料就清空物件」。
因此呼叫端必須自己決定「查無資料」要長什麼樣子。`InventoryService` 的作法是先把
`name` 設成 `""`，再靠這個哨兵值判斷：

| 呼叫端傳入的 `Restaurant` | 查無資料後 |
| --- | --- |
| `new Restaurant()`（三個字串都是 `null`） | `name`／`address`／`contact` 都維持 `null` |
| `name = ""`、`address = "未設定"` | 三者都維持呼叫端給的值 |
| `id = 42` | `id` 維持 `42`（SQL 不讀寫 `id`） |

`InventoryDAOTest` 對查無資料保留預設值、以及 `id` 不被覆寫都有測試（見 §5）。

## 4. 與 `check_inventory` worker／workflow 的關係

### 4.1 worker 的狀態映射

`ConductorWorkers.checkInventoryTask`（`workers/ConductorWorkers.java:44-60`）從
`CheckInventoryRequest` 取出 `restaurantId` 與 `items`，呼叫
`InventoryService.checkAvailability`，再依 boolean 回傳：

| service 回傳 | TaskResult 狀態 | `reasonForIncompletion` | output |
| --- | --- | --- | --- |
| `true` | `COMPLETED` | 無 | 無（`outputKeys: []`） |
| `false` | `FAILED_WITH_TERMINAL_ERROR` | 固定字串 `"Restaurant is closed"` | 無 |

`FAILED_WITH_TERMINAL_ERROR` 是終端錯誤，Conductor 不會重試；即使
`conductor/taskdefs/check_inventory.json:4` 設定了 `retryCount: 3`，終端錯誤仍不進入重試。
`check_inventory` 沒有成功 output，TaskDef 的 `outputKeys` 也是空陣列，兩者一致。

> `"Restaurant is closed"` 是**硬編碼的固定原因**，不是從資料庫或例外取得的診斷資訊。
> 查無 id、名稱為空或只有空白、SQL 執行失敗都可能產生這個原因；不應把它解讀成
> 「餐廳存在但打烊」。

### 4.2 workflow 中的位置與補償

`conductor/workflows/FoodDeliveryWorkflow.v1.json:39-47` 把 workflow 輸入
`restaurantId` 與 `foodItems` 映射到 `check_inventory` 的 `restaurantId` 與 `items`。
`check_inventory` 是唯讀步驟，本身沒有庫存副作用，因此**沒有專屬的補償動作**。

`FoodDeliveryFailureWorkflow.v1.json` 的 `switch_compensate_from` 針對
`check_inventory_ref` 失敗只執行 `cancel_order`（`:37-47`）：此時前一個 `order_food`
已建立訂單，但庫存檢查沒有留下任何需要回滾的資料。

失敗流程（依版控內的定義檔）：

```
check_inventory 回 FAILED_WITH_TERMINAL_ERROR
  → FoodDeliveryFailureWorkflow
  → switch_compensate_from 的 check_inventory_ref 分支
  → cancel_order
```

## 5. 現有測試各保護哪一行為

### 5.1 `InventoryServiceTest`

`src/test/java/io/orkes/example/saga/service/InventoryServiceTest.java`

此測試使用 `SagaTestDb.createAppSchema()` 準備 `jdbc:sqlite:food_delivery.db`
（service 把連線字串寫死，無法注入）；既有案例只讀，只有 Issue #85 的空白名稱
邊界測試會暫插入一筆餐廳、斷言後於 `finally` 刪除。種子餐廳為 id 1..3。

| 測試方法 | 保護的行為 |
| --- | --- |
| `checkAvailabilityReturnsTrueForSeededRestaurant`（`:45`） | 存在的種子餐廳（id 1）回 `true` |
| `checkAvailabilityReturnsTrueForEverySeededRestaurant`（`:51`） | 三個種子餐廳（id 1..3）都存在且都可回 `true` |
| `checkAvailabilityReturnsFalseForUnknownRestaurant`（`:60`） | 不存在的 id 999 回 `false`，這是 worker 轉終端錯誤的訊號 |
| `checkAvailabilityReturnsFalseForNonPositiveIds`（`:66`） | id `0` 與負數都不存在，回 `false` |
| `emptyItemListDoesNotAffectAvailability`（`:73`） | 空 `items` 不影響結果，存在餐廳仍回 `true` |
| `nullItemListDoesNotAffectAvailability`（`:80`） | `items = null` 不會 NPE，存在餐廳仍回 `true` |
| `itemListDoesNotMakeUnknownRestaurantAvailable`（`:87`） | `items` 不能讓不存在的餐廳變成可用，仍回 `false` |
| `checkAvailabilityReturnsFalseForBlankNameRestaurant`（`:102`） | `name` 只有空白（`"   "`）的餐廳回 `false`（Issue #85） |
| `nullNameIsNotUsable`（`:114`） | `hasUsableName(null)` 回 `false`；DDL `name NOT NULL` 無法插入 NULL 餐廳，改以判斷式 seam 直接驗證（Issue #85） |

### 5.2 `InventoryDAOTest`

`src/test/java/io/orkes/example/saga/dao/InventoryDAOTest.java`

此測試使用 `SagaTestDb.tempUrl("inventory")` 建立獨立的暫存資料庫，不與其他測試共享狀態。

| 測試方法 | 保護的行為 |
| --- | --- |
| `readSeededRestaurantPopulatesColumns`（`:46`） | 種子餐廳可回讀 `name`／`address`／`contact` |
| `readRestaurantLeavesCallerSuppliedIdUntouched`（`:60`） | SQL 不含 `id`，不覆寫呼叫端設定的 `id` |
| `readUnknownRestaurantDoesNotThrowAndLeavesFieldsNull`（`:72`） | 查無資料不拋例外，三個欄位維持 `null` |
| `readUnknownRestaurantKeepsCallerDefaults`（`:83`） | 查無資料不覆寫呼叫端既有預設值（service 靠 `name` 哨兵判斷） |
| `readRestaurantReturnsLatestRowValues`（`:98`） | 每次查詢取得 DB 最新值，不是快取 |

### 5.3 其他相關測試

| 測試 | 保護的行為 |
| --- | --- |
| `ConductorWorkersTest.checkInventoryCompletesForKnownRestaurant`（`:136`） | 種子餐廳 → `COMPLETED` |
| `ConductorWorkersTest.checkInventoryFailsWithTerminalErrorForUnknownRestaurant`（`:146`） | 查無餐廳 → `FAILED_WITH_TERMINAL_ERROR`，原因為 `"Restaurant is closed"` |
| `SeedDataTest.onlySeededRestaurantsAreReadable`（`:59`） | 用 DAO 直接確認 id 1..3 可讀、999 不可讀，且查無資料時 `name` 維持空白 |
| `WorkflowDefinitionContractTest` | 檢查 `check_inventory` 等 SIMPLE task 名稱有對應 worker、taskdef 存在、workflow 的 `inputParameters` key 是 worker POJO 欄位、`${...}` 引用可解析 |

### 5.4 測試沒有覆蓋的邊界

> Issue #85 後，「名稱只有空白」與「名稱為 `NULL`」兩項已由
> `InventoryServiceTest` 覆蓋（見 §5.1）。

- `SQLException` 被吞掉後回 `false` 的路徑。
- 資料庫連線失敗時 `DataAccessException` 往外的路徑。
- `items` 的數量、品項內容、售完狀態——因為這些功能尚未實作。

## 6. 已知限制與風險

1. **不是真正的庫存檢查。** 沒有 `inventory`／`stock` 資料表，沒有數量欄位，
   沒有保留、扣減或回補。`foodItems` 的數量與品項售完都不影響結果。
   若要實作，必須同時處理資料模型、併發與 Saga 補償，不能只改這一個方法。
2. **只判斷「名稱非 null 且非空白」，不是「餐廳營業中」。** 無法區分不存在、
   名稱為空、打烊、暫停營業或資料錯誤；worker 一律回報 `"Restaurant is closed"`。
3. **空白／NULL 名稱已不再通過（Issue #85）。** 判斷式是
   `name != null && !name.isBlank()`（`InventoryService.hasUsableName`）；修前為
   `!Objects.equals(name, "")`，只有空白的名稱會通過。
4. **SQL 例外被吞掉。** `InventoryDAO.readRestaurant` 只印 stdout 後返回，
   service 會把「查詢失敗」誤判成「餐廳不存在」，worker 因此回終端錯誤而不是可重試的
   `FAILED`。連線失敗則丟 `DataAccessException`，行為與前者不同。
5. **連線字串寫死。** `InventoryService` 的 static `inventoryDAO` 固定使用
   `jdbc:sqlite:food_delivery.db`，無法注入；`InventoryServiceTest` 因而與其他
   service 測試共用同一個應用資料庫檔。
6. **`items` 的形狀在邊界層仍有風險。** service 不讀 `items`，但 worker 參數綁定
   仍要求它能被轉成 `ArrayList<FoodItem>`；上游 `FoodDeliveryRequest.foodItems`
   卻是 `ArrayList<Object>`。若未來真的使用 `items`，這個型別落差會直接變成缺陷。
7. **種子資料的 `address`／`contact` 寫反。** `BaseDAO.seedRestaurants()` 的 INSERT
   值順序與欄位順序不一致（`SeedDataTest` 中對應的缺陷測試目前是 `@Disabled`）。
   `checkAvailability` 只看 `name`，所以不影響可用性判斷；但任何使用
   `readRestaurant` 回傳的 `address`／`contact` 的流程會拿到互換的資料。
8. **read-only 的定位。** `conductor/taskdefs/check_inventory.json` 的描述是
   `Check restaurant availability (read-only).`，與實作一致；不應把這個步驟描述成
   「保留庫存」或「扣庫存」。
9. **就緒度限制。** `factory/process.yaml:30-31` 目前把 inventory 標為 `readiness: R0`，
   `readiness_evidence` 仍寫著「no tests cover InventoryService / InventoryDAO
   (src/test has none)」。這個 evidence 與現況已不一致：`InventoryServiceTest` 與
   `InventoryDAOTest` 已存在（Issue #50）。readiness 是人類裁定的欄位，本文件
   不變更它；是否重新裁定或更新 evidence，需由人類決定。`factory/process.yaml`
   的 `spec` 仍指向 `docs/architecture.md`，本文件是補充說明，不取代該規格。

## 7. 變更這個模組時必須同步的範圍

目前 `items` 未被使用，但只要改變判斷語意，受影響的不只是 `InventoryService`：

| 變更 | 必須同步更新 |
| --- | --- |
| 改變「餐廳可用」的判斷（例如 trim、檢查營業狀態） | `InventoryService`、`InventoryServiceTest`、本文件 |
| 真正使用 `items` 做數量／品項檢查 | `InventoryDAO`、資料表 DDL（`BaseDAO`）、`CheckInventoryRequest`／`FoodItem`、`ConductorWorkers.checkInventoryTask` 的狀態語意、`conductor/taskdefs/check_inventory.json` 的 `outputKeys`、`conductor/workflows/FoodDeliveryWorkflow.v1.json` 的輸入映射、`WorkflowDefinitionContractTest`、`InventoryServiceTest`、`InventoryDAOTest`、`docs/architecture.md`、`conductor/PROVENANCE.md` |
| 改變失敗狀態（例如把 DB 錯誤改成可重試的 `FAILED`） | worker、`conductor/workflows/FoodDeliveryFailureWorkflow.v1.json` 的補償分支、`ConductorWorkersTest`、本文件 |

Issue #70 的範圍**只新增本文件**，沒有修改上述程式、測試或 `conductor/**`。
Issue #85 落實了上表第一列（trim 邊界）：修改範圍為 `InventoryService`、
`InventoryServiceTest` 與本文件；`InventoryDAO`、worker 狀態語意與
`conductor/**` 未變動。

## 8. 驗證方式

本文件是唯讀文件變更，驗證命令與 CI 相同：

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew test --no-daemon
```

預期結果與變更前相同（文件不影響測試）。若要核對本文件描述的靜態事實：

```bash
grep -n "checkAvailability\|readRestaurant" \
  src/main/java/io/orkes/example/saga/service/InventoryService.java \
  src/main/java/io/orkes/example/saga/dao/InventoryDAO.java
grep -n "check_inventory" conductor/workflows/*.json conductor/taskdefs/*.json
```

## 9. 參考

| 主題 | 位置 |
| --- | --- |
| `checkAvailability` 實作 | `service/InventoryService.java:15-37`（判斷式 seam `hasUsableName` 見 `:35-37`） |
| `readRestaurant` 實作 | `dao/InventoryDAO.java:17-32` |
| 連線例外包裝 | `dao/BaseDAO.java:14-25` |
| worker 狀態映射 | `workers/ConductorWorkers.java:44-60` |
| `check_inventory` TaskDef | `conductor/taskdefs/check_inventory.json` |
| 主 workflow 的 inventory 步驟 | `conductor/workflows/FoodDeliveryWorkflow.v1.json:39-47` |
| 失敗 workflow 的 inventory 補償分支 | `conductor/workflows/FoodDeliveryFailureWorkflow.v1.json:37-47` |
| 模組清單與 readiness | `factory/process.yaml:22-32` |
| 現有測試 | `src/test/java/io/orkes/example/saga/service/InventoryServiceTest.java`、`src/test/java/io/orkes/example/saga/dao/InventoryDAOTest.java` |

---

*本文件為 `agent-write-docs` 產出，只描述版控內可證實的現行行為；不具放行效力，
也不代表已實作真正的庫存管理。*
