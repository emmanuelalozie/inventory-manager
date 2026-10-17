package com.example.inventix.export;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Builds RFC 4180 CSV text (comma separated, CRLF line ends, fields quoted when needed).
 * <p>
 * Text that a spreadsheet would read as a formula (starting with =, +, -, @, tab or carriage return) gets a leading
 * apostrophe, so opening an export can't run a formula someone typed into a product name or note. Numbers are
 * written as plain numbers and are never prefixed.
 */
public final class CsvWriter {

    /** Byte order mark, so Excel opens the UTF-8 file with the right encoding. */
    public static final String BOM = "﻿";

    private final StringBuilder out = new StringBuilder(BOM);

    /** One row; null values become empty fields. */
    public CsvWriter row(Object... values) {
        return row(Arrays.asList(values)); // List.of would reject nulls
    }

    public CsvWriter row(List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(field(values.get(i)));
        }
        out.append("\r\n");
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String field(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
