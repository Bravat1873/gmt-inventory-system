CREATE TABLE product_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(50) NOT NULL,
  file_size BIGINT NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order INT NOT NULL,
  uploaded_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES sku(id) ON DELETE CASCADE,
  CONSTRAINT uk_product_image_storage_key UNIQUE (storage_key),
  CONSTRAINT uk_product_image_order UNIQUE (product_id, sort_order),
  INDEX idx_product_image_product_primary (product_id, is_primary)
);
