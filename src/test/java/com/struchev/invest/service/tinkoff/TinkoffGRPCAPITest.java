package com.struchev.invest.service.tinkoff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TinkoffGRPCAPITest {

    @Test
    void shouldConvertPriceWithFraction() {
        assertQuotation("125.50", 125L, 500000000);
    }

    @Test
    void shouldConvertWholePrice() {
        assertQuotation("100.00", 100L, 0);
    }

    @Test
    void shouldConvertPriceBelowOne() {
        assertQuotation("0.01", 0L, 10000000);
    }

    @Test
    void shouldConvertZeroPrice() {
        assertQuotation("0.00", 0L, 0);
    }

    @Test
    void shouldPreserveNineDecimalPlaces() {
        assertQuotation("12.345678901", 12L, 345678901);
    }

    @Test
    void shouldConvertLargePrice() {
        assertQuotation("125000.99", 125000L, 990000000);
    }

    private void assertQuotation(
            String price,
            long expectedUnits,
            int expectedNano
    ) {
        var quotation = TinkoffGRPCAPI.toQuotation(
                new BigDecimal(price)
        );

        assertEquals(expectedUnits, quotation.getUnits());
        assertEquals(expectedNano, quotation.getNano());
    }
}
