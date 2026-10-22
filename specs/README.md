# Saga Pattern 形式規格（Quint）

本目錄是餐點外送 Saga（以 Orkes Conductor 編排）的 Quint 規格，描述**現行程式碼**的行為，包括 Conductor 執行時期的語意（失敗集合、逾時、重試）。

## 檔案與模組

| 檔案 / 模組 | 內容 |
|---|---|
| [`saga.qnt`](saga.qnt) `sagaTypes` | 型別：資料庫實體狀態、task ref、補償 task、Saga 階段 |
| [`saga.qnt`](saga.qnt) `saga` | 以 `const COMP_WORKFLOW` 參數化的狀態機、不變量、witness |
| [`saga.qnt`](saga.qnt) `saga_current` | 可執行實例：**現行**補償 workflow（反向、冪等取消） |
| [`saga.qnt`](saga.qnt) `saga_switch` | 可執行實例：`#136–#139` **修正前**的補償 workflow（`SWITCH` on `failedReferenceTaskNames[0]`），保留 A–D 反例作回歸 |
| [`saga.qnt`](saga.qnt) `saga_prefix` | 可執行實例：`ab60ba5` **修正前**的補償 workflow（回歸用） |
| [`saga_test.qnt`](saga_test.qnt) | 情境測試；`current_*` 驗證現行版本、`defect_*` 保存舊版反例 |

## 規格與程式碼的對應

| 規格 | 程式碼 |
|---|---|
| `taskOk` / `taskFail` / `paymentDeclined` / `paymentInsertFail` / `shipFoodFail` | [`ConductorWorkers.java`](../src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java) 的正向 worker；[`PaymentService.createPayment`](../src/main/java/io/orkes/example/saga/service/PaymentService.java)（先 insert `PENDING`，再改為 `FAILED`/`SUCCESSFUL`）；[`ShipmentService.createShipment`](../src/main/java/io/orkes/example/saga/service/ShipmentService.java)（找不到司機時自行取消；`confirmShipment` 失敗時列停在 `SCHEDULED`） |
| `FULL_COMPENSATION` / `startFullCompensation` | 現行 [`FoodDeliveryFailureWorkflow.v1.json`](../conductor/workflows/FoodDeliveryFailureWorkflow.v1.json)：`cancel_delivery` → `cancel_payment` → `cancel_order` → `notify_customer_failure_ref`，不看失敗點 |
| `compensationPlan` / `selectCompensation` / `selectDefaultCompensation` | `saga_switch`／`saga_prefix` 專用：舊定義的 `SWITCH.decisionCases` 與 `extract_failure_context` 的 `failedReferenceTaskNames[0]` |
| `allowMissing` / `compTerminalError` | `cancel_*` worker：現行 workflow 傳 `allowMissing=true`，`UPDATE ... WHERE orderId=?` 影響 0 列時回 `COMPLETED`；其他情況回 `FAILED_WITH_TERMINAL_ERROR` |
| `compensateEnvFault` | `cancel_*` 遇到 `DataAccessException` 回 `FAILED`，`retryCount: 3` 用完仍失敗，或 task 逾時 |
| `transientFail` | [`check_inventory.json`](../conductor/taskdefs/check_inventory.json) 的 `retryCount: 3`：失敗的嘗試留在 `tasks[]` |
| `taskTimeout` | 各 taskdef 的 `timeoutPolicy: TIME_OUT_WF`、`responseTimeoutSeconds: 60` |
| 現行版的 `orderId` 永遠已知 | [`FoodDeliveryWorkflow.v1.json`](../conductor/workflows/FoodDeliveryWorkflow.v1.json) 把 `orderId: ${workflow.workflowId}` 傳給 `order_food`；`createOrder` 沿用該 id |

Conductor 執行時期的語意依據 conductor-oss 原始碼（commit `4ed6f796`）：

- `failedReferenceTaskNames` 是 `HashSet`，只收 `FAILED` / `FAILED_WITH_TERMINAL_ERROR` 的 task（含後來重試成功的失敗嘗試），**不收 `TIMED_OUT`**。見 `WorkflowExecutorOps.terminateWorkflow`、`WorkflowModel.failedReferenceTaskNames`。
- 集合為空時 JSON 中沒有這個 key，`[0]` 為 null。
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
quint run specs/saga.qnt --main=saga_current --invariant=inv_safety --max-steps=20 --max-samples=100000
quint run specs/saga.qnt --main=saga_switch  --invariant=inv_no_dangling_effects --max-steps=20
quint run specs/saga.qnt --main=saga_prefix  --invariant=inv_compensation_failure_only_from_env --max-steps=20

# Witness：每個 action 都應在部分 trace 中發生
quint run specs/saga.qnt --main=saga_current --max-steps=20 --max-samples=5000 \
  --witnesses w_start w_order_food_ok w_payment_insert_fail w_ship_fail_scheduled \
              w_notify_fail_with_join w_timeout_applied w_join w_full_compensation w_comp_env_fault
