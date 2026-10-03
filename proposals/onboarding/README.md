# 納管建議：tradingbot-tw/saga-pattern（Issue #3）

本目錄是 **agent-onboard 的建議草稿**，供人類審核後裁定。它不是生效設定——
`factory-score` 讀 `catalog-info.yaml`、`factory-run` 讀 `.github/factory/risk-paths.yml`，
兩者都不看 `proposals/`，所以誤合併也不改變任何評級（docs/27 §2）。
**三軸與 risk-paths 必須由人類親手搬檔才生效**（docs/05 §1.1）。

- 掃描基準：`tradingbot-tw/saga-pattern` @ `software-factory`，commit `565bf09`
- 掃描方式：`git ls-files`（34 檔）、逐檔閱讀、`grep`，並以 minimatch 對實際檔案清單
  實測 risk-paths 的每條 glob
- 產出：`catalog-info.yaml`（三軸留 `TODO` + 建議值與證據）、`risk-paths.yml`

---

## 1. 掃描摘要

**語言／建置／測試**

| 項目 | 實際值 | 證據 |
| --- | --- | --- |
| 語言 | Java（Spring Boot 3.2.3 需 JDK 17+；repo 未釘版本、無 gradle wrapper） | `build.gradle` plugins `java` + `org.springframework.boot 3.2.3` |
| 建置 | Gradle（`build.gradle` / `settings.gradle`；**無** `gradlew`，裸 Gradle 專案） | `git ls-files` 無 wrapper |
| 測試框架 | JUnit 5（junit-bom 5.9.1 + `junit-jupiter` + `useJUnitPlatform()`）；**repo 目前無 `src/test`（尚無任何測試）** | `build.gradle` |
| 執行前提 | 需外部 Conductor server：`docker run ... conductoross/conductor-standalone:3.15.0` | `README.md` |

**目錄結構（真的存在，未臆測）**

```
README.md
build.gradle
settings.gradle
src/main/resources/application.properties
src/main/java/io/orkes/example/saga/
├── SagaApplication.java                  # 唯一進入點
├── controller/OrderServiceController.java  # 唯一 REST 端點
├── service/   Order/Inventory/Payment/Shipment/Workflow（5）
├── workers/   ConductorWorkers.java        # @WorkerTask 註冊
├── dao/       BaseDAO + 4 個 DAO           # sqlite；DDL 內嵌於 BaseDAO
└── pojos/     16 個 DTO/POJO
```

**關鍵發現**

- **單一服務、單一 DB、單一端點。** `SagaApplication` 起一台 Spring Boot（`server.port=8081`）；
  唯一對外合約是 `@PostMapping("/triggerFoodDeliveryFlow")`；單一 SQLite
  （`jdbc:sqlite:food_delivery.db`），7 張表由 `dao/BaseDAO.java` 以內嵌 `CREATE TABLE` +
  `tableExists()` 建立。
- **Saga 編排在 repo 之外。** `WorkflowService` 只設定 `name="FoodDeliveryWorkflow"` 後呼叫
  外部 Conductor（`play.orkes.io`）啟動；workflow 定義與補償邏輯不在版控內。repo 內的
  4 個 domain service + `ConductorWorkers` 是同行程 worker，**不是**跨服務協調。
- **無 migrations、無 openapi/proto、無 `api/`。** schema 在 `BaseDAO.java`。
- **無認證/授權程式碼。** grep auth/security 只命中 `application.properties` 的憑證佔位符。
- **金流是模擬。** `PaymentService.makePayment()` 只驗到期日就 `return true`（原始註解自承
  「skipping that and assuming payment went through」），金額原樣寫入本機 SQLite。
- **敏感資料僅佔位符與假資料，但有真實的敏感資料欄位／流向。**
  `application.properties` 的 `key-id=<key>`/`secret=<secret>`/`password=admin`；
  `pojos/PaymentDetails.java` 有 `number`/`expiry`/`cvv`；`pojos/Customer.java` 有
  email/name/contact；`WorkflowService` 會把 `customerEmail/Name/Contact` 送進外部 Conductor。
  種子資料為 `john.smith@example.com` 等假帳號。

---

## 2. 三軸建議值與理由（人類裁定；`catalog-info.yaml` 內留 `TODO`）

> 合法值以 `src/scoring/types.ts` 為單一真相來源：`tactical|operational|strategic`、
> `low|medium|high`、`low|medium|high`。**值缺席或非法一律 fail-safe 計為 2**，且無錯誤訊息
> （docs/27 §7.2）。搬檔前請把 3 個 `TODO` 換成下表（或您裁定）的值。

| 軸 | 建議 | 一句話 | 若裁定為此，基準分 |
| --- | --- | --- | --- |
| `factory.io/business-criticality` | **tactical (0)** | 內部學習用範例，非正式服務；Issue #3 自述「僅影響範例與試點驗證，無正式環境或外部使用者」 | 0 |
| `factory.io/risk-profile` | **low (0)** | 無認證授權、無真實金流、無加密金鑰管理；憑證是佔位符、PII 是假種子資料 | 0 |
| `factory.io/complexity` | **low (0)** | 30 檔、單模組、單 DB、單端點；Saga 編排在外部 Conductor，不在 repo | 0 |

**完整理由（含「為何不是更高／更低」）寫在 `catalog-info.yaml` 各軸的註解內**，摘要：

- **business-criticality = tactical**：README 開頭即 "This is an example project..."，且需自行
  起 Conductor 才跑得動；無 Dockerfile/k8s/CI/部署設定。故障只影響範例試跑，不影響任何
  正式服務或外部使用者。tactical 已是量表最低值。
