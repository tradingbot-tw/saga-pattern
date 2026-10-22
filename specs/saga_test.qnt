// -*- mode: Bluespec; -*-
// 情境測試。執行：quint test specs/saga_test.qnt --match '_test$'
module saga_test {
  import sagaTypes.* from "./saga"
  import saga(COMP_WORKFLOW = PreFixWorkflow) as P from "./saga"
  import saga(COMP_WORKFLOW = CurrentWorkflow) as C from "./saga"

  // 測試 1：正向流程全部成功
  run happy_path_test =
    C::init
      .then(C::startWorkflow)
      .then(C::orderFoodOk)
      .then(C::checkInventoryOk)
      .then(C::makePaymentOk)
      .then(C::shipFoodOk)
      .then(C::notifyOk(NotifyDriverRef))
      .then(C::notifyOk(NotifyCustomerRef))
      .then(C::joinComplete)
      .expect(and {
        C::saga.phase == Completed,
        C::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentConfirmed },
        C::inv_safety,
      })

  // 測試 2：庫存檢查失敗 → cancel_order
  run inventory_failure_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::orderFoodOk)
      .then(C::checkInventoryFail)
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::saga.customerFailureNotified,
        C::inv_safety,
      })

  // 測試 3：notify_driver 失敗 → cancel_delivery → cancel_payment → cancel_order
  run notify_driver_failure_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::orderFoodOk)
      .then(C::checkInventoryOk)
      .then(C::makePaymentOk)
      .then(C::shipFoodOk)
      .then(C::notifyFail(NotifyDriverRef))
      .then(C::compensateOk)   // cancel_delivery
      .then(C::compensateOk)   // cancel_payment
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentCanceled },
        C::saga.customerFailureNotified,
        C::inv_safety,
      })

  // 測試 4：修正前的補償 workflow 在 make_payment 失敗時呼叫 cancel_payment，
  // 查無付款 → FAILED_WITH_TERMINAL_ERROR → 補償中斷，訂單殘留
  run prefix_make_payment_compensation_failure_test =
    P::init
      .then(P::startWorkflow)
      .then(P::orderFoodOk)
      .then(P::checkInventoryOk)
      .then(P::makePaymentFail)
      .then(P::compensateTerminalError)
      .expect(and {
        P::saga.phase == CompensationFailed,
        P::db.order == OrderCreated,
        not(P::inv_no_compensation_failure),
      })

  // 測試 5：現行補償 workflow 在 make_payment 失敗時完成回滾
  run current_make_payment_compensation_success_test =
    C::init
      .then(C::startWorkflow)
      .then(C::orderFoodOk)
      .then(C::checkInventoryOk)
      .then(C::makePaymentFail)
      .then(C::compensateOk)   // cancel_payment（commit 1 暫沿用舊「冪等」語意）
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::saga.customerFailureNotified,
        C::inv_safety,
      })
}
