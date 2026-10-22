# Saga Pattern Formal Specification (Quint)

本目錄包含餐點外送流程（Food Delivery Saga Pattern，以 Orkes Conductor 編排）之形式化驗證規格。

## 規格架構

- **[`specs/saga.qnt`](file:///Users/cfh00805122/lab/playground/saga-pattern/specs/saga.qnt)**：
  - 定義正向流程（`order_food` $\to$ `check_inventory` $\to$ `make_payment` $\to$ `ship_food` $\to$ `notify_fork`）與顯式並行交錯（`notify_driver` 與 `notify_customer`）。
  - 定義失敗補償流程（`FoodDeliveryFailureWorkflow`）：根據各步驟失敗情境，反向回滾運送、扣款與訂單。
  - 定義四項核心安全性質與終態一致性不變量：
    1. `inv_eventual_consistency`：最終一致性（終態要麼全數成功 `WfCompleted`，要麼全數回滾 `WfCompensated`）。
    2. `inv_no_dangling_effects`：終止時無懸空副作用（回滾後不殘留生效中之訂單、付款或出貨）。
    3. `inv_no_compensation_failure`：補償流程不崩潰（補償操作自身不得因終端錯誤中斷）。
    4. `inv_shipment_requires_payment`：實體依賴約束（出貨必先付款成功）。
  - 支援模式切換：`buggy_model = true`（重現程式碼現況缺陷）與 `buggy_model = false`（修復後之補償機制）。

- **[`specs/saga_test.qnt`](file:///Users/cfh00805122/lab/playground/saga-pattern/specs/saga_test.qnt)**：
  - 包含五大情境測試：
    1. `happy_path_test`：正向流程完整成功。
    2. `inventory_failure_compensation_test`：庫存檢查失敗，成功取消訂單。
    3. `notify_driver_failure_compensation_test`：並行分支中司機通知失敗，完整回滾運送、退款並取消訂單。
    4. `buggy_make_payment_compensation_failure_test`：**精確重現程式碼缺陷**（付款失敗時呼叫 `cancel_payment` 導致拋出異常中斷補償，殘留訂單）。
    5. `fixed_make_payment_compensation_success_test`：驗證修復後之安全補償。

---

## 形式驗證發現之關鍵缺陷 (Vulnerability Analysis)

在現行 [`conductor/workflows/FoodDeliveryFailureWorkflow.v1.json`](file:///Users/cfh00805122/lab/playground/saga-pattern/conductor/workflows/FoodDeliveryFailureWorkflow.v1.json) 中：
```json
"make_payment_ref": [
  { "name": "cancel_payment", ... },
  { "name": "cancel_order", ... }
]
```
當 `make_payment` 失敗時，尚未產生成功之付款紀錄。然而在 [`ConductorWorkers.java`](file:///Users/cfh00805122/lab/playground/saga-pattern/src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java) 第 154-157 行中：
```java
int canceled = PaymentService.cancelPayment(orderId);
if (canceled == 0) {
    return failed(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR,
            "No payment found for order " + orderId);
}
```
`cancel_payment` 找不到紀錄時會拋出 `FAILED_WITH_TERMINAL_ERROR`。這導致：
1. 補償流程在第一步 `cancel_payment` 就崩潰中斷。
2. 後續的 `cancel_order` 永遠不會被執行。
3. 違反了 `inv_no_compensation_failure` 與 `inv_no_dangling_effects`，在資料庫中留下一筆未被取消的懸空訂單（Dangling Created Order）。

### 建議修復方式
1. **工作流層級修復**：`make_payment_ref` 失敗時，補償路徑只需執行 `cancel_order`，不應呼叫 `cancel_payment`。
2. **Worker 冪等性修復**：`cancel_payment` 當 `canceled == 0` 時應視為已經取消或無須退款，回傳 `COMPLETED`（Idempotent），不拋出終端失敗。

---

## 驗證執行命令

### 1. 執行場景測試
```bash
quint test specs/saga_test.qnt
```

### 2. 重現現況缺陷（產出反例 Counterexample Trace）
```bash
quint run --invariant=inv_no_compensation_failure --init=init specs/saga.qnt
```

### 3. 驗證修正方案（探索所有可能執行路徑均滿足安全性不變量）
```bash
quint run --invariant=inv_safety --init=init_fixed specs/saga.qnt
```

### 4. 形式化有界模型檢驗（Apalache Bounded Model Checking）
```bash
quint verify --invariant=inv_safety --init=init_fixed specs/saga.qnt
```
