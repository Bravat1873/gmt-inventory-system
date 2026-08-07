ALTER TABLE sys_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

UPDATE sys_user
SET role = 'ADMIN'
WHERE username = 'admin';

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_role CHECK (role IN ('ADMIN', 'FINANCE', 'USER'));
