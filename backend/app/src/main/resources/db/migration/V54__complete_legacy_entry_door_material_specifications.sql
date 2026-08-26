-- Some legacy entry-door rows already have a brand rule but still retain the
-- remaining door attributes only in the retired rule columns.
UPDATE sku s
LEFT JOIN product_code_rule brand ON brand.id = s.brand_rule_id AND brand.category = 'BRAND'
LEFT JOIN product_code_rule door_model ON door_model.id = s.door_model_rule_id AND door_model.category = 'DOOR_MODEL'
LEFT JOIN product_code_rule security_grade ON security_grade.id = s.security_grade_rule_id AND security_grade.category = 'SECURITY_GRADE'
LEFT JOIN product_code_rule base_material ON base_material.id = s.base_material_rule_id AND base_material.category = 'BASE_MATERIAL'
LEFT JOIN product_code_rule thickness ON thickness.id = s.thickness_rule_id AND thickness.category = 'THICKNESS'
LEFT JOIN product_code_rule finish_color ON finish_color.id = s.finish_color_rule_id AND finish_color.category = 'FINISH_COLOR'
SET s.configuration = CONCAT_WS(' / ',
    COALESCE(brand.display_name, CASE WHEN s.product_code LIKE 'SXSEL_%' THEN 'STANLEY' END),
    NULLIF(TRIM(s.model), ''),
    door_model.display_name,
    security_grade.display_name,
    base_material.display_name,
    thickness.display_name,
    finish_color.display_name
)
WHERE s.product_type = 'ENTRY_DOOR'
  AND s.door_model_rule_id IS NOT NULL;
