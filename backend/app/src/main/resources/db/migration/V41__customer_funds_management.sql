CREATE TABLE customer_fund_account (
    customer_id BIGINT PRIMARY KEY,
    balance DECIMAL(19,4) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_fund_account_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT ck_fund_account_non_negative CHECK (balance >= 0)
);

INSERT INTO customer_fund_account(customer_id)
SELECT id FROM customer;

CREATE TABLE customer_fund_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    request_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount DECIMAL(19,4) NOT NULL,
    payment_date DATE,
    payment_method VARCHAR(60),
    reference_no VARCHAR(100),
    source_type VARCHAR(30),
    source_id BIGINT,
    remark VARCHAR(1000),
    review_comment VARCHAR(1000),
    submitted_by BIGINT,
    submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_by BIGINT,
    reviewed_at DATETIME(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_fund_request_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_fund_request_submitter FOREIGN KEY (submitted_by) REFERENCES sys_user(id),
    CONSTRAINT fk_fund_request_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id),
    CONSTRAINT ck_fund_request_amount CHECK (amount > 0),
    CONSTRAINT ck_fund_request_type CHECK (request_type IN ('CUSTOMER_DEPOSIT','AFTER_SALES_REFUND')),
    CONSTRAINT ck_fund_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','REVERSED')),
    INDEX idx_fund_request_customer_status (customer_id, status, submitted_at),
    INDEX idx_fund_request_status_time (status, submitted_at)
);

CREATE TABLE customer_fund_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    balance_before DECIMAL(19,4) NOT NULL,
    balance_after DECIMAL(19,4) NOT NULL,
    request_id BIGINT,
    source_type VARCHAR(30),
    source_id BIGINT,
    source_no VARCHAR(80),
    reverses_ledger_id BIGINT,
    reason VARCHAR(1000),
    operated_by BIGINT,
    operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_fund_ledger_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_fund_ledger_request FOREIGN KEY (request_id) REFERENCES customer_fund_request(id),
    CONSTRAINT fk_fund_ledger_reversal FOREIGN KEY (reverses_ledger_id) REFERENCES customer_fund_ledger(id),
    CONSTRAINT fk_fund_ledger_operator FOREIGN KEY (operated_by) REFERENCES sys_user(id),
    CONSTRAINT ck_fund_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_fund_ledger_direction CHECK (direction IN ('IN','OUT')),
    CONSTRAINT ck_fund_ledger_balances CHECK (balance_before >= 0 AND balance_after >= 0),
    CONSTRAINT uk_fund_ledger_request UNIQUE (request_id),
    CONSTRAINT uk_fund_ledger_reversal UNIQUE (reverses_ledger_id),
    INDEX idx_fund_ledger_customer_time (customer_id, operated_at, id),
    INDEX idx_fund_ledger_source (source_type, source_id)
);