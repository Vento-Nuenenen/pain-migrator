package ch.painmigrator.model;

import ch.painmigrator.validate.Checksums;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Ein Eingabefeld einer Rückfrage. */
public record Field(String name, String label, String value, boolean required, int maxLength, Type type,
        LocalDate notBefore) {

    public enum Type {
        TEXT, IBAN, DEBTOR_IBAN, QR_IBAN, QR_REFERENCE, CREDITOR_REFERENCE, COUNTRY, DATE
    }

    public static Field text(String name, String label, String value, boolean required, int maxLength) {
        return of(name, label, value, required, maxLength, Type.TEXT);
    }

    public static Field of(String name, String label, String value, boolean required, int maxLength, Type type) {
        return new Field(name, label, value == null ? "" : value, required, maxLength, type, null);
    }

    /** Pflichtfeld für ein Datum, das nicht vor {@code notBefore} liegen darf. */
    public static Field date(String name, String label, String value, LocalDate notBefore) {
        return new Field(name, label, value, true, 10, Type.DATE, notBefore);
    }

    /** Prüft eine Eingabe und liefert die Fehlermeldung oder null, wenn sie gültig ist. */
    public String validate(String input) {
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) {
            return required ? label + " ist erforderlich." : null;
        }
        if (v.length() > maxLength) {
            return label + " darf höchstens " + maxLength + " Zeichen lang sein.";
        }
        switch (type) {
            case IBAN -> {
                if (!Checksums.isValidIban(v)) {
                    return "Die IBAN ist ungültig (Format oder Prüfziffer).";
                }
            }
            case DEBTOR_IBAN -> {
                if (!Checksums.isValidIban(v)) {
                    return "Die IBAN ist ungültig (Format oder Prüfziffer).";
                }
                if (Checksums.isQrIban(v)) {
                    return "Eine QR-IBAN ist als Belastungskonto nicht erlaubt.";
                }
            }
            case QR_IBAN -> {
                if (!Checksums.isQrIban(v)) {
                    return "Das ist keine gültige QR-IBAN (IID 30000 bis 31999).";
                }
            }
            case QR_REFERENCE -> {
                if (!Checksums.isValidQrReference(v)) {
                    return "Die QR-Referenz ist ungültig (27 Ziffern mit Prüfziffer).";
                }
            }
            case CREDITOR_REFERENCE -> {
                if (!Checksums.isValidCreditorReference(v)) {
                    return "Die ISO-Referenz ist ungültig (RF mit Prüfziffern).";
                }
            }
            case COUNTRY -> {
                if (!v.matches("[A-Z]{2}")) {
                    return "Das Land muss als zweistelliger ISO-Code angegeben werden, z. B. CH.";
                }
            }
            case DATE -> {
                try {
                    LocalDate date = LocalDate.parse(v);
                    if (notBefore != null && date.isBefore(notBefore)) {
                        return "Das Datum darf nicht vor dem " + notBefore + " liegen.";
                    }
                } catch (DateTimeParseException e) {
                    return "Das Datum muss im Format JJJJ-MM-TT angegeben werden.";
                }
            }
            default -> {
            }
        }
        return null;
    }
}
