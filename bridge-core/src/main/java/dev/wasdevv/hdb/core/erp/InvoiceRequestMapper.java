package dev.wasdevv.hdb.core.erp;

import dev.wasdevv.hdb.core.billing.InvoiceDraft;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/** Invoice draft to the ERP wire format. Any change here shows up in the contract test against the OpenAPI. */
@Mapper
public interface InvoiceRequestMapper {

    InvoiceRequestMapper INSTANCE = Mappers.getMapper(InvoiceRequestMapper.class);

    @Mapping(target = "patientReference", expression = "java(\"Patient/\" + draft.patientId())")
    @Mapping(target = "issueDate", expression = "java(draft.issueDate().toString())")
    ErpInvoiceRequest toRequest(InvoiceDraft draft);

    ErpInvoiceRequest.Line toLine(InvoiceDraft.Line line);
}
