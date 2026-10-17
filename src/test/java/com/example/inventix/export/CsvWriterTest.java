package com.example.inventix.export;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void startsWithByteOrderMark_andEndsRowsWithCrLf() {
        String csv = new CsvWriter().row("a", "b").row(1, 2).toString();

        assertThat(csv).isEqualTo(CsvWriter.BOM + "a,b\r\n1,2\r\n");
    }

    @Test
    void nullValuesBecomeEmptyFields() {
        assertThat(new CsvWriter().row("a", null, 3).toString()).isEqualTo(CsvWriter.BOM + "a,,3\r\n");
    }

    @Test
    void quotesFieldsWithCommasQuotesOrLineBreaks() {
        assertThat(CsvWriter.field("Hub, 7-in-1")).isEqualTo("\"Hub, 7-in-1\"");
        assertThat(CsvWriter.field("27\" Monitor")).isEqualTo("\"27\"\" Monitor\"");
        assertThat(CsvWriter.field("line 1\nline 2")).isEqualTo("\"line 1\nline 2\"");
        assertThat(CsvWriter.field(null)).isEmpty();
    }

    @Test
    void textThatLooksLikeAFormulaIsDefused_butNumbersAreNot() {
        assertThat(CsvWriter.field("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(CsvWriter.field("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.field("-5 broken")).isEqualTo("'-5 broken");
        assertThat(CsvWriter.field("@sum")).isEqualTo("'@sum");
        assertThat(CsvWriter.field(-5)).isEqualTo("-5");
        assertThat(CsvWriter.field(new BigDecimal("1E+3"))).isEqualTo("1000");
    }
}
