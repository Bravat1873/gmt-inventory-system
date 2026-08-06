DROP TABLE IF EXISTS supplier_payment;
DROP TABLE IF EXISTS customer_receipt;
DROP TABLE IF EXISTS user_session;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS purchase_order;
DROP TABLE IF EXISTS sales_order_item;
DROP TABLE IF EXISTS sales_order;
DROP TABLE IF EXISTS supplier;
DROP TABLE IF EXISTS customer;

CREATE TABLE customer(
    id BIGINT PRIMARY KEY,
    customer_name VARCHAR(200) NOT NULL
);
CREATE TABLE sys_user(
    id BIGINT PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL
);
CREATE TABLE user_session(
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP
);
CREATE TABLE supplier(
    id BIGINT PRIMARY KEY,
    supplier_name VARCHAR(200) NOT NULL
);
CREATE TABLE sales_order(
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(50) NOT NULL,
    customer_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE sales_order_item(
    id BIGINT PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    shipped_quantity INT NOT NULL DEFAULT 0,
    sale_price DECIMAL(18,2) NOT NULL
);
CREATE TABLE customer_receipt(
    id BIGINT PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL
);
CREATE TABLE purchase_order(
    id BIGINT PRIMARY KEY,
    purchase_no VARCHAR(50) NOT NULL,
    supplier_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE supplier_payment(
    id BIGINT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL
);

INSERT INTO customer(id,customer_name) VALUES(1,'客户甲');
INSERT INTO sys_user(id,username,display_name,enabled) VALUES(9,'finance-test','财务测试',TRUE);
INSERT INTO user_session(id,user_id,token_hash,expires_at,revoked_at)
VALUES(9,9,'0762bbb22d5dbc5f919841b480a2873193873a3e7312032bee8a7ae0e35231e5',TIMESTAMP '2099-01-01 00:00:00',NULL);
INSERT INTO supplier(id,supplier_name) VALUES(2,'供应商乙');
INSERT INTO sales_order(id,order_no,customer_id,status,created_at,updated_at)
VALUES(10,'SO-F-001',1,'WAITING_STOCK',TIMESTAMP '2026-08-06 10:00:00',TIMESTAMP '2026-08-06 10:00:00');
INSERT INTO sales_order_item(id,sales_order_id,quantity,shipped_quantity,sale_price)
VALUES(11,10,5,1,20.00);
INSERT INTO customer_receipt(id,sales_order_id,amount) VALUES(12,10,30.00);
INSERT INTO purchase_order(id,purchase_no,supplier_id,status,total_amount,created_at,updated_at)
VALUES(20,'PO-F-002',2,'PENDING_SUPPLIER_PAYMENT',100.00,TIMESTAMP '2026-08-06 12:00:00',TIMESTAMP '2026-08-06 12:00:00');
INSERT INTO supplier_payment(id,purchase_order_id,amount) VALUES(21,20,40.00);
