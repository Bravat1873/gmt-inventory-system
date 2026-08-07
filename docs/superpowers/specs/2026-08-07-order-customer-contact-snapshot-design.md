# 订单客户三联系人快照设计

## 目标

订单选择客户后，从客户管理带入业务、订单、财务三类联系人及客户地址，并将这些资料保存为订单快照。客户资料后续修改不影响历史订单。

## 字段映射

- 客户管理“业务联系人/业务电话”带入订单 `businessContactName/businessContactPhone`。
- 客户管理“订单联系人/订单电话”带入订单 `orderContactName/orderContactPhone`。
- 客户管理“财务联系人/财务电话”带入订单 `financeContactName/financeContactPhone`。
- 订单联系人和订单电话同时作为新订单的默认 `deliveryContact/deliveryPhone`。
- 客户地址作为新订单的默认 `deliveryAddress`。
- 现有 `customerContact/customerPhone` 保持兼容，并与订单联系人快照保存相同值，避免旧页面、导出或查询失效。

## 页面交互

- 订单“客户信息”区域保留客户选择器，并显示业务、订单、财务三张紧凑联系人卡片。
- 每张卡片包含联系人姓名和电话，允许在本订单内修改。
- 收货信息区域继续允许独立修改联系人、电话和地址。
- 用户重新选择客户时，重新带入三类联系人、收货联系人、电话和地址；编辑已有订单初始化时不覆盖已保存快照。
- 客户资料缺少某类联系人时对应字段留空，不使用其他联系人冒充；只有兼容字段 `customerContact/customerPhone` 在保存时取订单联系人。

## 数据与接口

- 新增 Flyway 迁移，为 `sales_order` 增加六个可空快照字段：`business_contact_name`、`business_contact_phone`、`order_contact_name`、`order_contact_phone`、`finance_contact_name`、`finance_contact_phone`。
- `SalesOrderRequest` 增加六个字段；新增和修改订单均写入快照。
- 订单详情返回六个 camelCase 字段。
- 客户选项接口补齐业务、订单、财务联系人字段，空值返回空字符串。
- 历史订单六个字段为空时，页面可将旧 `customerContact/customerPhone` 显示为订单联系人，但不自动写回数据库。

## 兼容与验证

- 迁移只新增可空列，不修改已有订单数据。
- 发货默认地址仍使用订单保存的收货地址，不直接读取客户当前资料。
- 合同价格、订单库存分配和收款逻辑不变。
- 后端测试覆盖客户选项字段、创建/修改保存快照、详情返回和旧订单兼容。
- 前端测试覆盖选择客户自动带入六字段及收货信息、编辑订单不覆盖、订单内修改后请求载荷正确。
- 完成后运行后端完整测试、前端完整测试和生产构建，并重启后端应用数据库迁移。
