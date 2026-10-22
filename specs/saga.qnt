// -*- mode: Bluespec; -*-
//
// 餐點外送 Saga（Orkes Conductor 編排）的 Quint 規格。
// 對應來源：conductor/workflows/*.json、conductor/taskdefs/*.json、
//           src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java 與 service/*。
// 執行方式與涵蓋範圍見 specs/README.md。

// ===========================================================================
// 型別 (Types)
// ===========================================================================
module sagaTypes {
  // 補償 workflow（FoodDeliveryFailureWorkflow）的版本
  type CompWorkflow =
    | PreFixWorkflow    // ab60ba5 之前：make_payment_ref 分支為 cancel_payment → cancel_order
    | CurrentWorkflow   // 現行 conductor/workflows/FoodDeliveryFailureWorkflow.v1.json

  // 資料庫實體狀態（對應 orders / payments / shipments 的 status 欄位）
  type OrderStatus    = OrderNone | OrderCreated | OrderCancelled
  type PaymentStatus  = PaymentNone | PaymentPaid | PaymentCanceled
  type ShipmentStatus = ShipmentNone | ShipmentConfirmed | ShipmentCanceled

  type Db = {
    order:    OrderStatus,
    payment:  PaymentStatus,
    shipment: ShipmentStatus,
  }

  // 主 workflow 的 task reference name
  type TaskRef =
    | OrderFoodRef | CheckInventoryRef | MakePaymentRef | ShipFoodRef
    | NotifyDriverRef | NotifyCustomerRef

  // 正向流程的游標
  type Stage = AtOrderFood | AtCheckInventory | AtMakePayment | AtShipFood | AtNotifyFork | ForwardDone

  // 補償 workflow 的 SIMPLE task（NotifyFailure = notify_customer_failure_ref）
  type CompTask = CancelDelivery | CancelPayment | CancelOrder | NotifyFailure

  // Saga 整體階段：主 workflow + 補償 workflow 合併觀察
  type Phase = Pending | Running | Completed | Compensating | Compensated | CompensationFailed

  type Saga = {
    phase:                   Phase,
    stage:                   Stage,
    forkDone:                Set[TaskRef],     // 並行分支已完成者
    failedRefs:              Set[TaskRef],     // 對應 failedWorkflow.failedReferenceTaskNames
    compQueue:               List[CompTask],   // 補償 workflow 尚未執行的步驟（有序）
    customerFailureNotified: bool,
  }

  // 最近一次發生的 action（供 witness 與 trace 閱讀）
  type Act =
    | ActInit
    | ActStart
    | ActOk(TaskRef)
    | ActFail(TaskRef)
    | ActJoin
    | ActCompOk(CompTask)
    | ActCompTerminalError(CompTask)
}

// ===========================================================================
// 規格本體（以 COMP_WORKFLOW 參數化）
// ===========================================================================
module saga {
  import sagaTypes.*

  const COMP_WORKFLOW: CompWorkflow

  var db: Db
  var saga: Saga
  var lastAction: Act

  // -------------------------------------------------------------------------
  // 純函數：正向流程
  // -------------------------------------------------------------------------

  pure val NOTIFY_TASKS: Set[TaskRef] = Set(NotifyDriverRef, NotifyCustomerRef)

  pure val initDb: Db = { order: OrderNone, payment: PaymentNone, shipment: ShipmentNone }

  pure val initSaga: Saga = {
    phase: Pending,
    stage: AtOrderFood,
    forkDone: Set(),
    failedRefs: Set(),
    compQueue: List(),
    customerFailureNotified: false,
  }

  pure def runningAt(s: Saga, st: Stage): bool =
    s.phase == Running and s.stage == st

  pure def advance(s: Saga, st: Stage): Saga = { ...s, stage: st }

  pure def canNotify(s: Saga, t: TaskRef): bool =
    runningAt(s, AtNotifyFork) and NOTIFY_TASKS.contains(t) and not(s.forkDone.contains(t))

  pure def canJoin(s: Saga): bool =
    runningAt(s, AtNotifyFork) and s.forkDone == NOTIFY_TASKS

  // -------------------------------------------------------------------------
  // 純函數：補償 workflow
  // -------------------------------------------------------------------------

  // FoodDeliveryFailureWorkflow 的 SWITCH(decisionCases) 表
  pure def compensationPlan(wf: CompWorkflow, failed: TaskRef): List[CompTask] =
    match failed {
      | OrderFoodRef      => List()                                   // defaultCase: []
      | CheckInventoryRef => List(CancelOrder)
      | MakePaymentRef    => List(CancelPayment, CancelOrder)
      | ShipFoodRef       => List(CancelPayment, CancelOrder)
      | NotifyDriverRef   => List(CancelDelivery, CancelPayment, CancelOrder)
      | NotifyCustomerRef => List(CancelDelivery, CancelPayment, CancelOrder)
    }

