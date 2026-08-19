UPDATE product_code_rule
SET display_name = REPLACE(display_name, 'è£…é¥°é', '装饰锁')
WHERE display_name LIKE '%è£…é¥°é%';

UPDATE sku
SET configuration = REPLACE(configuration, 'è£…é¥°é', '装饰锁')
WHERE configuration LIKE '%è£…é¥°é%';

UPDATE sku
SET product_configuration = REPLACE(product_configuration, 'è£…é¥°é', '装饰锁')
WHERE product_configuration LIKE '%è£…é¥°é%';
