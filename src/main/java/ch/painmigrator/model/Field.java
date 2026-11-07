package ch.painmigrator.model;

import ch.painmigrator.validate.Checksums;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Ein Eingabefeld einer Rückfrage. */
public record Field(String name, String label, String value, boolean required, int maxLength, Type type) {

    public enum Type {
        TEXT, IBAN, QR_IBAN, COUNTRY, DATE
    }

    public static Field text(String name, String label, String value, boolean required, int maxLength) {
        return new Field(name, label, value == null ? "" : value, required, maxLength, Type.TEXT);
    }

    public static Field of(String name, String label, String value, boolean required, int maxLength, Type type) {
        return new Field(name, label, value == null ? "" : value, required, maxLength, type);
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
            case QR_IBAN -> {
                if (!Checksums.isQrIban(v)) {
                    return "Das ist keine gültige QR-IBAN (IID 30000 bis 31999).";
                }
            }
            case COUNTRY -> {
                if (!v.matches("[A-Z]{2}")) {
                    return "Das Land muss als zweistelliger ISO-Code angegeben werden, z. B. CH.";
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(v);
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