  // 主 workflow 失敗 → 觸發補償 workflow：SWITCH 之後一律接 notify_customer_failure_ref
  pure def startCompensation(wf: CompWorkflow, s: Saga, failed: TaskRef): Saga = {
    ...s,
    phase: Compensating,
    stage: ForwardDone,
    failedRefs: Set(failed),
    compQueue: compensationPlan(wf, failed).append(NotifyFailure),
  }

  // 補償 task 回報 FAILED_WITH_TERMINAL_ERROR 的條件
  pure def compTerminalError(wf: CompWorkflow, d: Db, t: CompTask): bool =
    t == CancelPayment and wf == PreFixWorkflow and d.payment == PaymentNone

  // 補償 task 對資料庫的效果（假設成功）
  pure def applyComp(d: Db, t: CompTask): Db =
    match t {
      | CancelDelivery => { ...d, shipment: if (d.shipment == ShipmentConfirmed) ShipmentCanceled else d.shipment }
      | CancelPayment  => { ...d, payment: if (d.payment == PaymentPaid) PaymentCanceled else d.payment }
      | CancelOrder    => { ...d, order: if (d.order == OrderCreated) OrderCancelled else d.order }
      | NotifyFailure  => d
    }

  pure def hasPendingComp(s: Saga): bool =
    s.phase == Compensating and s.compQueue.length() > 0

  // 補償 task 成功 → 出列；佇列清空即補償 workflow COMPLETED
  pure def popComp(s: Saga): Saga = {
    val t = s.compQueue.head()
    val rest = s.compQueue.tail()
    {
      ...s,
      compQueue: rest,
      customerFailureNotified: s.customerFailureNotified or t == NotifyFailure,
      phase: if (rest.length() == 0) Compensated else Compensating,
    }
  }

  // 補償 workflow 自身 FAILED
  pure def failComp(s: Saga): Saga = { ...s, phase: CompensationFailed, compQueue: List() }

  // -------------------------------------------------------------------------
  // 狀態機
  // -------------------------------------------------------------------------

  action init: bool = all {
    db' = initDb,
    saga' = initSaga,
    lastAction' = ActInit,
  }

  action startWorkflow: bool = all {
    saga.phase == Pending,
    db' = db,
    saga' = { ...saga, phase: Running },
    lastAction' = ActStart,
  }

  // 1. order_food
  action orderFoodOk: bool = all {
    runningAt(saga, AtOrderFood),
    db' = { ...db, order: OrderCreated },
    saga' = advance(saga, AtCheckInventory),
    lastAction' = ActOk(OrderFoodRef),
  }

  // insertOrder 失敗 → 回傳 null → FAILED；沒有訂單，無須補償
  action orderFoodFail: bool = all {
    runningAt(saga, AtOrderFood),
    db' = db,
    saga' = { ...saga, phase: Compensated, stage: ForwardDone, failedRefs: Set(OrderFoodRef) },
    lastAction' = ActFail(OrderFoodRef),
  }

  // 2. check_inventory
  action checkInventoryOk: bool = all {
    runningAt(saga, AtCheckInventory),
    db' = db,
    saga' = advance(saga, AtMakePayment),
    lastAction' = ActOk(CheckInventoryRef),
  }

  action checkInventoryFail: bool = all {
    runningAt(saga, AtCheckInventory),
    db' = db,
    saga' = startCompensation(COMP_WORKFLOW, saga, CheckInventoryRef),
    lastAction' = ActFail(CheckInventoryRef),
  }

  // 3. make_payment
  action makePaymentOk: bool = all {
    runningAt(saga, AtMakePayment),
    db' = { ...db, payment: PaymentPaid },
    saga' = advance(saga, AtShipFood),
    lastAction' = ActOk(MakePaymentRef),
  }

  action makePaymentFail: bool = all {
    runningAt(saga, AtMakePayment),
    db' = db,
    saga' = startCompensation(COMP_WORKFLOW, saga, MakePaymentRef),
    lastAction' = ActFail(MakePaymentRef),
  }

  // 4. ship_food
  action shipFoodOk: bool = all {
    runningAt(saga, AtShipFood),
    db' = { ...db, shipment: ShipmentConfirmed },
    saga' = advance(saga, AtNotifyFork),
    lastAction' = ActOk(ShipFoodRef),
  }

  action shipFoodFail: bool = all {
    runningAt(saga, AtShipFood),
    db' = db,
    saga' = startCompensation(COMP_WORKFLOW, saga, ShipFoodRef),
    lastAction' = ActFail(ShipFoodRef),
  }

  // 5. notify_fork（notify_driver / notify_customer 並行）
  action notifyOk(t: TaskRef): bool = all {
    canNotify(saga, t),
    db' = db,
    saga' = { ...saga, forkDone: saga.forkDone.union(Set(t)) },
    lastAction' = ActOk(t),
  }

  action notifyFail(t: TaskRef): bool = all {
    canNotify(saga, t),
    db' = db,
    saga' = startCompensation(COMP_WORKFLOW, saga, t),
    lastAction' = ActFail(t),
  }

