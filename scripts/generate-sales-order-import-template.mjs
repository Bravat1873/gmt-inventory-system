import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile, Workbook } from "@oai/artifact-tool";
import JSZip from "jszip";

const projectRoot = path.resolve(import.meta.dirname, "..");
const outputDir = path.join(projectRoot, "outputs", "order-import-template");
const previewDir = path.join(outputDir, "previews");
const outputPath = path.join(outputDir, "销售订单批量导入模板-库存表风格.xlsx");
const classpathPath = path.join(
  projectRoot,
  "backend",
  "app",
  "src",
  "main",
  "resources",
  "templates",
  "sales-order-import-template.xlsx",
);

async function preserveFrozenHeaderInExportedXlsx(xlsxPath) {
  const zip = await JSZip.loadAsync(await fs.readFile(xlsxPath));
  const sheetEntry = zip.file("xl/worksheets/sheet1.xml");
  if (!sheetEntry) throw new Error("Missing xl/worksheets/sheet1.xml");
  const sheetXml = await sheetEntry.async("string");
  if (sheetXml.includes("<x:pane ")) return;

  const emptyView = '<x:sheetView showGridLines="0" workbookViewId="0" />';
  const frozenView = '<x:sheetView showGridLines="0" workbookViewId="0"><x:pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen" /><x:selection pane="bottomLeft" activeCell="A2" sqref="A2" /></x:sheetView>';
  if (!sheetXml.includes(emptyView)) throw new Error("Unexpected sheetView XML; cannot preserve frozen header safely");

  zip.file("xl/worksheets/sheet1.xml", sheetXml.replace(emptyView, frozenView));
  const tableEntry = zip.file("xl/tables/table1.xml");
  if (!tableEntry) throw new Error("Missing xl/tables/table1.xml");
  const tableXml = await tableEntry.async("string");
  zip.file(
    "xl/tables/table1.xml",
    tableXml.replace(
      /<x:tableStyleInfo[^>]*\/>/,
      '<x:tableStyleInfo showFirstColumn="0" showLastColumn="0" showRowStripes="0" showColumnStripes="0" />',
    ),
  );
  const patched = await zip.generateAsync({
    type: "uint8array",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
  await fs.writeFile(xlsxPath, patched);
}

const workbook = Workbook.create();
const orderSheet = workbook.worksheets.add("订单导入");
const guideSheet = workbook.worksheets.add("填写说明");

const headers = [
  "外部订单号", "客户编码", "订单日期", "订单类型", "订单状态", "销售员", "产品编号",
  "客户料号", "订单数量", "含税单价", "业务联系人", "业务联系电话", "订单联系人",
  "订单联系电话", "财务联系人", "财务联系电话", "收货地址", "收货联系人",
  "收货联系电话", "发货方式", "订单备注",
];
const exampleRows = [
  [
    "DEMO-ORDER-001", "DEMO-CUSTOMER-001", new Date(Date.UTC(2026, 7, 18)), "工程订单", "正式订单",
    "示例销售员", "DEMO-PRODUCT-001", "DEMO-MATERIAL-001", 2, 1280.5, "示例业务联系人", "13800000001",
    "示例订单联系人", "13800000002", "示例财务联系人", "13800000003", "示例省示例市示例区示例路 1 号",
    "示例收货人", "13800000004", "物流配送", "占位示例：同一外部订单号的首条明细",
  ],
  [
    "DEMO-ORDER-001", "DEMO-CUSTOMER-001", new Date(Date.UTC(2026, 7, 18)), "工程订单", "正式订单",
    "示例销售员", "DEMO-PRODUCT-002", "DEMO-MATERIAL-002", 5, 860, "示例业务联系人", "13800000001",
    "示例订单联系人", "13800000002", "示例财务联系人", "13800000003", "示例省示例市示例区示例路 1 号",
    "示例收货人", "13800000004", "物流配送", "占位示例：同一外部订单号的第二条明细",
  ],
  [
    "DEMO-ORDER-002", "DEMO-CUSTOMER-002", new Date(Date.UTC(2026, 7, 19)), "零售订单", "草稿",
    "示例销售员", "DEMO-PRODUCT-003", "DEMO-MATERIAL-003", 1, 399.99, "示例业务联系人", "13800000011",
    "示例订单联系人", "13800000012", "示例财务联系人", "13800000013", "示例省示例市示例区示例路 2 号",
    "示例收货人", "13800000014", "客户自提", "占位示例：草稿订单不锁定库存",
  ],
];

orderSheet.getRange("A1:U4").values = [headers, ...exampleRows];
orderSheet.showGridLines = false;

const orderTable = orderSheet.tables.add("A1:U101", true, "SalesOrderImportTable");
orderTable.style = "TableStyleLight1";
orderTable.showBandedRows = false;
orderTable.showFilterButton = true;

for (let column = 0; column < headers.length; column += 1) {
  const headerCell = orderSheet.getCell(0, column);
  headerCell.format = {
    fill: "#FFFFFF",
    font: { bold: false, color: "#000000", size: 11 },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "all", style: "thin", color: "#000000" },
  };
}

orderSheet.getRange("A2:U101").format = {
  horizontalAlignment: "center",
  verticalAlignment: "center",
  wrapText: true,
  borders: { preset: "all", style: "thin", color: "#000000" },
};
orderSheet.getRange("A2:U4").format.rowHeight = 28;
orderSheet.getRange("A2:B101").format.numberFormat = "@";
orderSheet.getRange("G2:H101").format.numberFormat = "@";
orderSheet.getRange("L2:L101").format.numberFormat = "@";
orderSheet.getRange("N2:N101").format.numberFormat = "@";
orderSheet.getRange("P2:P101").format.numberFormat = "@";
orderSheet.getRange("S2:S101").format.numberFormat = "@";
orderSheet.getRange("C2:C101").format.numberFormat = "yyyy-mm-dd";
orderSheet.getRange("I2:I101").format.numberFormat = "#,##0";
orderSheet.getRange("J2:J101").format.numberFormat = "#,##0.00";
orderSheet.getRange("I2:J101").format.horizontalAlignment = "right";
orderSheet.getRange("A1:U1").format.rowHeight = 30;

const widths = [18, 18, 13, 14, 14, 14, 21, 20, 12, 14, 16, 16, 16, 16, 16, 16, 30, 16, 16, 16, 36];
for (let column = 0; column < widths.length; column += 1) {
  orderSheet.getRangeByIndexes(0, column, 101, 1).format.columnWidth = widths[column];
}

orderSheet.getRange("D2:D101").dataValidation = {
  rule: { type: "list", values: ["工程订单", "零售订单", "前置订单"] },
};
orderSheet.getRange("E2:E101").dataValidation = {
  rule: { type: "list", values: ["正式订单", "草稿"] },
};

const guideRows = [
  ["销售订单批量导入模板 · 填写说明", null, null, null],
  ["填写提示", "必填字段", "必填或可选请以本页字段说明为准。", "请保留表头文字和顺序"],
  ["提交方式", "预览后提交", "上传文件只会生成预览；核对订单分组与错误后，再确认提交。", "预览不会自动写入订单"],
  ["分组规则", "同单分组一致性", "同一外部订单号的客户编码、订单日期、订单类型、销售员、订单状态、联系人、收货信息、发货方式和备注必须一致。", "DEMO-ORDER-001 的两行应填写相同订单级字段"],
  ["唯一匹配", "客户编码", "客户编码必须唯一匹配一个已启用客户。", "DEMO-CUSTOMER-001 仅为占位示例"],
  ["唯一匹配", "产品编号", "产品编号必须唯一匹配一个已启用产品；同一订单可填写多个不同产品。", "DEMO-PRODUCT-001 仅为占位示例"],
  ["辅助核对", "客户料号", "客户料号不参与产品匹配，仅用于核对；若填写且与产品档案不一致，预览会报错。", "请以产品编号作为唯一匹配依据"],
  ["状态与库存", "正式订单", "正式订单提交后会按现有订单逻辑锁定库存，并生成采购缺口。", "订单状态：正式订单"],
  ["状态与库存", "草稿", "草稿订单不锁定库存。", "订单状态：草稿"],
  ["客户资金", "余额不扣", "导入订单不会自动扣减客户余额；资金扣减仍通过登记收款流程处理。", "余额仅用于显示和提醒"],
  ["重复检查", "外部订单号", "已有正式订单使用相同外部订单号时，整张订单会被拒绝。", "请勿重复使用已提交外部订单号"],
  ["示例数据", "必须替换", "模板内所有 DEMO 编码和示例联系人均为格式占位，不代表系统真实数据。", "导入前请替换全部示例值"],
  ["字段", "必填/可选", "填写规则", "错误示例"],
  ["外部订单号", "必填", "同一文件中的订单分组键；同单多行重复填写。", "空白或与已有正式订单重复"],
  ["客户编码", "必填", "唯一匹配已启用客户。", "编码不存在、重复或客户不可用"],
  ["订单日期", "必填", "填写合法日期，推荐 yyyy-mm-dd。", "2026-02-30"],
  ["订单类型", "必填", "仅限工程订单、零售订单、前置订单。", "填写未定义类型"],
  ["订单状态", "可选", "正式订单或草稿；空白按正式订单处理。", "填写其他状态"],
  ["销售员", "必填", "同一订单的所有明细保持一致。", "空白或同单多行不一致"],
  ["产品编号", "必填", "唯一匹配已启用产品。", "编码不存在、重复或产品不可用"],
  ["客户料号", "可选", "仅辅助核对，不参与产品匹配。", "与产品档案不一致"],
  ["订单数量", "必填", "正整数；正式订单不得低于产品最小起订量。", "0、负数、小数或低于起订量"],
  ["含税单价", "必填", "大于等于零；空白不会自动推断价格。", "负数或空白"],
  ["业务联系人/电话", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同联系人"],
  ["订单联系人/电话", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同联系人"],
  ["财务联系人/电话", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同联系人"],
  ["收货地址/联系人/电话", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同收货信息"],
  ["发货方式", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同发货方式"],
  ["订单备注", "可选", "同一订单的所有明细保持一致。", "同单多行填写不同备注"],
];

guideSheet.getRange(`A1:D${guideRows.length}`).values = guideRows;
guideSheet.getRange("A1:D1").merge();
guideSheet.showGridLines = false;
guideSheet.freezePanes.freezeRows(1);
guideSheet.getRange("A1:D1").format = {
  fill: "#FFFFFF",
  font: { bold: false, color: "#000000", size: 11 },
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#000000" },
};
guideSheet.getRange("A1:D1").format.rowHeight = 30;
guideSheet.getRange("A2:D12").format = {
  fill: "#FFFFFF",
  wrapText: true,
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#000000" },
};
guideSheet.getRange("A13:D13").format = {
  fill: "#FFFFFF",
  font: { bold: false, color: "#000000", size: 11 },
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#000000" },
};
guideSheet.getRange(`A14:D${guideRows.length}`).format = {
  wrapText: true,
  horizontalAlignment: "center",
  verticalAlignment: "center",
  borders: { preset: "all", style: "thin", color: "#000000" },
};
guideSheet.getRange(`A2:D${guideRows.length}`).format.autofitRows();
guideSheet.getRange(`A2:D${guideRows.length}`).format.rowHeight = 36;
guideSheet.getRange("A:A").format.columnWidth = 24;
guideSheet.getRange("B:B").format.columnWidth = 20;
guideSheet.getRange("C:C").format.columnWidth = 72;
guideSheet.getRange("D:D").format.columnWidth = 42;

orderSheet.freezePanes.freezeRows(1);
await fs.mkdir(previewDir, { recursive: true });
await fs.mkdir(path.dirname(classpathPath), { recursive: true });
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
await preserveFrozenHeaderInExportedXlsx(outputPath);
await fs.copyFile(outputPath, classpathPath);

const verificationWorkbook = await SpreadsheetFile.importXlsx(await FileBlob.load(outputPath));
const orderInspection = await verificationWorkbook.inspect({
  kind: "table",
  sheetId: "订单导入",
  range: "A1:U5",
  include: "values,formulas",
  tableMaxRows: 5,
  tableMaxCols: 21,
  maxChars: 12000,
});
const guideInspection = await verificationWorkbook.inspect({
  kind: "table",
  sheetId: "填写说明",
  range: `A1:D${guideRows.length}`,
  include: "values,formulas",
  tableMaxRows: guideRows.length,
  tableMaxCols: 4,
  maxChars: 18000,
});
const formulaErrors = await verificationWorkbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
  maxChars: 4000,
});
console.log("ORDER_INSPECT\n" + orderInspection.ndjson);
console.log("GUIDE_INSPECT\n" + guideInspection.ndjson);
console.log("FORMULA_ERROR_SCAN\n" + formulaErrors.ndjson);

for (const [sheetName, range, fileName] of [
  ["订单导入", "A1:U5", "订单导入.png"],
  ["填写说明", `A1:D${guideRows.length}`, "填写说明.png"],
]) {
  const preview = await verificationWorkbook.render({ sheetName, range, scale: 1, format: "png" });
  await fs.writeFile(path.join(previewDir, fileName), new Uint8Array(await preview.arrayBuffer()));
}

const outputBytes = await fs.readFile(outputPath);
const classpathBytes = await fs.readFile(classpathPath);
const outputSha = crypto.createHash("sha256").update(outputBytes).digest("hex");
const classpathSha = crypto.createHash("sha256").update(classpathBytes).digest("hex");
if (outputSha !== classpathSha) {
  throw new Error(`SHA-256 mismatch: output=${outputSha}, classpath=${classpathSha}`);
}
console.log(`OUTPUT=${outputPath}`);
console.log(`CLASSPATH_COPY=${classpathPath}`);
console.log(`SHA256=${outputSha}`);