```

目前結果（`quint` 0.33.0，`--max-steps=20 --max-samples=30000 --seed=0x1`）：

| 實例 | `completed_consistent` | `no_dangling_effects` | `comp_failure_only_from_env` | `shipment_requires_payment` |
|---|---|---|---|---|
| `saga_current` | 未發現反例 | 未發現反例 | 未發現反例 | 未發現反例 |
| `saga_switch` | 未發現反例 | **違反**（A–D） | 未發現反例 | 未發現反例 |
| `saga_prefix` | 未發現反例 | **違反** | **違反**（舊缺陷） | 未發現反例 |

`saga_current` 另以 `--max-samples=100000` 抽樣 `inv_safety`，未發現反例。「未發現反例」是抽樣結果，不是證明。
`saga_current` 的 witness `w_select*` 與 `w_comp_terminal_error` 為 0%，兩者都是預期結果：現行版本沒有 `SWITCH`，`allowMissing` 也讓「查無資料的終端錯誤」不會發生。

## 缺陷 A–D：已修正，反例保留

| 缺陷 | 情境 | 修正前的殘留 | Quint 反例（`saga_switch`） | Java |
|---|---|---|---|---|
| 舊缺陷 | `insertPayment` 失敗（沒有付款列）→ `cancel_payment` 查無資料 → 終端錯誤 | 訂單 | `defect_prefix_payment_insert_failure_test`（`saga_prefix`） | 已由 `ab60ba5` 修正 |
| A（#136） | `check_inventory` 失敗一次後重試成功，之後 `ship_food` 失敗；集合 `[0]` 是 `check_inventory_ref` → 只取消訂單 | 付款 | `defect_a_wrong_branch_after_retry_test` | `defectA_*` |
| B（#137） | `make_payment` 已扣款但回報逾時；`TIMED_OUT` 不進集合 → `defaultCase` | 訂單、付款 | `defect_b_timeout_skips_compensation_test` | `defectB_*`（2 個：含 `order_food` 逾時） |
| C（#138，推論） | 並行分支失敗時 JOIN 也標成 `FAILED`；`[0]` 是 `notify_join_ref` → `defaultCase` | 訂單、付款、出貨 | `defect_c_join_race_skips_compensation_test` | `defectC_*` |
| D（#139） | `confirmShipment` 資料庫例外，出貨單停在 `SCHEDULED`；舊分支沒有 `cancel_delivery` | 出貨單 | `defect_d_scheduled_shipment_left_test` | `defectD_*` |

現行版本的對照情境是 `saga_test.qnt` 的 `current_*` 測試（全部通過），以及
[`SagaCompensationDefectTest`](../src/test/java/io/orkes/example/saga/workers/SagaCompensationDefectTest.java)：
它照版控中的 JSON 執行補償 workflow（Conductor 的 `Workflow`／`Task` 物件、jackson-jq、真實 `cancel_*`
worker 與 SQLite；資料庫錯誤以 SQLite trigger 注入），並斷言補償後不留殘留。

```bash
# JAVA_HOME 需為 JDK 17 或 21（JDK 25 會在 Lombok 編譯階段失敗）
./gradlew test --tests 'io.orkes.example.saga.workers.SagaCompensationDefectTest'
```

同一組 Java 測試若改回 SWITCH 版定義，5 個情境會失敗（A、B×2、C、D），3 個一般情境通過——這證明測試真的能分辨修正前後。

修正的做法：補償不看失敗點，一律反向執行 `cancel_delivery` → `cancel_payment` → `cancel_order`，
每個都帶 `allowMissing=true`（查無資料為 no-op）；`order_food` 的 `orderId` 改由 `${workflow.workflowId}`
決定，讓補償在 `order_food` 逾時、`output` 尚未計算時仍能定址訂單。

## 未涵蓋範圍

- **單一訂單**：不建模多筆訂單並行，也不建模同一 `orderId` 的多筆付款。
- **重試次數**：補償 task 的重試抽象成「重試用完仍失敗」一個事件，不追蹤次數。
- **晚到的副作用**：worker 在 task 已逾時、補償已執行「之後」才完成，例如 `updatePaymentStatus` 把已 `CANCELED` 的付款改回 `SUCCESSFUL`。規格把逾時視為瞬間發生，因此未建模（另開 issue 追蹤）。
- **被取消分支的副作用**：並行分支失敗後，另一分支若已送出通知，不會撤銷；通知不寫資料庫，因此不建模。
- **`PENDING` 付款列**：`createPayment` 的 `updatePaymentStatus` 拋例外時，付款列會停在 `PENDING`；未建模。
- **雜湊順序**：`saga_switch`／`saga_prefix` 的 SWITCH key 以「集合中任一元素」過度近似，不依賴推論出來的 Orkes `HashSet` 順序。
- **活性**：沒有 `temporal` 性質；終態是設計上的終點，`quint run` 的 trace 會在終態提早結束。

## 何時更新

規格是正向流程與補償 workflow 的依據。修改 `conductor/**` 或 worker／service 的失敗行為之前，先更新規格並重新執行上面的命令，再改程式碼。
**不要把規格改寬來讓實作過關**：若規格真的該變，先提出修改並取得同意。
