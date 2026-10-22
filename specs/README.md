# Saga Pattern 形式規格（Quint）

本目錄是餐點外送 Saga（以 Orkes Conductor 編排）的 Quint 規格，描述**現行程式碼**的行為，包括 Conductor 執行時期的語意（失敗集合、逾時、重試）。

## 檔案與模組

| 檔案 / 模組 | 內容 |
|---|---|
| [`saga.qnt`](saga.qnt) `sagaTypes` | 型別：資料庫實體狀態、task ref、補償 task、Saga 階段 |
| [`saga.qnt`](saga.qnt) `saga` | 以 `const COMP_WORKFLOW` 參數化的狀態機、不變量、witness |
| [`saga.qnt`](saga.qnt) `saga_current` | 可執行實例：**現行**補償 workflow |
| [`saga.qnt`](saga.qnt) `saga_prefix` | 可執行實例：`ab60ba5` **修正前**的補償 workflow（回歸用） |
| [`saga_test.qnt`](saga_test.qnt) | 情境測試；`defect_*` 測試重現下方的已知缺陷 |

## 規格與程式碼的對應

| 規格 | 程式碼 |
|---|---|
| `taskOk` / `taskFail` / `paymentDeclined` / `paymentInsertFail` / `shipFoodFail` | [`ConductorWorkers.java`](../src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java) 的正向 worker；[`PaymentService.createPayment`](../src/main/java/io/orkes/example/saga/service/PaymentService.java)（先 insert `PENDING`，再改為 `FAILED`/`SUCCESSFUL`）；[`ShipmentService.createShipment`](../src/main/java/io/orkes/example/saga/service/ShipmentService.java)（找不到司機時自行取消；`confirmShipment` 失敗時列停在 `SCHEDULED`） |
| `compensationPlan` | [`FoodDeliveryFailureWorkflow.v1.json`](../conductor/workflows/FoodDeliveryFailureWorkflow.v1.json) 的 `SWITCH.decisionCases`；最後一律執行 `notify_customer_failure_ref` |
| `selectCompensation` / `selectDefaultCompensation` | 同檔 `extract_failure_context` 的 JQ：`failedReferenceTaskNames[0]` |
| `hasTargetRow` / `compensateTerminalError` | `cancel_*` worker：`UPDATE ... WHERE orderId=?` 影響 0 列時回 `FAILED_WITH_TERMINAL_ERROR` |
| `compensateEnvFault` | `cancel_*` 遇到 `DataAccessException` 回 `FAILED`，`retryCount: 3` 用完仍失敗，或 task 逾時 |
| `transientFail` | [`check_inventory.json`](../conductor/taskdefs/check_inventory.json) 的 `retryCount: 3`：失敗的嘗試留在 `tasks[]` |
| `taskTimeout` | 各 taskdef 的 `timeoutPolicy: TIME_OUT_WF`、`responseTimeoutSeconds: 60` |

Conductor 執行時期的語意依據 conductor-oss 原始碼（commit `4ed6f796`）：

- `failedReferenceTaskNames` 是 `HashSet`，只收 `FAILED` / `FAILED_WITH_TERMINAL_ERROR` 的 task（含後來重試成功的失敗嘗試），**不收 `TIMED_OUT`**。見 `WorkflowExecutorOps.terminateWorkflow`、`WorkflowModel.failedReferenceTaskNames`。
- 集合為空時 JSON 中沒有這個 key，`[0]` 為 null，走 `defaultCase`。
- 逾時（`TIME_OUT_WF` 或回應逾時且重試用完）會讓 workflow 變成 `TIMED_OUT`，仍會觸發 `failureWorkflow`。

## 不變量

| 不變量 | 意義 |
|---|---|
| `inv_completed_consistent` | 主 workflow 成功時，訂單、付款、出貨都已生效 |
| `inv_no_dangling_effects` | 補償完成或補償失敗時，不得殘留生效中的訂單、已付款項、`SCHEDULED`/`CONFIRMED` 出貨單。唯一例外：補償 task 本身遇到基礎設施故障（需人工介入） |
| `inv_compensation_failure_only_from_env` | 補償 workflow 只會因基礎設施故障而失敗，不會因設計錯誤（例如取消不存在的資料）而失敗 |
| `inv_shipment_requires_payment` | 已確認的出貨單必有成功的付款 |
| `inv_safety` | 以上四項的合取 |

## 執行命令

```bash
# 型別檢查
quint typecheck specs/saga.qnt
quint typecheck specs/saga_test.qnt

# 情境測試（必須加 --match；測試名稱以 _test 結尾，預設篩選不會執行它們）
quint test specs/saga_test.qnt --match '_test$'

# 抽樣模擬：檢查不變量（--main 選擇實例）
quint run specs/saga.qnt --main=saga_current --invariant=inv_safety --max-steps=20 --max-samples=30000
quint run specs/saga.qnt --main=saga_prefix  --invariant=inv_compensation_failure_only_from_env --max-steps=20

# Witness：每個 action 都應在部分 trace 中發生
quint run specs/saga.qnt --main=saga_current --max-steps=20 --max-samples=5000 \
  --witnesses w_start w_order_food_ok w_payment_insert_fail w_ship_fail_scheduled \
              w_notify_fail_with_join w_timeout_applied w_join w_select_default w_comp_env_fault
```

目前結果（`quint` 0.33.0，`--max-steps=20 --max-samples=30000 --seed=0x1`）：

| 實例 | `completed_consistent` | `no_dangling_effects` | `comp_failure_only_from_env` | `shipment_requires_payment` |
|---|---|---|---|---|
| `saga_current` | 未發現反例 | **違反**（缺陷 A–D） | 未發現反例 | 未發現反例 |
| `saga_prefix` | 未發現反例 | **違反** | **違反**（舊缺陷） | 未發現反例 |

