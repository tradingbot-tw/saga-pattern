// -*- mode: Bluespec; -*-
// 情境測試。執行：quint test specs/saga_test.qnt --match '_test$'
//
// P:: = 修正前（ab60ba5 之前）的補償 workflow；C:: = 現行補償 workflow。
// 名稱含 defect_ 的測試重現 specs/README.md「已知缺陷」中的反例：
// 它們斷言不變量「被違反」，缺陷修好後應改寫。
module saga_test {
  import sagaTypes.* from "./saga"
  import saga(COMP_WORKFLOW = PreFixWorkflow) as P from "./saga"
  import saga(COMP_WORKFLOW = CurrentWorkflow) as C from "./saga"

  // -------------------------------------------------------------------------
  // 正常路徑
  // -------------------------------------------------------------------------

  run happy_path_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::taskOk(ShipFoodRef))
      .then(C::taskOk(NotifyDriverRef))
      .then(C::taskOk(NotifyCustomerRef))
      .then(C::joinComplete)
      .expect(and {
        C::saga.phase == Completed,
        C::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentConfirmed },
        C::inv_safety,
      })

  // order_food 失敗：defaultCase，但仍通知客戶失敗
  run order_food_failure_notifies_customer_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskFail(OrderFoodRef))
      .then(C::selectCompensation(OrderFoodRef))
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == C::initDb,
        C::saga.customerFailureNotified,
        C::inv_safety,
      })

  run inventory_failure_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskFail(CheckInventoryRef))
      .then(C::selectCompensation(CheckInventoryRef))
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::saga.customerFailureNotified,
        C::inv_safety,
      })

  run notify_driver_failure_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::taskOk(ShipFoodRef))
      .then(C::taskFail(NotifyDriverRef))
      .then(C::selectCompensation(NotifyDriverRef))
      .then(C::compensateOk)   // cancel_delivery
      .then(C::compensateOk)   // cancel_payment
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentCanceled },
        C::inv_safety,
      })

  // 現行 workflow：make_payment 的兩種失敗都只需 cancel_order
  run current_payment_insert_failure_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::paymentInsertFail)
      .then(C::selectCompensation(MakePaymentRef))
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::inv_safety,
      })

  // 補償 task 遇到基礎設施故障：補償失敗、訂單殘留，但屬可接受的環境故障（需人工介入）
  run compensation_env_fault_is_tolerated_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskFail(CheckInventoryRef))
      .then(C::selectCompensation(CheckInventoryRef))
      .then(C::compensateEnvFault)
      .expect(and {
        C::saga.phase == CompensationFailed,
        C::db.order == OrderCreated,
        C::inv_safety,
      })

  // -------------------------------------------------------------------------
  // 修正前 workflow（ab60ba5 之前）
  // -------------------------------------------------------------------------

  // 付款被拒時已有一筆 FAILED 付款列 → cancel_payment 影響 1 列 → 成功。
  // 舊缺陷「不會」在這條最常見的路徑出現。
  run prefix_payment_declined_compensates_test =
    P::init
      .then(P::startWorkflow)
      .then(P::taskOk(OrderFoodRef))
      .then(P::taskOk(CheckInventoryRef))
      .then(P::paymentDeclined)
      .then(P::selectCompensation(MakePaymentRef))
      .then(P::compensateOk)   // cancel_payment：FAILED → CANCELED
      .then(P::compensateOk)   // cancel_order
      .then(P::compensateOk)   // notify_customer_failure
      .expect(and {
        P::saga.phase == Compensated,
        P::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentNone },
        P::inv_safety,
      })

  // 只有 insertPayment 失敗（沒有付款列）時，cancel_payment 才回 FAILED_WITH_TERMINAL_ERROR
  run defect_prefix_payment_insert_failure_test =
    P::init
      .then(P::startWorkflow)
      .then(P::taskOk(OrderFoodRef))
      .then(P::taskOk(CheckInventoryRef))
      .then(P::paymentInsertFail)
      .then(P::selectCompensation(MakePaymentRef))
      .then(P::compensateTerminalError)   // cancel_payment：No payment found
      .expect(and {
        P::saga.phase == CompensationFailed,
        P::db.order == OrderCreated,
        not(P::inv_compensation_failure_only_from_env),
        not(P::inv_no_dangling_effects),
      })

  // -------------------------------------------------------------------------
  // 現行 workflow 的已知缺陷（反例）
  // -------------------------------------------------------------------------

  // 缺陷 A：check_inventory 失敗一次後重試成功，之後 ship_food 失敗。
  // failedReferenceTaskNames = {check_inventory_ref, ship_food_ref}，[0] 可能是 check_inventory_ref
  // （依 Java HashSet 的雜湊順序確實如此）→ 只 cancel_order，付款殘留。
  run defect_a_wrong_branch_after_retry_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::transientFail(CheckInventoryRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::shipFoodFail(ShipmentNone))
      .then(C::selectCompensation(CheckInventoryRef))
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db.payment == PaymentPaid,
        not(C::inv_no_dangling_effects),
      })

  // 缺陷 B：make_payment 已扣款但回報逾時 → TIMED_OUT 不進集合 → defaultCase → 什麼都不補償
  run defect_b_timeout_skips_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskTimeout(MakePaymentRef, true))
      .then(C::selectDefaultCompensation)
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentNone },
        not(C::inv_no_dangling_effects),
      })

  // 缺陷 C（推論）：notify_driver 失敗時 JOIN 也被標成 FAILED，[0] = notify_join_ref → defaultCase
  run defect_c_join_race_skips_compensation_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::taskOk(ShipFoodRef))
      .then(C::notifyFailWithJoin(NotifyDriverRef))
      .then(C::selectCompensation(NotifyJoinRef))
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentConfirmed },
        not(C::inv_no_dangling_effects),
      })

  // 缺陷 D：confirmShipment 拋出資料庫例外 → 出貨單停在 SCHEDULED；
  // ship_food_ref 分支沒有 cancel_delivery → 出貨單殘留
  run defect_d_scheduled_shipment_left_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::shipFoodFail(ShipmentScheduled))
      .then(C::selectCompensation(ShipFoodRef))
      .then(C::compensateOk)   // cancel_payment
      .then(C::compensateOk)   // cancel_order
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentScheduled },
        not(C::inv_no_dangling_effects),
      })
}
