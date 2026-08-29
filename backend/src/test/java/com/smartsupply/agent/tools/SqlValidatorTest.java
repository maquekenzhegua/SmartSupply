package com.smartsupply.agent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlValidatorTest {
    private final SqlValidator v = new SqlValidator();

    @Test void allowsSimpleSelect() {
        assertDoesNotThrow(() -> v.validate("SELECT * FROM supplier WHERE rating > 4"));
    }
    @Test void allowsJoinAcrossBusinessTables() {
        assertDoesNotThrow(() -> v.validate("select s.name, i.quantity from sku s join inventory i on i.sku_id=s.id"));
    }
    @Test void rejectsNonSelect() {
        assertThrows(IllegalArgumentException.class, () -> v.validate("DELETE FROM supplier"));
        assertThrows(IllegalArgumentException.class, () -> v.validate("update supplier set rating=5"));
        assertThrows(IllegalArgumentException.class, () -> v.validate("DROP TABLE supplier"));
    }
    @Test void rejectsForbiddenKeywords() {
        assertThrows(IllegalArgumentException.class, () -> v.validate("SELECT * FROM supplier; -- comment"));
        assertThrows(IllegalArgumentException.class, () -> v.validate("SELECT * FROM supplier WHERE 1=1; truncate table supplier"));
    }
    @Test void rejectsNonBusinessTable() {
        assertThrows(IllegalArgumentException.class, () -> v.validate("SELECT * FROM sys_user"));
        assertThrows(IllegalArgumentException.class, () -> v.validate("SELECT * FROM pg_tables"));
    }
    @Test void allowsSelectFromAnyBusinessTable() {
        for (String tbl : new String[]{"supplier","inventory","purchase_order","contract","sku","product"}) {
            assertDoesNotThrow(() -> v.validate("SELECT * FROM " + tbl + " LIMIT 10"), "should allow " + tbl);
        }
    }
}
