// -*- mode: Bluespec; -*-
//
// 餐點外送 Saga（Orkes Conductor 編排）的 Quint 規格。
// 對應來源：conductor/workflows/*.json、conductor/taskdefs/*.json、
//           src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java 與 service/*、dao/*。
// 執行方式、涵蓋範圍與已知缺陷見 specs/README.md。

// ===========================================================================
// 型別 (Types)
// ===========================================================================
module sagaTypes {
  // 補償 workflow（FoodDeliveryFailureWorkflow）的版本
  type CompWorkflow =
    | PreFixWorkflow    // ab60ba5 之前：SWITCH 版，make_payment_ref 分支為 cancel_payment → cancel_order
    | SwitchWorkflow    // ab60ba5 之後、#136–#139 修正之前：SWITCH on failedReferenceTaskNames[0]
    | CurrentWorkflow   // 現行 conductor/workflows/FoodDeliveryFailureWorkflow.v1.json：
                        // orderId = workflowId；一律反向執行冪等的 cancel_*（allowMissing = true）

  // 資料庫實體狀態（對應 orders / payments / shipments 的 status 欄位；None = 沒有這一列）
  type OrderStatus    = OrderNone | OrderCreated | OrderCancelled
  type PaymentStatus  = PaymentNone | PaymentFailed | PaymentPaid | PaymentCanceled
  type ShipmentStatus = ShipmentNone | ShipmentScheduled | ShipmentConfirmed | ShipmentCanceled

  type Db = {
    order:    OrderStatus,
    payment:  PaymentStatus,
    shipment: ShipmentStatus,
  }

  // 主 workflow 的 task reference name（可能出現在 failedReferenceTaskNames 中者）
  type TaskRef =
    | OrderFoodRef | CheckInventoryRef | MakePaymentRef | ShipFoodRef
    | NotifyDriverRef | NotifyCustomerRef | NotifyJoinRef

  // 正向流程的游標
  type Stage = AtOrderFood | AtCheckInventory | AtMakePayment | AtShipFood | AtNotifyFork | ForwardDone

  // 補償 workflow 的 SIMPLE task（NotifyFailure = notify_customer_failure_ref）
  type CompTask = CancelDelivery | CancelPayment | CancelOrder | NotifyFailure

  // Saga 整體階段：主 workflow + 補償 workflow 合併觀察
  type Phase =
    | Pending
    | Running
    | Completed            // 主 workflow COMPLETED
    | MainFailed           // 主 workflow FAILED / TIMED_OUT，failureWorkflow 尚未進入 SWITCH
    | Compensating         // 補償 workflow 執行中
    | Compensated          // 補償 workflow COMPLETED
    | CompensationFailed   // 補償 workflow FAILED

  type Saga = {
    phase:                   Phase,
    stage:                   Stage,
    forkDone:                Set[TaskRef],     // 並行分支已完成者
    failedRefs:              Set[TaskRef],     // failedWorkflow.failedReferenceTaskNames（HashSet，無序）
    compQueue:               List[CompTask],   // 補償 workflow 尚未執行的步驟（有序）
    customerFailureNotified: bool,
    compEnvFault:            bool,             // 補償 task 曾遇到基礎設施故障（重試用完仍失敗）
  }

  // 最近一次發生的 action（供 witness 與 trace 閱讀）
  type Act =
    | ActInit
    | ActStart
    | ActOk(TaskRef)
    | ActFail(TaskRef)                     // task 以 FAILED / FAILED_WITH_TERMINAL_ERROR 結束
    | ActPaymentInsertFail                 // make_payment：insertPayment 失敗，沒有付款列
    | ActShipFoodFail(ShipmentStatus)      // ship_food 失敗後出貨單的狀態
    | ActNotifyFailWithJoin(TaskRef)       // 並行分支失敗，且 JOIN 也搶先標成 FAILED（推論）
    | ActTransientFail(TaskRef)            // 失敗後由 Conductor 重試成功（留下 FAILED 嘗試）
    | ActTimeout({ task: TaskRef, applied: bool })  // TIMED_OUT；applied = 副作用已寫入但回報丟失
    | ActJoin
    | ActSelect(TaskRef)                   // SWITCH 取 failedReferenceTaskNames[0]
    | ActSelectDefault                     // 集合為空 → defaultCase
    | ActFullCompensation                  // 現行：不看失敗點，一律反向補償
    | ActCompOk(CompTask)
    | ActCompTerminalError(CompTask)
    | ActCompEnvFault(CompTask)
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
  // 常數集合
  // -------------------------------------------------------------------------

