# GMT 智能进销存管理系统

GMT 智能进销存管理系统提供客户、产品、订单、库存、采购和财务管理能力，支持 Excel 导入。

## Docker 部署

服务器需要安装 Docker Engine 与 Docker Compose Plugin。推荐 Linux 服务器使用 Ubuntu 22.04 或更高版本。

### 1. 上传项目并准备配置

将整个项目目录上传到服务器后，在项目根目录执行：

```bash
cp .env.example .env
nano .env
```

至少修改下面两项为不同的强密码：

```dotenv
MYSQL_PASSWORD=你的应用数据库强密码
MYSQL_ROOT_PASSWORD=你的数据库管理员强密码
```

若服务器的 `80` 端口已被其他网站使用，将 `APP_PORT=80` 改为例如 `APP_PORT=8080`。

### 2. 首次启动

```bash
docker compose up -d --build
```

首次构建会下载基础镜像并编译前后端，完成后访问：

```text
http://服务器IP
```

若设置了 `APP_PORT=8080`，则访问 `http://服务器IP:8080`。

系统健康检查地址：

```text
http://服务器IP/api/system/health
```

### 3. 常用运维命令

```bash
# 查看服务状态
docker compose ps

# 持续查看日志
docker compose logs -f

# 只查看后端日志
docker compose logs -f backend

# 停止服务（保留数据库与附件）
docker compose down

# 拉取新代码后更新
docker compose up -d --build
```

### 4. 备份与恢复

备份数据库：

```bash
mkdir -p backups
docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backups/gmt-$(date +%F).sql
```

恢复数据库前请先停止业务写入，然后执行：

```bash
cat backups/gmt-2026-08-06.sql | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
```

数据库数据保存在 Docker 卷 `gmt-inventory_mysql_data`，上传附件保存在 `gmt-inventory_attachment_data`。不要执行 `docker compose down -v`，否则这两个持久化卷会被删除。

## 服务结构

- `frontend`：Nginx 托管 Vue 前端，并代理 `/api` 请求。
- `backend`：Spring Boot 服务，启动时自动执行数据库迁移。
- `mysql`：MySQL 8 数据库，业务数据使用持久化卷保存。

默认仅开放前端 HTTP 端口；数据库和后端不会直接暴露到公网。

## 业务全景查看

在“订单管理”“采购管理”或“财务管理”的每一条记录右侧点击“查看”，可打开只读的业务全景：

- 订单可看到创建、客户收款、销售发票、库存锁定、订单发货出库及因缺货产生的关联采购单。
- 采购可看到创建、供应商付款、采购在途、到货入库以及它所补充的关联销售订单。
- 财务记录会自动打开对应的销售订单或采购订单全景；时间线中的关联单据也可继续点击查看。

库存、金额和状态均读取已办理的业务记录；“查看”不改变库存或单据状态。

## 供应商资料导入

供应商管理支持导入 `.xls` 和 `.xlsx` 文件。选择文件后应先查看预览结果：预览只解析、映射和校验文件，不会更新供应商主数据；确认行数、14 个字段的内容以及错误行为零后，再执行提交。

提交时可选择两种模式：

- `覆盖同名供应商（OVERWRITE）`：按规范化后的供应商名称更新同名记录并恢复启用状态，新增文件中的新供应商；文件中未出现的现有供应商保持原状态。
- `全量替换（REPLACE_ALL）`：同样更新同名记录并新增新供应商，同时将文件中未出现的现有供应商设为停用。该模式要求预览中没有错误行，适合以文件作为完整供应商清单的场景。

全量替换采用软停用，不会删除供应商记录，因此既有采购单、采购建议和产品供应商配置等历史外键仍然有效。停用的供应商不会作为当前可选供应商使用，但历史业务记录仍可追溯。

执行 `REPLACE_ALL` 前，建议先按上文“备份与恢复”章节备份数据库，并核对预览中的供应商数量、名称、税务登记号、开户地址和开户账户等文本字段。若文件不是完整清单，请使用 `OVERWRITE`，避免误停用文件外供应商。
