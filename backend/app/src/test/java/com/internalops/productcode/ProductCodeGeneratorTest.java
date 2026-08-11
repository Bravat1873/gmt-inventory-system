package com.internalops.productcode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ProductCodeGeneratorTest {
    private JdbcTemplate jdbc;
    private ProductCodeGenerator generator;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:codegen;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP TABLE IF EXISTS product_code_rule");
        jdbc.execute("CREATE TABLE product_code_rule(id BIGINT PRIMARY KEY,category VARCHAR(40),code VARCHAR(20),enabled BOOLEAN)");
        insert(1,"BRAND","br",true); insert(2,"SERIES","P90",true); insert(3,"BODY_COLOR","HGT",true);
        insert(4,"LOCK_TYPE","60",true); insert(5,"CONNECTIVITY","W",true); insert(6,"SALES_CHANNEL","P",true);
        insert(7,"OPERATING_ENTITY","Z",true); insert(8,"LANGUAGE","E",true);
        insert(11,"DOOR_MODEL","M",true); insert(12,"SECURITY_GRADE","J",true); insert(13,"BASE_MATERIAL","3",true);
        insert(14,"THICKNESS","080",true); insert(15,"FINISH_COLOR","A",true);
        generator = new ProductCodeGenerator(jdbc);
    }

    @Test
    void generatesTheExactApprovedCodeInFixedOrder() {
        assertEquals("BR_P90HGT60WPZE", generator.generate(new ProductCodeSelection(1L,2L,3L,4L,5L,6L,7L,8L)));
    }

    @Test
    void generatesTheApprovedEntryDoorCodeWithBrandPrefixAndSeparator() {
        assertEquals("BR_MJ3080A", generator.generateEntryDoor(
                new EntryDoorProductCodeSelection(1L,11L,12L,13L,14L,15L)));
    }
    @Test
    void rejectsMissingDisabledAndWrongCategoryRules() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new ProductCodeSelection(1L,null,3L,4L,5L,6L,7L,8L)));
        jdbc.update("UPDATE product_code_rule SET enabled=FALSE WHERE id=3");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new ProductCodeSelection(1L,2L,3L,4L,5L,6L,7L,8L)));
        jdbc.update("UPDATE product_code_rule SET enabled=TRUE,category='LANGUAGE' WHERE id=3");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new ProductCodeSelection(1L,2L,3L,4L,5L,6L,7L,8L)));
    }

    private void insert(long id, String category, String code, boolean enabled) {
        jdbc.update("INSERT INTO product_code_rule(id,category,code,enabled) VALUES(?,?,?,?)", id,category,code,enabled);
    }
}