  // 6. notify_join
  action joinComplete: bool = all {
    canJoin(saga),
    db' = db,
    saga' = { ...saga, phase: Completed, stage: ForwardDone },
    lastAction' = ActJoin,
  }

  // 補償 workflow：執行佇列頭的 task
  action compensateOk: bool = all {
    hasPendingComp(saga),
    not(compTerminalError(COMP_WORKFLOW, db, saga.compQueue.head())),
    db' = applyComp(db, saga.compQueue.head()),
    saga' = popComp(saga),
    lastAction' = ActCompOk(saga.compQueue.head()),
  }

  action compensateTerminalError: bool = all {
    hasPendingComp(saga),
    compTerminalError(COMP_WORKFLOW, db, saga.compQueue.head()),
    db' = db,
    saga' = failComp(saga),
    lastAction' = ActCompTerminalError(saga.compQueue.head()),
  }

  // 終態（Completed / Compensated / CompensationFailed）沒有可執行的 action：
  // quint run 的 trace 會在此提早結束，這是預期行為。
  action step: bool = {
    nondet t = NOTIFY_TASKS.oneOf()
    any {
      startWorkflow,
      orderFoodOk,
      orderFoodFail,
      checkInventoryOk,
      checkInventoryFail,
      makePaymentOk,
      makePaymentFail,
      shipFoodOk,
      shipFoodFail,
      notifyOk(t),
      notifyFail(t),
      joinComplete,
      compensateOk,
      compensateTerminalError,
    }
  }

  // -------------------------------------------------------------------------
  // 不變量
  // -------------------------------------------------------------------------

  // 性質 1：終態一致性
  val inv_eventual_consistency: bool =
    if (saga.phase == Completed) {
      db.order == OrderCreated and db.payment == PaymentPaid and db.shipment == ShipmentConfirmed
    } else if (saga.phase == Compensated) {
      db.order != OrderCreated and db.payment != PaymentPaid and db.shipment != ShipmentConfirmed
    } else {
      true
    }

  // 性質 2：回滾後無懸空副作用
  val inv_no_dangling_effects: bool =
    (saga.phase == Compensated) implies
      (db.order != OrderCreated and db.payment != PaymentPaid and db.shipment != ShipmentConfirmed)

  // 性質 3：補償 workflow 不失敗
  val inv_no_compensation_failure: bool = saga.phase != CompensationFailed

  // 性質 4：出貨必先付款成功
  val inv_shipment_requires_payment: bool =
    (db.shipment == ShipmentConfirmed) implies (db.payment == PaymentPaid)

  val inv_safety: bool = and {
    inv_eventual_consistency,
    inv_no_dangling_effects,
    inv_no_compensation_failure,
    inv_shipment_requires_payment,
  }

  // -------------------------------------------------------------------------
  // Witness：每個 action 至少要在部分 trace 中發生（quint run --witnesses）
  // -------------------------------------------------------------------------
  val w_start: bool                 = lastAction == ActStart
  val w_order_food_ok: bool         = lastAction == ActOk(OrderFoodRef)
  val w_order_food_fail: bool       = lastAction == ActFail(OrderFoodRef)
  val w_check_inventory_ok: bool    = lastAction == ActOk(CheckInventoryRef)
  val w_check_inventory_fail: bool  = lastAction == ActFail(CheckInventoryRef)
  val w_make_payment_ok: bool       = lastAction == ActOk(MakePaymentRef)
  val w_make_payment_fail: bool     = lastAction == ActFail(MakePaymentRef)
  val w_ship_food_ok: bool          = lastAction == ActOk(ShipFoodRef)
  val w_ship_food_fail: bool        = lastAction == ActFail(ShipFoodRef)
  val w_notify_driver_ok: bool      = lastAction == ActOk(NotifyDriverRef)
  val w_notify_driver_fail: bool    = lastAction == ActFail(NotifyDriverRef)
  val w_notify_customer_ok: bool    = lastAction == ActOk(NotifyCustomerRef)
  val w_notify_customer_fail: bool  = lastAction == ActFail(NotifyCustomerRef)
  val w_join: bool                  = lastAction == ActJoin
  val w_cancel_delivery_ok: bool    = lastAction == ActCompOk(CancelDelivery)
  val w_cancel_payment_ok: bool     = lastAction == ActCompOk(CancelPayment)
  val w_cancel_order_ok: bool       = lastAction == ActCompOk(CancelOrder)
  val w_notify_failure_ok: bool     = lastAction == ActCompOk(NotifyFailure)
  val w_comp_terminal_error: bool   = match lastAction { | ActCompTerminalError(_) => true | _ => false }
  val w_completed: bool             = saga.phase == Completed
  val w_compensated: bool           = saga.phase == Compensated
}

// ===========================================================================
// 可執行實例
// ===========================================================================
module saga_prefix {
  import sagaTypes.*
  import saga(COMP_WORKFLOW = PreFixWorkflow).*
}

module saga_current {
  import sagaTypes.*
  import saga(COMP_WORKFLOW = CurrentWorkflow).*
}
