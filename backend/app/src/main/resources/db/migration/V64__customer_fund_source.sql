ALTER TABLE customer_fund_request ADD COLUMN fund_source VARCHAR(20) NULL;
ALTER TABLE customer_fund_request ADD CONSTRAINT ck_customer_fund_source
    CHECK (fund_source IS NULL OR fund_source IN ('DOMESTIC','FOREX'));
CREATE INDEX idx_customer_fund_source ON customer_fund_request(fund_source, submitted_at);