  pure val NOTIFY_TASKS: Set[TaskRef] = Set(NotifyDriverRef, NotifyCustomerRef)

  // 會被 worker 執行的正向 task（JOIN 為系統 task，不在此列）
  pure val FORWARD_TASKS: Set[TaskRef] =
    Set(OrderFoodRef, CheckInventoryRef, MakePaymentRef, ShipFoodRef, NotifyDriverRef, NotifyCustomerRef)

  pure val ALL_REFS: Set[TaskRef] = FORWARD_TASKS.union(Set(NotifyJoinRef))

  // ship_food 失敗時出貨單可能的狀態（ShipmentService.createShipment）：
  //   None      — insertShipment 失敗
  //   Canceled  — 找不到司機，服務自行 cancelShipment
  //   Scheduled — confirmShipment 拋出 DataAccessException，列停在 SCHEDULED
  pure val SHIP_FAIL_OUTCOMES: Set[ShipmentStatus] = Set(ShipmentNone, ShipmentCanceled, ShipmentScheduled)

  pure val initDb: Db = { order: OrderNone, payment: PaymentNone, shipment: ShipmentNone }

  pure val initSaga: Saga = {
    phase: Pending,
    stage: AtOrderFood,
    forkDone: Set(),
    failedRefs: Set(),
    compQueue: List(),
    customerFailureNotified: false,
    compEnvFault: false,
  }

  // -------------------------------------------------------------------------
  // 純函數：正向流程
  // -------------------------------------------------------------------------

  pure def stageOf(t: TaskRef): Stage =
    match t {
      | OrderFoodRef      => AtOrderFood
      | CheckInventoryRef => AtCheckInventory
      | MakePaymentRef    => AtMakePayment
      | ShipFoodRef       => AtShipFood
      | NotifyDriverRef   => AtNotifyFork
      | NotifyCustomerRef => AtNotifyFork
      | NotifyJoinRef     => ForwardDone
    }

  pure def nextStage(st: Stage): Stage =
    match st {
      | AtOrderFood      => AtCheckInventory
      | AtCheckInventory => AtMakePayment
      | AtMakePayment    => AtShipFood
      | AtShipFood       => AtNotifyFork
      | AtNotifyFork     => ForwardDone
      | ForwardDone      => ForwardDone
    }

  // task t 目前正在執行（已排程、尚未結束）
  pure def isRunning(s: Saga, t: TaskRef): bool = and {
    s.phase == Running,
    FORWARD_TASKS.contains(t),
    s.stage == stageOf(t),
    not(s.forkDone.contains(t)),
  }

  // task 成功時寫入資料庫的副作用
  pure def forwardEffect(d: Db, t: TaskRef): Db =
    match t {
      | OrderFoodRef   => { ...d, order: OrderCreated }
      | MakePaymentRef => { ...d, payment: PaymentPaid }
      | ShipFoodRef    => { ...d, shipment: ShipmentConfirmed }
      | _              => d
    }

  pure def afterOk(s: Saga, t: TaskRef): Saga =
    if (NOTIFY_TASKS.contains(t)) { ...s, forkDone: s.forkDone.union(Set(t)) }
    else { ...s, stage: nextStage(s.stage) }

  // 主 workflow 終止（FAILED / TIMED_OUT）。refs 是新增到 failedReferenceTaskNames 的 ref；
  // TIMED_OUT 的 task 不會被加入（WorkflowExecutorOps.terminateWorkflow 只收 FAILED*）。
  pure def terminateMain(s: Saga, refs: Set[TaskRef]): Saga =
    { ...s, phase: MainFailed, stage: ForwardDone, failedRefs: s.failedRefs.union(refs) }

  pure def canJoin(s: Saga): bool =
    s.phase == Running and s.stage == AtNotifyFork and s.forkDone == NOTIFY_TASKS

