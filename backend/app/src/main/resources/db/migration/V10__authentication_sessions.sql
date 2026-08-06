CREATE TABLE user_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_user_session_token UNIQUE (token_hash),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);
INSERT INTO sys_user(username,password_hash,display_name,enabled)
SELECT 'admin','$2b$12$budbHP4blgVi7rbdpNMA5eYa/D83VYzeh/I7RcK7dLLn/qSVwmu7O','管理员',TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username='admin');
