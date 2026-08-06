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
