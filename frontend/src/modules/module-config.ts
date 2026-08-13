export type ModuleKey = 'customer' | 'user' | 'product' | 'supplier' | 'order' | 'afterSales' | 'inventory' | 'purchase' | 'finance'

export interface ModuleDefinition {
  key: ModuleKey
  label: string
  description: string
  actionLabel: string
  columns: string[]
  fields: string[]
  sortable: string[]
  importType?: 'CUSTOMER' | 'COST' | 'INVENTORY' | 'SUPPLIER'
}

export const moduleDefinitions: ModuleDefinition[] = [
  { key: 'customer', label: '客户管理', description: '维护客户资料、合同有效期和固定价格', actionLabel: '导入客户', columns: ['客户编码', '客户名称', '客户地址', '订单联系人', '订单电话', '合同状态', '合同到期日', '修改时间'], fields: ['customerCode', 'customerName', 'address', 'orderContactName', 'orderContactPhone', 'contractStatus', 'contractEndDate', 'updatedAt'], sortable: ['customerCode', 'customerName', '', '', '', '', '', 'updatedAt'], importType: 'CUSTOMER' },
  { key: 'user', label: '用户管理', description: '维护内部使用人员信息', actionLabel: '新增用户', columns: ['用户名', '姓名', '角色', '修改时间'], fields: ['username', 'displayName', 'role', 'updatedAt'], sortable: ['username', 'displayName', '', 'updatedAt'] },
  { key: 'product', label: '产品管理', description: '维护产品编号、客户编号、品牌系列及产品资料', actionLabel: '导入产品', columns: ['图片', '产品编号', '客户料号', '品牌', '型号', '产品分类', '物料类型', '物料规格', '产品配置', '成本单价（含税）', '转厂价格', '差异：转厂价-原成本', '备注', '修改时间'], fields: ['productImage', 'productCode', 'customerCode', 'brand', 'model', 'productType', 'materialType', 'configuration', 'productConfiguration', 'currentCost', 'factoryPrice', 'priceDifference', 'remark', 'updatedAt'], sortable: ['', 'productCode', 'customerCode', '', 'model', '', '', '', '', 'currentCost', 'factoryPrice', 'priceDifference', '', 'updatedAt'], importType: 'COST' },
  { key: 'supplier', label: '供应商管理', description: '维护供应商完整资料及其可供产品', actionLabel: '导入供应商', columns: ['厂商分类', '厂商类型', '供应商地点', '产品属性', '简称', '供应商名称', '联系人', '职称', '联系方式', '供应商地址', '币种', '税务登记号', '开户地址', '开户账户', '供应产品数', '修改时间'], fields: ['manufacturerCategory', 'manufacturerType', 'supplierLocation', 'productAttribute', 'shortName', 'supplierName', 'contactName', 'contactTitle', 'phone', 'address', 'currency', 'taxRegistrationNo', 'bankAddress', 'bankAccount', 'productCount', 'updatedAt'], sortable: ['', '', '', '', '', 'supplierName', 'contactName', '', 'phone', '', '', '', '', '', '', 'updatedAt'], importType: 'SUPPLIER' },
  { key: 'order', label: '订单管理', description: '查看并处理客户销售订单', actionLabel: '新增订单', columns: ['订单编号', '客户', '订单金额', '订单状态', '订单日期', '销售员', '创建时间', '修改时间'], fields: ['orderNo', 'customerName', 'totalAmount', 'status', 'orderDate', 'salesperson', 'createdAt', 'updatedAt'], sortable: ['orderNo', 'customerName', 'totalAmount', 'status', 'createdAt', '', 'createdAt', 'updatedAt'] },
  { key: 'afterSales', label: '售后管理', description: '处理客户退货与换货', actionLabel: '新增售后', columns: ['售后单号', '原订单号', '客户', '售后类型', '退回数量', '换出数量', '处理状态', '申请日期', '修改时间'], fields: ['afterSalesNo', 'orderNo', 'customerName', 'afterSalesType', 'returnQuantity', 'replacementQuantity', 'status', 'applicationDate', 'updatedAt'], sortable: ['afterSalesNo', 'orderNo', 'customerName', '', '', '', 'status', 'applicationDate', 'updatedAt'] },
{ key: 'inventory', label: '库存管理', description: '字段与“吉门第库存汇总表.xlsx”保持一致', actionLabel: '导入库存', columns: ['产品编号', '型号', '产品类型', '产品配置', '物料规格', '单位', '实际库存数量', '未锁定库存数量', '最早在库日期', '库龄', '已锁定数量', '在途数量', '待交订单数量', '供需余量', '供应商', '备注', '修改时间'], fields: ['productCode', 'model', 'productType', 'productConfiguration', 'configuration', 'unit', 'actualQuantity', 'availableQuantity', 'oldestStockDate', 'inventoryAgeDays', 'lockedQuantity', 'inTransitQuantity', 'pendingDeliveryQuantity', 'supplyDemandBalance', 'sourceSupplierName', 'inventoryRemark', 'updatedAt'], sortable: ['productCode', 'model', '', '', '', '', 'actualQuantity', 'availableQuantity', '', '', '', '', '', '', '', '', 'updatedAt'], importType: 'INVENTORY' },
  { key: 'purchase', label: '采购管理', description: '处理缺货采购及供应商订单', actionLabel: '生成采购', columns: ['采购单号', '供应商', '产品', '采购金额', '付款进度', '收货进度', '订单状态', '预计到货', '修改时间'], fields: ['purchaseNo', 'supplierName', 'productSummary', 'totalAmount', 'paymentStatus', 'receiptStatus', 'status', 'expectedArrivalDate', 'updatedAt'], sortable: ['purchaseNo', 'supplierName', '', 'totalAmount', '', '', 'status', '', 'updatedAt'] },
  { key: 'finance', label: '财务管理', description: '自动汇总订单收款与采购付款', actionLabel: '', columns: ['业务单号', '业务类型', '往来单位', '应收/应付', '已收/已付', '未收/未付', '状态', '修改时间'], fields: ['businessNo', 'businessType', 'counterparty', 'amount', 'settledAmount', 'outstandingAmount', 'status', 'updatedAt'], sortable: ['businessNo', '', 'counterparty', 'amount', 'settledAmount', 'outstandingAmount', '', 'updatedAt'] }
]
