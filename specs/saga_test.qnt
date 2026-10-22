// -*- mode: Bluespec; -*-
module saga_test {
  import saga.* from "./saga"

  // =========================================================================
  // 情境測試 (Scenario Tests)
  // =========================================================================

  // 測試 1: 完美成功路徑 (Happy Path)
  run happy_path_test = {
    init
      .then(start_workflow)
      .then(order_food_success)
      .then(check_inventory_success)
      .then(make_payment_success)
      .then(ship_food_success)
      .then(notify_driver_success)
      .then(notify_customer_success)
      .then(join_and_complete)
      .then(check_assert(
        workflow_status == WfCompleted and
        order_state == OrderCreated and
        payment_state == PaymentPaid and
        shipment_state == ShipmentShipped and
        inv_safety
      ))
  }

  // 測試 2: 庫存不足中斷並成功補償 (Inventory Failure Path)
  run inventory_failure_compensation_test = {
    init
      .then(start_workflow)
      .then(order_food_success)
      .then(check_inventory_fail)
      .then(execute_cancel_order)
      .then(execute_notify_customer_failure)
      .then(check_assert(
        workflow_status == WfCompensated and
        order_state == OrderCancelled and
        payment_state == PaymentNone and
        shipment_state == ShipmentNone and
        customer_failure_notified and
        inv_safety
      ))
  }

  // 測試 3: 司機通知失敗中斷並成功反向回滾運送、扣款與訂單
  run notify_driver_failure_compensation_test = {
    init
      .then(start_workflow)
      .then(order_food_success)
      .then(check_inventory_success)
      .then(make_payment_success)
      .then(ship_food_success)
      .then(notify_driver_fail)
      .then(execute_cancel_delivery)
      .then(execute_cancel_payment)
      .then(execute_cancel_order)
      .then(execute_notify_customer_failure)
      .then(check_assert(
        workflow_status == WfCompensated and
        shipment_state == ShipmentCancelled and
        payment_state == PaymentRefunded and
        order_state == OrderCancelled and
        customer_failure_notified and
        inv_safety
      ))
  }

  // 測試 4: 重現現行代碼缺陷 (Bug Reproduction in Original Implementation)
  // 當 make_payment 失敗時，現有 FoodDeliveryFailureWorkflow 定義了呼叫 cancel_payment。
  // 但由於 payment 並未產生成功，ConductorWorkers.cancelPaymentTask 傳回 0 並拋出 FAILED_WITH_TERMINAL_ERROR。
  // 這會導致補償流程本身失敗 (WfCompensationFailed)，訂單殘留在 OrderCreated 未被取消！
  run buggy_make_payment_compensation_failure_test = {
    init // buggy_model = true
      .then(start_workflow)
      .then(order_food_success)
      .then(check_inventory_success)
      .then(make_payment_fail)
      .then(execute_cancel_payment)
      .then(check_assert(
        workflow_status == WfCompensationFailed and
        order_state == OrderCreated and // 訂單未能被 cancel_order，形成殘留副作用！
        not(inv_no_compensation_failure) // 證明違反了「補償不崩潰」性質
      ))
  }

  // 測試 5: 驗證修正後的補償邏輯 (Fixed Implementation Test)
  // 在修復後 (init_fixed)，cancel_payment 具備防禦性處理或補償定義不調用不存在之資源，
  // 補償能順利走到 cancel_order 並完成一致性回滾！
  run fixed_make_payment_compensation_success_test = {
    init_fixed // buggy_model = false
      .then(start_workflow)
      .then(order_food_success)
      .then(check_inventory_success)
      .then(make_payment_fail)
      .then(execute_cancel_payment)
      .then(execute_cancel_order)
      .then(execute_notify_customer_failure)
      .then(check_assert(
        workflow_status == WfCompensated and
        order_state == OrderCancelled and
        payment_state == PaymentNone and
        customer_failure_notified and
        inv_safety
      ))
  }
}