  // -------------------------------------------------------------------------
  // 純函數：補償 workflow
  // -------------------------------------------------------------------------

  // 補償 workflow 是否以 SWITCH on failedReferenceTaskNames[0] 決定補償內容
  pure def usesSwitch(wf: CompWorkflow): bool = wf != CurrentWorkflow

  // cancel_* 是否以 allowMissing = true 呼叫（查無資料視為已取消，回 COMPLETED）
  pure def allowMissing(wf: CompWorkflow): bool = wf == CurrentWorkflow

  // 現行補償 workflow：orderId 取自 failedWorkflow.workflowId（order_food 以它建立訂單），
  // 因此永遠已知；一律依正向步驟的反序取消
  pure val FULL_COMPENSATION: List[CompTask] = List(CancelDelivery, CancelPayment, CancelOrder)

  // SWITCH 版的 decisionCases 表；未列出者走 defaultCase: []
  pure def compensationPlan(wf: CompWorkflow, key: TaskRef): List[CompTask] =
    match key {
      | CheckInventoryRef => List(CancelOrder)
      | MakePaymentRef    => if (wf == PreFixWorkflow) List(CancelPayment, CancelOrder) else List(CancelOrder)
      | ShipFoodRef       => List(CancelPayment, CancelOrder)
      | NotifyDriverRef   => List(CancelDelivery, CancelPayment, CancelOrder)
      | NotifyCustomerRef => List(CancelDelivery, CancelPayment, CancelOrder)
      | _                 => List()
    }

  // SWITCH 之後一律執行 notify_customer_failure_ref
  pure def startCompensation(s: Saga, plan: List[CompTask]): Saga =
    { ...s, phase: Compensating, compQueue: plan.append(NotifyFailure) }

  // cancel_* 的 UPDATE ... WHERE orderId=? 影響 0 列 → FAILED_WITH_TERMINAL_ERROR
  pure def hasTargetRow(d: Db, t: CompTask): bool =
    match t {
      | CancelDelivery => d.shipment != ShipmentNone
      | CancelPayment  => d.payment != PaymentNone
      | CancelOrder    => d.order != OrderNone
      | NotifyFailure  => true
    }

  // 補償 task 回 FAILED_WITH_TERMINAL_ERROR：影響 0 列，且未允許查無資料
  pure def compTerminalError(wf: CompWorkflow, d: Db, t: CompTask): bool =
    not(allowMissing(wf)) and not(hasTargetRow(d, t))