「未發現反例」是抽樣結果，不是證明。`saga_current` 的 witness `w_comp_terminal_error` 為 0%：現行 workflow 的每個補償分支只取消「該失敗點之前一定已建立」的資料。

## 已知缺陷

以下都是規格找到的反例，各有兩個重現測試：

- **Quint**（[`saga_test.qnt`](saga_test.qnt) 的 `defect_*`）：斷言不變量**被違反**；缺陷修好後要改寫成斷言不變量成立。
- **Java**（[`SagaCompensationDefectTest`](../src/test/java/io/orkes/example/saga/workers/SagaCompensationDefectTest.java) 的 `defect*`）：斷言**正確的 Saga 一致性**，所以在缺陷修好之前會紅燈。正向步驟呼叫真實 worker 寫入 SQLite；資料庫錯誤用 SQLite trigger 注入真實程式碼路徑；Conductor 的重試、逾時、JOIN 競態用 Conductor 自己的 `Workflow`／`Task` 物件表達，`failedReferenceTaskNames` 照 conductor-oss 的規則填入 `HashSet`；補償 workflow 由 [`FailureWorkflowHarness`](../src/test/java/io/orkes/example/saga/workers/FailureWorkflowHarness.java) 照版控中的 JSON 執行（JQ 用 jackson-jq）。兩個 `control_*` 對照組應通過，用來確認紅燈來自缺陷而不是 harness。

| 缺陷 | 情境 | 殘留 | Quint 測試 | Java 測試 |
|---|---|---|---|---|
| 舊缺陷（已由 `ab60ba5` 修正） | 修正前 workflow：`insertPayment` 失敗（沒有付款列）→ `cancel_payment` 查無資料 → 終端錯誤 → 補償中斷 | 訂單 | `defect_prefix_payment_insert_failure_test` | （已修正） |
| A：重試留下的失敗紀錄讓 SWITCH 選錯分支 | `check_inventory` 失敗一次後重試成功，之後 `ship_food` 失敗；集合 `{check_inventory_ref, ship_food_ref}` 的 `[0]` 是 `check_inventory_ref` | 付款 | `defect_a_wrong_branch_after_retry_test` | `defectA_retriedCheckInventoryMakesSwitchPickWrongBranch` |
| B：逾時後完全不補償 | `make_payment` 已扣款但回報逾時；`TIMED_OUT` 不進集合 → `defaultCase` | 訂單、付款 | `defect_b_timeout_skips_compensation_test` | `defectB_timedOutTaskIsNotInFailedSetSoNothingIsCompensated` |
| C：JOIN 競態（依原始碼推論，未在 Orkes 實測） | 並行分支失敗時 JOIN 也標成 `FAILED`；`[0]` 是 `notify_join_ref` → `defaultCase` | 訂單、付款、出貨 | `defect_c_join_race_skips_compensation_test` | `defectC_failedJoinHidesTheFailedBranch` |
| D：`SCHEDULED` 出貨單殘留 | `confirmShipment` 拋出資料庫例外，列停在 `SCHEDULED`；`ship_food_ref` 分支沒有 `cancel_delivery` | 出貨單 | `defect_d_scheduled_shipment_left_test` | `defectD_scheduledShipmentIsNotCanceled` |

Java 重現（`JAVA_HOME` 需為 JDK 17 或 21；JDK 25 會在 Lombok 編譯階段失敗）：

```bash
./gradlew test --tests 'io.orkes.example.saga.workers.SagaCompensationDefectTest'
```

舊缺陷只在 `insertPayment` 失敗時出現。付款被拒是較常見的路徑，此時已有一筆 `FAILED` 付款列，`cancel_payment` 會成功（`prefix_payment_declined_compensates_test`）。

修正方向（尚未實作）：不要用 `failedReferenceTaskNames[0]` 決定補償內容，改依 `failedWorkflow.tasks` 中已 `COMPLETED` 的步驟決定（Orkes saga 範例的做法），或以 `failureTaskId` 反查失敗的 task。缺陷 D 若在 `ship_food_ref` 分支補上 `cancel_delivery`，要注意查無出貨單時 `cancel_delivery` 會回終端錯誤。

## 未涵蓋範圍

- **單一訂單**：不建模多筆訂單並行，也不建模同一 `orderId` 的多筆付款。
- **重試次數**：補償 task 的重試抽象成「重試用完仍失敗」一個事件，不追蹤次數。
- **被取消分支的副作用**：並行分支失敗後，另一分支若已送出通知，不會撤銷；通知不寫資料庫，因此不建模。
- **`PENDING` 付款列**：`createPayment` 的 `updatePaymentStatus` 拋例外時，付款列會停在 `PENDING`；未建模。
- **逾時的副作用**：只建模「完全寫入」與「完全沒寫入」兩種，例如 `ship_food` 逾時時不建模停在 `SCHEDULED` 的情況。
- **雜湊順序**：SWITCH 的 key 以「集合中任一元素」過度近似，不依賴推論出來的 Orkes `HashSet` 順序。
- **活性**：沒有 `temporal` 性質；終態是設計上的終點，`quint run` 的 trace 會在終態提早結束。
- **找到缺陷 A–D 以外的新缺陷**：`inv_no_dangling_effects` 目前已被 A–D 違反，抽樣不會再報告其他反例。修正 A–D 之後，這項不變量才能重新當作回歸檢查。

## 何時更新

規格是正向流程與補償 workflow 的依據。修改 `conductor/**` 或 worker／service 的失敗行為之前，先更新規格並重新執行上面的命令，再改程式碼。
