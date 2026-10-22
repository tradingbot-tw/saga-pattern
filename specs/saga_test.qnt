// -*- mode: Bluespec; -*-
// 情境測試。執行：quint test specs/saga_test.qnt --match '_test$'
//
// P:: = ab60ba5 之前的補償 workflow；S:: = SWITCH 版（#136–#139 修正之前）；C:: = 現行補償 workflow。
// 名稱含 defect_ 的測試保存「舊版本」的反例：它們斷言不變量「被違反」，
// 用來證明規格抓得到這類錯誤。current_ 開頭的同名情境斷言現行 workflow 已修正。
module saga_test {
  import sagaTypes.* from "./saga"
  import saga(COMP_WORKFLOW = PreFixWorkflow) as P from "./saga"
  import saga(COMP_WORKFLOW = SwitchWorkflow) as S from "./saga"
  import saga(COMP_WORKFLOW = CurrentWorkflow) as C from "./saga"

  // -------------------------------------------------------------------------
  // 現行 workflow：正常與一般失敗路徑
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

  // order_food 失敗：沒有訂單，三個 cancel 都查無資料但不報錯，最後通知客戶
  run current_order_food_failure_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskFail(OrderFoodRef))
      .then(C::startFullCompensation)
      .then(C::compensateOk)   // cancel_delivery（查無）
      .then(C::compensateOk)   // cancel_payment（查無）
      .then(C::compensateOk)   // cancel_order（查無）
      .then(C::compensateOk)   // notify_customer_failure
      .expect(and {
        C::saga.phase == Compensated,
        C::db == C::initDb,
        C::saga.customerFailureNotified,
        C::inv_safety,
      })

  run current_inventory_failure_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskFail(CheckInventoryRef))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::inv_safety,
      })

  run current_payment_insert_failure_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::paymentInsertFail)
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)   // cancel_payment：沒有付款列，allowMissing → COMPLETED
      .then(C::compensateOk)
      .then(C::compensateOk)
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
      .then(C::startFullCompensation)
      .then(C::compensateEnvFault)
      .expect(and {
        C::saga.phase == CompensationFailed,
        C::db.order == OrderCreated,
        C::inv_safety,
      })

  // 現行 workflow 不使用 SWITCH
  run current_does_not_use_switch_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskFail(OrderFoodRef))
      .then(C::selectCompensation(OrderFoodRef).fail())

  // -------------------------------------------------------------------------
  // 缺陷 A–D：現行 workflow 已修正
  // -------------------------------------------------------------------------

  // A：重試留下的失敗紀錄不再影響補償內容
  run current_a_retry_does_not_matter_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::transientFail(CheckInventoryRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::shipFoodFail(ShipmentNone))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentNone },
        C::inv_safety,
      })

  // B：逾時且副作用已寫入，仍會被取消（含 order_food 逾時：orderId = workflowId）
  run current_b_timeout_is_compensated_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskTimeout(MakePaymentRef, true))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentNone },
        C::inv_safety,
      })

  run current_b_order_food_timeout_is_compensated_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskTimeout(OrderFoodRef, true))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentNone, shipment: ShipmentNone },
        C::inv_safety,
      })

  // C：JOIN 也被標成 FAILED 不再影響補償內容
  run current_c_join_race_does_not_matter_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::taskOk(ShipFoodRef))
      .then(C::notifyFailWithJoin(NotifyDriverRef))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentCanceled },
        C::inv_safety,
      })

  // D：SCHEDULED 出貨單會被 cancel_delivery 取消
  run current_d_scheduled_shipment_is_canceled_test =
    C::init
      .then(C::startWorkflow)
      .then(C::taskOk(OrderFoodRef))
      .then(C::taskOk(CheckInventoryRef))
      .then(C::taskOk(MakePaymentRef))
      .then(C::shipFoodFail(ShipmentScheduled))
      .then(C::startFullCompensation)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .then(C::compensateOk)
      .expect(and {
        C::saga.phase == Compensated,
        C::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentCanceled },
        C::inv_safety,
      })

  // -------------------------------------------------------------------------
  // SWITCH 版（#136–#139 修正之前）：一般路徑仍正確
  // -------------------------------------------------------------------------

  run switch_notify_driver_failure_compensation_test =
    S::init
      .then(S::startWorkflow)
      .then(S::taskOk(OrderFoodRef))
      .then(S::taskOk(CheckInventoryRef))
      .then(S::taskOk(MakePaymentRef))
      .then(S::taskOk(ShipFoodRef))
      .then(S::taskFail(NotifyDriverRef))
      .then(S::selectCompensation(NotifyDriverRef))
      .then(S::compensateOk)   // cancel_delivery
      .then(S::compensateOk)   // cancel_payment
      .then(S::compensateOk)   // cancel_order
      .then(S::compensateOk)   // notify_customer_failure
      .expect(and {
        S::saga.phase == Compensated,
        S::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentCanceled },
        S::inv_safety,
      })

  // -------------------------------------------------------------------------
  // ab60ba5 之前
  // -------------------------------------------------------------------------

  // 付款被拒時已有一筆 FAILED 付款列 → cancel_payment 影響 1 列 → 成功。
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
  // SWITCH 版的缺陷 A–D（保留作為反例）
  // -------------------------------------------------------------------------

  // 缺陷 A（#136）：check_inventory 失敗一次後重試成功，之後 ship_food 失敗。
  // failedReferenceTaskNames = {check_inventory_ref, ship_food_ref}，[0] 依 Java HashSet 雜湊順序是 check_inventory_ref
  // → 只 cancel_order，付款殘留。
  run defect_a_wrong_branch_after_retry_test =
    S::init
      .then(S::startWorkflow)
      .then(S::taskOk(OrderFoodRef))
      .then(S::transientFail(CheckInventoryRef))
      .then(S::taskOk(CheckInventoryRef))
      .then(S::taskOk(MakePaymentRef))
      .then(S::shipFoodFail(ShipmentNone))
      .then(S::selectCompensation(CheckInventoryRef))
      .then(S::compensateOk)   // cancel_order
      .then(S::compensateOk)   // notify_customer_failure
      .expect(and {
        S::saga.phase == Compensated,
        S::db.payment == PaymentPaid,
        not(S::inv_no_dangling_effects),
      })

  // 缺陷 B（#137）：make_payment 已扣款但回報逾時 → TIMED_OUT 不進集合 → defaultCase → 什麼都不補償
  run defect_b_timeout_skips_compensation_test =
    S::init
      .then(S::startWorkflow)
      .then(S::taskOk(OrderFoodRef))
      .then(S::taskOk(CheckInventoryRef))
      .then(S::taskTimeout(MakePaymentRef, true))
      .then(S::selectDefaultCompensation)
      .then(S::compensateOk)   // notify_customer_failure
      .expect(and {
        S::saga.phase == Compensated,
        S::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentNone },
        not(S::inv_no_dangling_effects),
      })

  // 缺陷 C（#138，推論）：notify_driver 失敗時 JOIN 也被標成 FAILED，[0] = notify_join_ref → defaultCase
  run defect_c_join_race_skips_compensation_test =
    S::init
      .then(S::startWorkflow)
      .then(S::taskOk(OrderFoodRef))
      .then(S::taskOk(CheckInventoryRef))
      .then(S::taskOk(MakePaymentRef))
      .then(S::taskOk(ShipFoodRef))
      .then(S::notifyFailWithJoin(NotifyDriverRef))
      .then(S::selectCompensation(NotifyJoinRef))
      .then(S::compensateOk)   // notify_customer_failure
      .expect(and {
        S::saga.phase == Compensated,
        S::db == { order: OrderCreated, payment: PaymentPaid, shipment: ShipmentConfirmed },
        not(S::inv_no_dangling_effects),
      })

  // 缺陷 D（#139）：confirmShipment 拋出資料庫例外 → 出貨單停在 SCHEDULED；
  // ship_food_ref 分支沒有 cancel_delivery → 出貨單殘留
  run defect_d_scheduled_shipment_left_test =
    S::init
      .then(S::startWorkflow)
      .then(S::taskOk(OrderFoodRef))
      .then(S::taskOk(CheckInventoryRef))
      .then(S::taskOk(MakePaymentRef))
      .then(S::shipFoodFail(ShipmentScheduled))
      .then(S::selectCompensation(ShipFoodRef))
      .then(S::compensateOk)   // cancel_payment
      .then(S::compensateOk)   // cancel_order
      .then(S::compensateOk)   // notify_customer_failure
      .expect(and {
        S::saga.phase == Compensated,
        S::db == { order: OrderCancelled, payment: PaymentCanceled, shipment: ShipmentScheduled },
        not(S::inv_no_dangling_effects),
      })
}
