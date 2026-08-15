DROP TABLE IF EXISTS product_code_rule;

CREATE TABLE product_code_rule (
    id BIGINT PRIMARY KEY,
    category VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL
);
