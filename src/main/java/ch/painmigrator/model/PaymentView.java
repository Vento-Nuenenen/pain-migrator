package ch.painmigrator.model;

import java.math.BigDecimal;

/** Eine Zahlung so, wie sie in der Vorschau erscheint und beim Vergleich alt gegen neu geprüft wird. */
public record PaymentView(
        String id,
        String pmtInfId,
        String endToEndId,
        String instrId,
        String oldType,
        String newType,
        String creditor,
        String creditorAddress,
        String account,
        BigDecimal amount,
        String currency,
        String executionDate,
        String reference,
        Status status) {

    public enum Status {
        OK, REVIEW, OPEN, EXCLUDED
    }

    public PaymentView withStatus(Status s) {
        return new PaymentView(id, pmtInfId, endToEndId, instrId, oldType, newType, creditor, creditorAddress,
                account, amount, currency, executionDate, reference, s);
    }
}
