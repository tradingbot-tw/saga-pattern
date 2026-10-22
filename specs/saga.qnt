// -*- mode: Bluespec; -*-
module saga {
  // =========================================================================
  // 型別定義 (Types)
  // =========================================================================

  type OrderStatus =
    | OrderNone
    | OrderCreated
    | OrderCancelled

  type PaymentStatus =
    | PaymentNone
    | PaymentPaid
    | PaymentRefunded

  type ShipmentStatus =
    | ShipmentNone
    | ShipmentShipped
    | ShipmentCancelled

  type WorkflowStatus =
    | WfPending
    | WfRunning
    | WfCompleted
    | WfCompensating
    | WfCompensated
    | WfCompensationFailed

  type ForwardStep =
    | StepOrderFood
    | StepCheckInventory
    | StepMakePayment
    | StepShipFood
    | StepNotifyFork
    | StepForwardDone

  type FailedTaskName =
    | FailNone
    | FailOrderFood
    | FailCheckInventory
    | FailMakePayment
    | FailShipFood
    | FailNotifyDriver
    | FailNotifyCustomer

  type CompensationStep =
    | CompNone
    | CompCancelDelivery
    | CompCancelPayment
    | CompCancelOrder
    | CompNotifyCustomerFailure
    | CompDone

  // =========================================================================
  // 狀態變數 (State Variables)
  // =========================================================================

  // 業務實體狀態 (Domain Entity State)
  var order_state: OrderStatus
  var inventory_checked: bool
  var payment_state: PaymentStatus
  var shipment_state: ShipmentStatus
  var driver_notified: bool
  var customer_notified: bool
  var customer_failure_notified: bool

  // 工作流狀態 (Workflow Orchestration State)
  var workflow_status: WorkflowStatus
  var forward_step: ForwardStep
  var failed_task: FailedTaskName
  var comp_step: CompensationStep

  // 並行分支完成標記 (Fork/Join tracking)
  var notify_driver_done: bool
  var notify_customer_done: bool

  // 模式開關：是否重現實際程式碼中的缺陷
  // 當 buggy_model = true 時，重現 FoodDeliveryFailureWorkflow.v1.json 中
  // 在 make_payment_ref 失敗時呼叫 cancel_payment，而 worker 找不到紀錄回傳 FAILED_WITH_TERMINAL_ERROR 導致補償失敗。
  // 當 buggy_model = false 時，補償具備防禦性檢查或冪等性。
  var buggy_model: bool

  // =========================================================================
  // 初始化 (Init)
  // =========================================================================

  action init: bool = all {
    order_state' = OrderNone,
    inventory_checked' = false,
    payment_state' = PaymentNone,
    shipment_state' = ShipmentNone,
    driver_notified' = false,
    customer_notified' = false,
    customer_failure_notified' = false,
    workflow_status' = WfPending,
    forward_step' = StepOrderFood,
    failed_task' = FailNone,
    comp_step' = CompNone,
    notify_driver_done' = false,
    notify_customer_done' = false,
    buggy_model' = true,
  }

  action init_fixed: bool = all {
    order_state' = OrderNone,
    inventory_checked' = false,
    payment_state' = PaymentNone,
    shipment_state' = ShipmentNone,
    driver_notified' = false,
    customer_notified' = false,
    customer_failure_notified' = false,
    workflow_status' = WfPending,
    forward_step' = StepOrderFood,
    failed_task' = FailNone,
    comp_step' = CompNone,
    notify_driver_done' = false,
    notify_customer_done' = false,
    buggy_model' = false,
  }

  // =========================================================================
  // 正向步驟 (Forward Steps)
  // =========================================================================

