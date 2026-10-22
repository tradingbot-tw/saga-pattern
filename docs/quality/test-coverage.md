# 測試與行覆蓋率（就緒度 R2 的證據）

`docs/29` §5 的 **R2** 要求「可重現建置 ＋ CI 綠燈 ＋ 行覆蓋率 ≥ 60%」。這份文件是該項的證據來源，並說明怎麼重跑。

## 怎麼量

```bash
./gradlew clean test jacocoTestReport      # 產生報告
open build/reports/jacoco/test/html/index.html
./gradlew check                            # CI 用的門檻檢查（整體行覆蓋率 ≥ 60%）
```

`build.gradle` 的設定：

| 項目 | 內容 |
|---|---|
| 外掛 | `jacoco`（`toolVersion = '0.8.12'`） |
| 報告 | `test` 結束後自動執行 `jacocoTestReport`，產出 HTML 與 XML |
| 門檻 | `jacocoTestCoverageVerification`：整體 `LINE` 覆蓋率 **≥ 60%**，並掛在 `check` 上 |
| Lombok | 根目錄 `lombok.config` 開啟 `addLombokGeneratedAnnotation`，JaCoCo 會排除 Lombok 產生的 getter／setter，避免自動產生的程式碼稀釋分母 |

## 各模組行覆蓋率（2026-10-22 實測）

依 `factory/process.yaml` 的模組 `paths` glob 聚合 JaCoCo 的 per-class 行覆蓋率：

| 模組 | 行覆蓋 | 比例 | R2（≥60%） |
|---|---|---|---|
| ordering | 138 / 149 | 92.6% | ✅ |
| inventory | 20 / 23 | 87.0% | ✅ |
| payment | 89 / 94 | 94.7% | ✅ |
| shipment | 79 / 82 | 96.3% | ✅ |
| workflow | 194 / 206 | 94.2% | ✅ |
| **全部** | **524 / 558** | **93.9%** | ✅ |

未歸屬任何模組的類別：`dao/DataAccessException`、`pojos/CustomerNotificationRequest`（不影響上表）。

## 怎麼重算各模組數字

JaCoCo 的 XML 在 `build/reports/jacoco/test/jacocoTestReport.xml`，用 `factory/process.yaml` 的 glob 對 `src/main/java/<package>/<sourcefilename>` 聚合即可。開發環境用的腳本片段：

```bash
python3 - <<'PY'
import xml.etree.ElementTree as ET, re
tree = ET.parse('build/reports/jacoco/test/jacocoTestReport.xml')
rows = []
for pkg in tree.getroot().iter('package'):
    for cls in pkg.iter('class'):
        path = f"src/main/java/{pkg.get('name')}/{cls.get('sourcefilename')}"
        line = next((c for c in cls.findall('counter') if c.get('type') == 'LINE'), None)
        if line is not None:
            rows.append((path, int(line.get('covered')), int(line.get('missed'))))
MODULES = {
  'ordering':  ["src/main/java/**/controller/**", "src/main/java/**/service/OrderService.java",
                "src/main/java/**/dao/OrdersDAO.java", "src/main/java/**/pojos/Order*.java",
                "src/main/java/**/pojos/Customer.java", "src/main/java/**/pojos/FoodItem.java",
                "src/main/java/**/pojos/Restaurant.java"],
  'inventory': ["src/main/java/**/service/InventoryService.java", "src/main/java/**/dao/InventoryDAO.java",
                "src/main/java/**/pojos/CheckInventoryRequest.java", "src/main/java/**/pojos/FoodDeliveryRequest.java"],
  'payment':   ["src/main/java/**/service/PaymentService.java", "src/main/java/**/dao/PaymentsDAO.java",
                "src/main/java/**/pojos/Payment*.java"],
  'shipment':  ["src/main/java/**/service/ShipmentService.java", "src/main/java/**/dao/ShipmentDAO.java",
                "src/main/java/**/pojos/Shipment.java", "src/main/java/**/pojos/ShippingRequest.java",
                "src/main/java/**/pojos/Driver*.java", "src/main/java/**/pojos/CancelRequest.java"],
  'workflow':  ["src/main/java/**/SagaApplication.java", "src/main/java/**/service/WorkflowService.java",
                "src/main/java/**/workers/**", "src/main/java/**/dao/BaseDAO.java"],
}
def match(path, globs):
    for g in globs:
        rx = '^' + g.replace('.', r'\.').replace('**', '\u00a7').replace('*', '[^/]*').replace('\u00a7', '.*') + '$'
        if re.match(rx, path):
            return True
    return False
for name, globs in MODULES.items():
    cov = mis = 0
    for path, c, m in rows:
        if match(path, globs):
            cov += c; mis += m
    total = cov + mis
    print(f"{name}: {cov}/{total} = {cov / total * 100:.1f}%")
PY
```

## 注意事項

- 行覆蓋率只反映「被執行到的行」，不代表斷言有效；行為正確性仍由各測試的斷言（含 mutation 驗證過的案例）保證。
- `dao/BaseDAO` 含 DDL 與種子資料建立，測試覆蓋高是因為 `SagaTestDb` 會建立完整 schema；這不等於 production 的遷移情境已被驗證（repo 沒有 migration 框架）。
- 覆蓋率下降會讓 `./gradlew check` 失敗；若確實需要降低門檻，應在 PR 說明理由並經人類審核。
