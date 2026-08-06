export type ModuleKey = 'customer' | 'user' | 'product' | 'supplier' | 'order' | 'inventory' | 'purchase' | 'finance'

export interface ModuleDefinition {
  key: ModuleKey
  label: string
  description: string
  actionLabel: string
  columns: string[]
  fields: string[]
  sortable: string[]
  importType?: 'CUSTOMER' | 'COST' | 'INVENTORY'
}

export const moduleDefinitions: ModuleDefinition[] = [
  { key: 'customer', label: '客户管理', description: '字段与“客户信息.xlsx”保持一致', actionLabel: '导入客户', columns: ['客户名称', '修改时间'], fields: ['customerName', 'updatedAt'], sortable: ['customerName', 'updatedAt'], importType: 'CUSTOMER' },
  { key: 'user', label: '用户管理', description: '维护内部使用人员信息', actionLabel: '新增用户', columns: ['用户名', '姓名', '修改时间'], fields: ['username', 'displayName', 'updatedAt'], sortable: ['username', 'displayName', 'updatedAt'] },
  { key: 'product', label: '产品管理', description: '字段与“产品成本单价汇总20260520.xlsx”保持一致', actionLabel: '导入产品', columns: ['物料编号', '型号', '颜色', '锁体', '物料规格', '成本单价（含税）', '转厂价格', '差异：转厂价-原成本', '备注', '修改时间'], fields: ['skuCode', 'model', 'color', 'lockBody', 'configuration', 'currentCost', 'factoryPrice', 'priceDifference', 'remark', 'updatedAt'], sortable: ['skuCode', 'model', '', '', '', 'currentCost', 'factoryPrice', 'priceDifference', '', 'updatedAt'], importType: 'COST' },
  { key: 'supplier', label: '供应商管理', description: '维护供应商资料及其可供产品', actionLabel: '新增供应商', columns: ['供应商名称', '联系人', '联系电话', '银行账户', '供应产品数', '修改时间'], fields: ['supplierName', 'contactName', 'phone', 'bankAccount', 'productCount', 'updatedAt'], sortable: ['supplierName', 'contactName', 'phone', '', '', 'updatedAt'] },
  { key: 'order', label: '订单管理', description: '查看并处理客户销售订单', actionLabel: '新增订单', columns: ['订单编号', '客户', '订单金额', '订单状态', '订单日期', '销售员', '创建时间', '修改时间'], fields: ['orderNo', 'customerName', 'totalAmount', 'status', 'orderDate', 'salesperson', 'createdAt', 'updatedAt'], sortable: ['orderNo', 'customerName', 'totalAmount', 'status', 'createdAt', '', 'createdAt', 'updatedAt'] },
  { key: 'inventory', label: '库存管理', description: '字段与“吉门第库存汇总表.xlsx”保持一致', actionLabel: '导入库存', columns: ['物料编号 SKU', '型号', '产品配置', '版本', '颜色', '锁体', '单位', '实际库存数量', '可用库存数量', '已锁定数量', '铭爱钧乔', '博乐龙米', '老挝', '贝朗', '马来西亚', '在途数量', '供应商', '备注', '修改时间'], fields: ['skuCode', 'model', 'configuration', 'productVersion', 'color', 'lockBody', 'unit', 'actualQuantity', 'availableQuantity', 'lockedQuantity', 'lockedMingAiJunQiao', 'lockedBoLeLongMi', 'lockedLaos', 'lockedBeiLang', 'lockedMalaysia', 'inTransitQuantity', 'sourceSupplierName', 'inventoryRemark', 'updatedAt'], sortable: ['skuCode', 'model', '', '', '', '', '', 'actualQuantity', 'availableQuantity', '', '', '', '', '', '', '', '', '', 'updatedAt'], importType: 'INVENTORY' },
  { key: 'purchase', label: '采购管理', description: '处理缺货采购及供应商订单', actionLabel: '生成采购', columns: ['采购单号', '供应商', '产品', '采购金额', '订单状态', '预计到货', '修改时间'], fields: ['purchaseNo', 'supplierName', 'productSummary', 'totalAmount', 'status', 'expectedArrivalDate', 'updatedAt'], sortable: ['purchaseNo', 'supplierName', '', 'totalAmount', 'status', '', 'updatedAt'] },
  { key: 'finance', label: '财务管理', description: '自动汇总订单收款与采购付款', actionLabel: '', columns: ['业务单号', '业务类型', '往来单位', '应收/应付', '已收/已付', '未收/未付', '状态', '修改时间'], fields: ['businessNo', 'businessType', 'counterparty', 'amount', 'settledAmount', 'outstandingAmount', 'status', 'updatedAt'], sortable: ['businessNo', '', 'counterparty', 'amount', 'settledAmount', 'outstandingAmount', '', 'updatedAt'] }
]