- **risk-profile = low**：無授權決策程式碼；付款是模擬（`PaymentService` 只驗到期日）；
  憑證全是佔位符；PII 是假資料。**殘餘風險值得標注**——`PaymentDetails` 帶卡號/CVV、
  `Customer` 帶 PII、且 `WorkflowService` 會把客戶聯絡資訊外送到第三方 Conductor；本提案
  以 risk-paths 的 H3 精準涵蓋這些檔案，而非靠整體 risk 升級。若您認為 PII 外送本身即應
  整體升級，可裁定 **medium**。
- **complexity = low**：對照 docs/16 §5.1，15 檔／單表／6 端點的 factory-scoreboard 已評
  low；本 repo 較大但同為單模組、單 DB、單端點，且無 MQ、無第三方 client、無 migration。

**其他 annotations**：`factory.io/agent-automerge: "false"`（新納管不預設開放）；
`factory.io/stack: java-springboot`、`factory.io/test-framework: junit`（依實際掃描，非風險裁定）；
`spec.owner: group:default/factory-team` 沿用機制 repo，**若應指向 tradingbot-tw 團隊請一併更正**。

---

## 3. risk-paths 摘要（完整內容見 `risk-paths.yml`）

| 規則 | 是否寫入 | 命中／說明 |
| --- | --- | --- |
| H1 認證授權 | 空 `[]` | 掃描未發現任何 auth/security 程式碼，待人類確認 |
| H2 財務計算 | 有 | `src/main/java/**/Payment*.java` → 6 檔（PaymentService、PaymentsDAO、4 個 Payment POJO） |
| H3 敏感資料 | 有 | `application.properties`（憑證）、`**/PaymentDetails.java`（卡號/CVV）、`**/Customer.java`（PII） |
| H4 systems of record | 有 | `src/main/java/**/dao/**` → BaseDAO + 4 DAO |
| H5 guardrail 自身 | 有（無條件） | `.github/**`、`CODEOWNERS`、`catalog-info.yaml`、`.dsh/skills/**`（目前尚不存在，搬檔後生效） |
| H6 schema 遷移 | 空 `[]` | 無 `migrations/`；DDL 內嵌於 `BaseDAO.java`，已由 H4 涵蓋。是否另立 H6 待人類確認 |
| H7 對外 API | 有 | `src/main/java/**/controller/**`、`**/FoodDeliveryRequest.java` |

**刻意不用泛樣式。** 沒有 `**/*secret*` / `**/*token*`：本 repo 無此類檔名，泛樣式是死規則，
且日後新增同名無關檔案就會誤報——誤報會訓練審查者略過警訊（docs/16 §5.3 的
`src/styles/tokens.css` 教訓）。每條 glob 的命中檔案已逐一在 `risk-paths.yml` 註解標明，
並用 minimatch 對 `git ls-files` 實測過。

**已在本機驗證**：以機制 repo 的 `factory-score --catalog ... --risk-paths ...` 讀取本提案，
YAML 可解析、schema 通過；三個 `TODO` 如預期 fail-safe 成 `total 6 / in-loop`（證明
「留 TODO」期間不會被誤讀成低風險）。

---

## 4. 搬檔指令（**人類**審核後執行）

先在 `catalog-info.yaml` 把 3 個 `TODO` 換成裁定的合法值（並確認 `spec.owner`），再：

```bash
git mv proposals/onboarding/catalog-info.yaml .
mkdir -p .github/factory
git mv proposals/onboarding/risk-paths.yml .github/factory/risk-paths.yml
git rm -r proposals/onboarding
```

合併進 `software-factory` 分支。**merge 後這兩個檔案受 CODEOWNERS 保護，agent 不得再寫入。**

---

## 5. 合併後的驗證（docs/16 §5.2，**雙向探測**）

設定檔寫對與「工廠真的讀得到」只有合併後才分得出來。開 `factory/*` 探針 PR，兩個方向都要驗：

```bash
gh workflow run factory-rescore.yml --repo aswf-dev/software_factory \
  -f repo=tradingbot-tw/saga-pattern -f base_branch=software-factory -f pr_number=<PR>
```

| 探測內容 | 期望（以本提案建議值 tactical/low/low 為例） |
| --- | --- |
| 只改一般檔案（如 `README.md` 或 `SagaApplication.java`） | 基準分 `total 0`、`triggeredHardRules: []`、`escalated: false`、`tier on-loop` |
| 改一個硬規則路徑（如 `src/main/java/.../dao/BaseDAO.java`） | 分數上升（`H4` 命中 → risk=2，`total 2`）、`triggeredHardRules` 出現對應規則、`escalated: true` |

> **兩個方向都要驗**：只驗一般檔案無法區分「硬規則正確」與「硬規則根本沒載入」。
> 若三軸裁定值不同，期望的絕對分數請按該值換算；重點是「一般檔案不觸發、硬規則路徑必觸發」。

驗證通過後，把本 repo 補進機制 repo `docs/16` §5 的已納管清單。

---

## 6. 審查者快速檢查清單

- [ ] 三軸 `TODO` 是否已換成合法值（勿留幽靈值如 `supporting`）
- [ ] 是否同意 `agent-automerge: "false"`
- [ ] `spec.owner` 是否正確
- [ ] risk-paths 每條 glob 是否會誤中無關檔案（本檔已標命中清單與 minimatch 實測結果）
- [ ] H1/H6 留空的理由是否接受
- [ ] 搬檔後執行 §5 雙向探測
