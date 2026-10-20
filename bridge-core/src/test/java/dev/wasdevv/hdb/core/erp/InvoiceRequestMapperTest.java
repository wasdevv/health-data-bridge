package dev.wasdevv.hdb.core.erp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.core.billing.InvoiceDraft;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceRequestMapperTest {

    @Test
    void mapsDraftToWireFormat() {
        InvoiceDraft draft = new InvoiceDraft("enc-7", "pat-3", "CUST-002", LocalDate.of(2026, 10, 1), "BRL",
                List.of("I10", "E11.9"),
                List.of(new InvoiceDraft.Line("10101012", "Consulta", BigDecimal.ONE, new BigDecimal("150.00"),
                        new BigDecimal("150.00"))),
                new BigDecimal("150.00"));

        ErpInvoiceRequest request = InvoiceRequestMapper.INSTANCE.toRequest(draft);

        assertThat(request).isEqualTo(new ErpInvoiceRequest("enc-7", "CUST-002", "Patient/pat-3", "2026-10-01", "BRL",
                List.of("I10", "E11.9"),
                List.of(new ErpInvoiceRequest.Line("10101012", "Consulta", BigDecimal.ONE, new BigDecimal("150.00"),
                        new BigDecimal("150.00"))),
                new BigDecimal("150.00")));
    }
}
