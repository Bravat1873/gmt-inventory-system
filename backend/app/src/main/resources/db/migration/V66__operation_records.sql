CREATE TABLE operation_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    action VARCHAR(200) NOT NULL,
    module_name VARCHAR(80) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(1000) NOT NULL,
    actor_id BIGINT,
    actor_name VARCHAR(100) NOT NULL,
    actor_username VARCHAR(80) NOT NULL,
    actor_role VARCHAR(20) NOT NULL,
    operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3),
    status VARCHAR(20) NOT NULL,
    http_status INT,
    client_ip VARCHAR(80),
    change_count INT NOT NULL DEFAULT 0,
    affected_modules TEXT,
    related_records TEXT,
    non_reversible_reason VARCHAR(500),
    result_message VARCHAR(1000),
    reverses_id BIGINT,
    reversed_by_operation_id BIGINT,
    reversal_reason VARCHAR(1000),
    legacy_log_id BIGINT,
    INDEX idx_operation_time (operated_at,id),
    INDEX idx_operation_status (status,id),
    INDEX idx_operation_actor (actor_id,id),
    UNIQUE KEY uk_operation_reversal (reverses_id),
    UNIQUE KEY uk_operation_legacy (legacy_log_id)
);

CREATE TABLE operation_change (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id BIGINT NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    record_key VARCHAR(500) NOT NULL,
    change_type VARCHAR(10) NOT NULL,
    before_data JSON,
    after_data JSON,
    INDEX idx_operation_change (operation_id,id),
    CONSTRAINT fk_operation_change_record FOREIGN KEY (operation_id) REFERENCES operation_record(id)
);

CREATE TABLE operation_entity (
    operation_id BIGINT NOT NULL,
    entity_key VARCHAR(300) NOT NULL,
    PRIMARY KEY (operation_id,entity_key),
    INDEX idx_operation_entity_key (entity_key,operation_id),
    CONSTRAINT fk_operation_entity_record FOREIGN KEY (operation_id) REFERENCES operation_record(id)
);

-- This row serializes application writes, including undo, across backend instances.
CREATE TABLE operation_write_lock (id INT PRIMARY KEY);
INSERT INTO operation_write_lock(id) VALUES(1);

-- Transactional connection context: never leaks an operation id into the connection pool.
CREATE TABLE operation_context (connection_id BIGINT PRIMARY KEY, operation_id BIGINT NOT NULL);

CREATE TABLE operation_audit_table (
    table_name VARCHAR(100) PRIMARY KEY,
    key_columns JSON NOT NULL,
    data_columns JSON NOT NULL
);

INSERT INTO operation_record(action,module_name,http_method,request_path,actor_id,actor_name,
    actor_username,actor_role,operated_at,completed_at,status,non_reversible_reason,legacy_log_id)
SELECT l.action,'历史记录','LEGACY',l.action,l.operated_by,COALESCE(u.display_name,'历史操作人员'),
    COALESCE(u.username,''),COALESCE(u.role,''),l.operated_at,l.operated_at,'LEGACY',
    '旧日志未保存变更前后的完整数据，无法撤回',l.id
FROM operation_log l LEFT JOIN sys_user u ON u.id=l.operated_by;
