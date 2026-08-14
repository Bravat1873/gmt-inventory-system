package com.internalops.numbering;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Service
public class DocumentNumberService {
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int MONTHLY_LIMIT = 99_999;

    private final JdbcTemplate jdbc;

    public DocumentNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String next(DocumentType type, LocalDate businessDate) {
        Objects.requireNonNull(type, "单据类型不能为空");
        Objects.requireNonNull(businessDate, "业务日期不能为空");
        String yearMonth = businessDate.format(YEAR_MONTH);

        try {
            jdbc.update("""
                    INSERT INTO document_number_sequence(document_type, year_month, current_value)
                    VALUES (?, ?, LAST_INSERT_ID(1))
                    ON DUPLICATE KEY UPDATE current_value=LAST_INSERT_ID(current_value + 1)
                    """, type.name(), yearMonth);
        } catch (DataIntegrityViolationException exception) {
            Integer current = jdbc.queryForObject("""
                    SELECT current_value FROM document_number_sequence
                    WHERE document_type=? AND year_month=?
                    """, Integer.class, type.name(), yearMonth);
            if (current != null && current >= MONTHLY_LIMIT) {
                throw new IllegalStateException("本月单号已达到 99999 上限", exception);
            }
            throw exception;
        }

        Integer sequence = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        if (sequence == null || sequence > MONTHLY_LIMIT) {
            throw new IllegalStateException("本月单号已达到 99999 上限");
        }
        return type.prefix() + yearMonth + String.format("%05d", sequence);
    }
}