  // 補償 task 成功時對資料庫的效果：UPDATE 不看原狀態，有列就改為 CANCELED；沒有列則不變
  pure def applyComp(d: Db, t: CompTask): Db =
    match t {
      | CancelDelivery => { ...d, shipment: if (d.shipment == ShipmentNone) ShipmentNone else ShipmentCanceled }
      | CancelPayment  => { ...d, payment: if (d.payment == PaymentNone) PaymentNone else PaymentCanceled }
      | CancelOrder    => { ...d, order: if (d.order == OrderNone) OrderNone else OrderCancelled }
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

  pure def failComp(s: Saga, envFault: bool): Saga =
    { ...s, phase: CompensationFailed, compQueue: List(), compEnvFault: s.compEnvFault or envFault }

  // 仍然生效、需要被補償的副作用
  pure def hasActiveEffects(d: Db): bool = or {
    d.order == OrderCreated,
    d.payment == PaymentPaid,
    d.shipment == ShipmentScheduled,
    d.shipment == ShipmentConfirmed,
  }

  // -------------------------------------------------------------------------
  // 狀態機：正向流程
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

  // 任一正向 task 成功（COMPLETED）
  action taskOk(t: TaskRef): bool = all {
    isRunning(saga, t),
    db' = forwardEffect(db, t),
    saga' = afterOk(saga, t),
    lastAction' = ActOk(t),
  }

  // 沒有副作用的失敗：
  //   order_food      — insertOrder / insertCustomer 失敗 → FAILED
  //   check_inventory — 無法供餐 → FAILED_WITH_TERMINAL_ERROR
  //   notify_*        — worker 例外 → FAILED（retryCount 0）
  action taskFail(t: TaskRef): bool = all {
    isRunning(saga, t),
    Set(OrderFoodRef, CheckInventoryRef).union(NOTIFY_TASKS).contains(t),
    db' = db,
    saga' = terminateMain(saga, Set(t)),
    lastAction' = ActFail(t),
  }

  // make_payment：付款被拒（卡片過期等）。createPayment 先 insert PENDING，再改為 FAILED
  action paymentDeclined: bool = all {
    isRunning(saga, MakePaymentRef),
    db' = { ...db, payment: PaymentFailed },
    saga' = terminateMain(saga, Set(MakePaymentRef)),
    lastAction' = ActFail(MakePaymentRef),
  }

  // make_payment：insertPayment 失敗（資料庫錯誤），沒有付款列
  action paymentInsertFail: bool = all {
    isRunning(saga, MakePaymentRef),
    db' = db,
    saga' = terminateMain(saga, Set(MakePaymentRef)),
    lastAction' = ActPaymentInsertFail,
  }

  // ship_food 失敗（FAILED，retryCount 0）
  action shipFoodFail(outcome: ShipmentStatus): bool = all {
    isRunning(saga, ShipFoodRef),
    SHIP_FAIL_OUTCOMES.contains(outcome),
    db' = { ...db, shipment: outcome },
    saga' = terminateMain(saga, Set(ShipFoodRef)),
    lastAction' = ActShipFoodFail(outcome),
  }

  // 並行分支失敗，非同步 JOIN 在 decide 之前也把自己標成 FAILED（依原始碼推論的競態）
  action notifyFailWithJoin(t: TaskRef): bool = all {
    isRunning(saga, t),
    NOTIFY_TASKS.contains(t),
    db' = db,
    saga' = terminateMain(saga, Set(t, NotifyJoinRef)),
    lastAction' = ActNotifyFailWithJoin(t),
  }

  // check_inventory（retryCount 3）暫時失敗後由 Conductor 重試：
  // FAILED 的嘗試留在 tasks[] 中，之後仍會被收進 failedReferenceTaskNames
  action transientFail(t: TaskRef): bool = all {
    isRunning(saga, t),
    t == CheckInventoryRef,
    not(saga.failedRefs.contains(t)),
    db' = db,
    saga' = { ...saga, failedRefs: saga.failedRefs.union(Set(t)) },
    lastAction' = ActTransientFail(t),
  }

  // 任一正向 task 逾時（TIME_OUT_WF / 回應逾時且重試用完）→ workflow TIMED_OUT。
  // applied = worker 已完成副作用但回報丟失。逾時的 task 不會進入 failedReferenceTaskNames。
  action taskTimeout(t: TaskRef, applied: bool): bool = all {
    isRunning(saga, t),
    db' = if (applied) forwardEffect(db, t) else db,
    saga' = terminateMain(saga, Set()),
    lastAction' = ActTimeout({ task: t, applied: applied }),
  }

  action joinComplete: bool = all {
    canJoin(saga),
    db' = db,
    saga' = { ...saga, phase: Completed, stage: ForwardDone },
    lastAction' = ActJoin,
  }

  // -------------------------------------------------------------------------
  // 狀態機：補償 workflow
  // -------------------------------------------------------------------------

  // SWITCH 取 failedReferenceTaskNames[0]。集合是 HashSet，[0] 取決於雜湊順序而非執行順序，
  // 因此以「集合中任一元素」過度近似。
  action selectCompensation(key: TaskRef): bool = all {
    usesSwitch(COMP_WORKFLOW),
    saga.phase == MainFailed,
    saga.failedRefs.contains(key),
    db' = db,
    saga' = startCompensation(saga, compensationPlan(COMP_WORKFLOW, key)),
    lastAction' = ActSelect(key),
  }

  // 集合為空（例如逾時）→ JSON 中沒有該 key → [0] 為 null → defaultCase: []
  action selectDefaultCompensation: bool = all {
    usesSwitch(COMP_WORKFLOW),
    saga.phase == MainFailed,
    saga.failedRefs == Set(),
    db' = db,
    saga' = startCompensation(saga, List()),
    lastAction' = ActSelectDefault,
  }

  // 現行：不看 failedReferenceTaskNames，一律 cancel_delivery → cancel_payment → cancel_order
  action startFullCompensation: bool = all {
    not(usesSwitch(COMP_WORKFLOW)),
    saga.phase == MainFailed,
    db' = db,
    saga' = startCompensation(saga, FULL_COMPENSATION),
    lastAction' = ActFullCompensation,
  }

  action compensateOk: bool = all {
    hasPendingComp(saga),
    not(compTerminalError(COMP_WORKFLOW, db, saga.compQueue.head())),
    db' = applyComp(db, saga.compQueue.head()),
    saga' = popComp(saga),
    lastAction' = ActCompOk(saga.compQueue.head()),
  }

  // 查無目標列且未允許 → FAILED_WITH_TERMINAL_ERROR → 補償 workflow FAILED
  action compensateTerminalError: bool = all {
    hasPendingComp(saga),
    compTerminalError(COMP_WORKFLOW, db, saga.compQueue.head()),
    db' = db,
    saga' = failComp(saga, false),
    lastAction' = ActCompTerminalError(saga.compQueue.head()),
  }

  // 基礎設施故障：資料庫例外（FAILED）重試 3 次仍失敗，或 task 逾時
  action compensateEnvFault: bool = all {
    hasPendingComp(saga),
    db' = db,
    saga' = failComp(saga, true),
    lastAction' = ActCompEnvFault(saga.compQueue.head()),
  }

  // 終態（Completed / Compensated / CompensationFailed）沒有可執行的 action：
  // quint run 的 trace 會在此提早結束，這是預期行為。
  //
  // 抽樣結構：nondet 只從「此刻可能啟用」的值中挑選，action 分成三組巢狀 any，
  // 避免故障類 action 淹沒正常進度。這只影響 quint run 的抽樣機率，不改變可達狀態集合。
  action step: bool = {
    val running = FORWARD_TASKS.filter(x => isRunning(saga, x))
    nondet t = (if (running == Set()) FORWARD_TASKS else running).oneOf()
    nondet key = (if (saga.failedRefs == Set()) ALL_REFS else saga.failedRefs).oneOf()
    nondet outcome = SHIP_FAIL_OUTCOMES.oneOf()
    nondet applied = Set(true, false).oneOf()
    any {
      // 正常進度
      any {
        startWorkflow,
        taskOk(t),
        joinComplete,
        selectCompensation(key),
        selectDefaultCompensation,
        startFullCompensation,
        compensateOk,
      },
      // 業務失敗（worker 依程式邏輯回報失敗）
      any {
        taskFail(t),
        paymentDeclined,
        paymentInsertFail,
        shipFoodFail(outcome),
        compensateTerminalError,
      },
      // 基礎設施故障與 Conductor 執行時期行為
      any {
        transientFail(t),
        taskTimeout(t, applied),
        notifyFailWithJoin(t),
        compensateEnvFault,
      },
    }
  }

  // -------------------------------------------------------------------------
  // 不變量
  // -------------------------------------------------------------------------

  // 性質 1：主 workflow 成功時，三個實體都已生效
  val inv_completed_consistent: bool =
    (saga.phase == Completed) implies
      (db.order == OrderCreated and db.payment == PaymentPaid and db.shipment == ShipmentConfirmed)

  // 性質 2：非成功的終態不得殘留生效中的副作用。
  // 唯一例外：補償 task 本身遇到基礎設施故障（需人工介入，見性質 3）。
  val inv_no_dangling_effects: bool =
    (saga.phase == Compensated or (saga.phase == CompensationFailed and not(saga.compEnvFault)))
      implies not(hasActiveEffects(db))

  // 性質 3：補償 workflow 只會因基礎設施故障而失敗，不會因設計錯誤（例如取消不存在的資料）而失敗
  val inv_compensation_failure_only_from_env: bool =
    (saga.phase == CompensationFailed) implies saga.compEnvFault

  // 性質 4：出貨必先付款成功
  val inv_shipment_requires_payment: bool =
    (db.shipment == ShipmentConfirmed) implies (db.payment == PaymentPaid)

  val inv_safety: bool = and {
    inv_completed_consistent,
    inv_no_dangling_effects,
    inv_compensation_failure_only_from_env,
    inv_shipment_requires_payment,
  }

  // -------------------------------------------------------------------------
  // Witness：每個 action 至少要在部分 trace 中發生（quint run --witnesses）
  // -------------------------------------------------------------------------
  pure def isTimeout(a: Act): bool = match a { | ActTimeout(_) => true | _ => false }
  pure def isTimeoutApplied(a: Act): bool = match a { | ActTimeout(r) => r.applied | _ => false }
  pure def isSelect(a: Act): bool = match a { | ActSelect(_) => true | _ => false }
  pure def isNotifyFailWithJoin(a: Act): bool = match a { | ActNotifyFailWithJoin(_) => true | _ => false }
  pure def isCompTerminalError(a: Act): bool = match a { | ActCompTerminalError(_) => true | _ => false }
  pure def isCompEnvFault(a: Act): bool = match a { | ActCompEnvFault(_) => true | _ => false }

  val w_start: bool                  = lastAction == ActStart
  val w_order_food_ok: bool          = lastAction == ActOk(OrderFoodRef)
  val w_order_food_fail: bool        = lastAction == ActFail(OrderFoodRef)
  val w_check_inventory_ok: bool     = lastAction == ActOk(CheckInventoryRef)
  val w_check_inventory_fail: bool   = lastAction == ActFail(CheckInventoryRef)
  val w_check_inventory_retry: bool  = lastAction == ActTransientFail(CheckInventoryRef)
  val w_make_payment_ok: bool        = lastAction == ActOk(MakePaymentRef)
  val w_payment_declined: bool       = lastAction == ActFail(MakePaymentRef)
  val w_payment_insert_fail: bool    = lastAction == ActPaymentInsertFail
  val w_ship_food_ok: bool           = lastAction == ActOk(ShipFoodRef)
  val w_ship_fail_none: bool         = lastAction == ActShipFoodFail(ShipmentNone)
  val w_ship_fail_canceled: bool     = lastAction == ActShipFoodFail(ShipmentCanceled)
  val w_ship_fail_scheduled: bool    = lastAction == ActShipFoodFail(ShipmentScheduled)
  val w_notify_driver_ok: bool       = lastAction == ActOk(NotifyDriverRef)
  val w_notify_driver_fail: bool     = lastAction == ActFail(NotifyDriverRef)
  val w_notify_customer_ok: bool     = lastAction == ActOk(NotifyCustomerRef)
  val w_notify_customer_fail: bool   = lastAction == ActFail(NotifyCustomerRef)
  val w_notify_fail_with_join: bool  = isNotifyFailWithJoin(lastAction)
  val w_timeout: bool                = isTimeout(lastAction)
  val w_timeout_applied: bool        = isTimeoutApplied(lastAction)
  val w_join: bool                   = lastAction == ActJoin
  val w_select: bool                 = isSelect(lastAction)
  val w_select_default: bool         = lastAction == ActSelectDefault
  val w_full_compensation: bool      = lastAction == ActFullCompensation
  val w_cancel_delivery_ok: bool     = lastAction == ActCompOk(CancelDelivery)
  val w_cancel_payment_ok: bool      = lastAction == ActCompOk(CancelPayment)
  val w_cancel_order_ok: bool        = lastAction == ActCompOk(CancelOrder)
  val w_notify_failure_ok: bool      = lastAction == ActCompOk(NotifyFailure)
  val w_comp_terminal_error: bool    = isCompTerminalError(lastAction)
  val w_comp_env_fault: bool         = isCompEnvFault(lastAction)
  val w_completed: bool              = saga.phase == Completed
  val w_compensated: bool            = saga.phase == Compensated
  val w_compensation_failed: bool    = saga.phase == CompensationFailed
}

// ===========================================================================
// 可執行實例
// ===========================================================================
module saga_prefix {
  import sagaTypes.*
  import saga(COMP_WORKFLOW = PreFixWorkflow).*
}

module saga_switch {
  import sagaTypes.*
  import saga(COMP_WORKFLOW = SwitchWorkflow).*
}

module saga_current {
  import sagaTypes.*
  import saga(COMP_WORKFLOW = CurrentWorkflow).*
}