  // 啟動工作流
  action start_workflow: bool = all {
    workflow_status == WfPending,
    workflow_status' = WfRunning,
    forward_step' = StepOrderFood,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 1. order_food 成功
  action order_food_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepOrderFood,
    order_state' = OrderCreated,
    forward_step' = StepCheckInventory,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 1. order_food 失敗 (訂單未建立，defaultCase: []，工作流直接失敗，無需補償)
  action order_food_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepOrderFood,
    workflow_status' = WfCompensated, // 終態：無副作用，即達到一致性回滾
    failed_task' = FailOrderFood,
    comp_step' = CompDone,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 2. check_inventory 成功
  action check_inventory_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepCheckInventory,
    inventory_checked' = true,
    forward_step' = StepMakePayment,
    order_state' = order_state,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 2. check_inventory 失敗 (進入 FoodDeliveryFailureWorkflow -> cancel_order)
  action check_inventory_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepCheckInventory,
    workflow_status' = WfCompensating,
    failed_task' = FailCheckInventory,
    comp_step' = CompCancelOrder,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 3. make_payment 成功
  action make_payment_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepMakePayment,
    payment_state' = PaymentPaid,
    forward_step' = StepShipFood,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 3. make_payment 失敗
  // 在 JSON 定義中，make_payment_ref 失敗時，補償路徑是：cancel_payment -> cancel_order
  action make_payment_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepMakePayment,
    workflow_status' = WfCompensating,
    failed_task' = FailMakePayment,
    comp_step' = CompCancelPayment,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 4. ship_food 成功
  action ship_food_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepShipFood,
    shipment_state' = ShipmentShipped,
    forward_step' = StepNotifyFork,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 4. ship_food 失敗 (補償路徑：cancel_payment -> cancel_order)
  action ship_food_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepShipFood,
    workflow_status' = WfCompensating,
    failed_task' = FailShipFood,
    comp_step' = CompCancelPayment,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 5. 並行通知 (Notify Fork): notify_driver 成功
  action notify_driver_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepNotifyFork,
    not(notify_driver_done),
    driver_notified' = true,
    notify_driver_done' = true,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 5. 並行通知 (Notify Fork): notify_driver 失敗 (觸發 failure workflow: notify_driver_ref 分支)
  action notify_driver_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepNotifyFork,
    not(notify_driver_done),
    workflow_status' = WfCompensating,
    failed_task' = FailNotifyDriver,
    comp_step' = CompCancelDelivery,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 5. 並行通知 (Notify Fork): notify_customer 成功
  action notify_customer_success: bool = all {
    workflow_status == WfRunning,
    forward_step == StepNotifyFork,
    not(notify_customer_done),
    customer_notified' = true,
    notify_customer_done' = true,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    buggy_model' = buggy_model,
  }

  // 5. 並行通知 (Notify Fork): notify_customer 失敗 (觸發 failure workflow: notify_customer_ref 分支)
  action notify_customer_fail: bool = all {
    workflow_status == WfRunning,
    forward_step == StepNotifyFork,
    not(notify_customer_done),
    workflow_status' = WfCompensating,
    failed_task' = FailNotifyCustomer,
    comp_step' = CompCancelDelivery,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 6. Join 成功完成全部正向流程
  action join_and_complete: bool = all {
    workflow_status == WfRunning,
    forward_step == StepNotifyFork,
    notify_driver_done,
    notify_customer_done,
    workflow_status' = WfCompleted,
    forward_step' = StepForwardDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // =========================================================================
  // 補償工作流步驟 (Failure Workflow Compensation Steps)
  // =========================================================================

  // 補償 1: cancel_delivery
  action execute_cancel_delivery: bool = all {
    workflow_status == WfCompensating,
    comp_step == CompCancelDelivery,
    shipment_state' = if (shipment_state == ShipmentShipped) ShipmentCancelled else shipment_state,
    comp_step' = CompCancelPayment,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 補償 2: cancel_payment
  // 若為 buggy_model，且 payment 根本沒有建立（payment_state == PaymentNone，例如 make_payment 失敗時）
  // worker 回傳 FAILED_WITH_TERMINAL_ERROR ("No payment found")，導致補償流程崩潰 (WfCompensationFailed)！
  action execute_cancel_payment: bool = all {
    workflow_status == WfCompensating,
    comp_step == CompCancelPayment,
    if (buggy_model and payment_state == PaymentNone) {
      // 缺陷重現：在無付款情況下呼叫 cancel_payment 導致異常終止
      all {
        workflow_status' = WfCompensationFailed,
        comp_step' = CompNone,
        payment_state' = payment_state,
      }
    } else {
      // 正常退款或修復後的冪等無操作 (Idempotent No-Op)
      all {
        payment_state' = if (payment_state == PaymentPaid) PaymentRefunded else payment_state,
        comp_step' = CompCancelOrder,
        workflow_status' = workflow_status,
      }
    },
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    forward_step' = forward_step,
    failed_task' = failed_task,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 補償 3: cancel_order
  action execute_cancel_order: bool = all {
    workflow_status == WfCompensating,
    comp_step == CompCancelOrder,
    order_state' = if (order_state == OrderCreated) OrderCancelled else order_state,
    comp_step' = CompNotifyCustomerFailure,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 補償 4: notify_customer_failure
  action execute_notify_customer_failure: bool = all {
    workflow_status == WfCompensating,
    comp_step == CompNotifyCustomerFailure,
    customer_failure_notified' = true,
    workflow_status' = WfCompensated,
    comp_step' = CompDone,
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    forward_step' = forward_step,
    failed_task' = failed_task,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 終態保持 (Stuttering step for terminal states)
  action step_stutter: bool = all {
    workflow_status.in(Set(WfCompleted, WfCompensated, WfCompensationFailed)),
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 斷言檢查 Action (供 run / test 序列最後維持狀態並斷言)
  action check_assert(p: bool): bool = all {
    assert(p),
    order_state' = order_state,
    inventory_checked' = inventory_checked,
    payment_state' = payment_state,
    shipment_state' = shipment_state,
    driver_notified' = driver_notified,
    customer_notified' = customer_notified,
    customer_failure_notified' = customer_failure_notified,
    workflow_status' = workflow_status,
    forward_step' = forward_step,
    failed_task' = failed_task,
    comp_step' = comp_step,
    notify_driver_done' = notify_driver_done,
    notify_customer_done' = notify_customer_done,
    buggy_model' = buggy_model,
  }

  // 狀態轉移 (Step)
  action step: bool = any {
    start_workflow,
    order_food_success,
    order_food_fail,
    check_inventory_success,
    check_inventory_fail,
    make_payment_success,
    make_payment_fail,
    ship_food_success,
    ship_food_fail,
    notify_driver_success,
    notify_driver_fail,
    notify_customer_success,
    notify_customer_fail,
    join_and_complete,
    execute_cancel_delivery,
    execute_cancel_payment,
    execute_cancel_order,
    execute_notify_customer_failure,
    step_stutter,
  }

  // =========================================================================
  // 規格不變量與性質 (Invariants & Properties)
  // =========================================================================

  // 性質 1: 最終一致性 (Eventual Consistency)
  // 當工作流終止時，系統必須處於一致狀態：
  // 若 WfCompleted，則必須全數成功（訂單存在、已扣款、已出貨）。
  // 若 WfCompensated，則所有已發生的正向操作都必須已被撤銷回滾（訂單取消、付款已退款、出貨已取消）。
  // 決不允許停留在中間殘留狀態。
  val inv_eventual_consistency: bool =
    if (workflow_status == WfCompleted) {
      order_state == OrderCreated and
      payment_state == PaymentPaid and
      shipment_state == ShipmentShipped
    } else if (workflow_status == WfCompensated) {
      (order_state == OrderNone or order_state == OrderCancelled) and
      (payment_state == PaymentNone or payment_state == PaymentRefunded) and
      (shipment_state == ShipmentNone or shipment_state == ShipmentCancelled)
    } else {
      true
    }

  // 性質 2: 無懸空副作用 (No Dangling Effects on Abort)
  // 如果流程未能成功完成 (WfCompensated)，不得有任何生效中的實體狀態
  val inv_no_dangling_effects: bool =
    (workflow_status == WfCompensated) implies (
      order_state != OrderCreated and
      payment_state != PaymentPaid and
      shipment_state != ShipmentShipped
    )

  // 性質 3: 補償流程無異常終止 (No Compensation Failure)
  // 補償工作流自身不可陷入崩潰或中斷
  // 在 buggy_model 下此性質會被違反（即當 make_payment 失敗時觸發 cancel_payment 拋出 terminal error）。
  val inv_no_compensation_failure: bool =
    workflow_status != WfCompensationFailed

  // 性質 4: 運送依賴付款 (No Shipment Without Successful Payment)
  // 出貨不可在未付款成功時發生
  val inv_shipment_requires_payment: bool =
    (shipment_state == ShipmentShipped) implies (payment_state == PaymentPaid)

  // 組合總安全性不變量
  val inv_safety: bool =
    inv_eventual_consistency and
    inv_no_dangling_effects and
    inv_no_compensation_failure and
    inv_shipment_requires_payment
}
