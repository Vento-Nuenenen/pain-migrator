package ch.painmigrator.validate;

import java.math.BigInteger;

/** Prüfziffern für IBAN, QR-IBAN, QR-Referenz und ISO-Creditor-Reference. */
public final class Checksums {
    private static final int[] MOD10_TABLE = {0, 9, 4, 6, 8, 2, 7, 1, 3, 5};
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private Checksums() {
    }

    public static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean mod97(String rearranged) {
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }

    public static boolean isValidIban(String iban) {
        String s = normalize(iban);
        if (!s.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}")) {
            return false;
        }
        if ((s.startsWith("CH") || s.startsWith("LI")) && s.length() != 21) {
            return false;
        }
        return mod97(s.substring(4) + s.substring(0, 4));
    }

    /** QR-IBAN: IBAN aus CH/LI, deren Institutsidentifikation (QR-IID) zwischen 30000 und 31999 liegt. */
    public static boolean isQrIban(String iban) {
        String s = normalize(iban);
        if (!isValidIban(s) || !(s.startsWith("CH") || s.startsWith("LI"))) {
            return false;
        }
        int iid = Integer.parseInt(s.substring(4, 9));
        return iid >= 30000 && iid <= 31999;
    }

    /** QR-Referenz: 27 Ziffern, letzte Stelle ist die Prüfziffer nach Modulo 10 rekursiv. */
    public static boolean isValidQrReference(String ref) {
        String s = normalize(ref);
        if (!s.matches("[0-9]{27}")) {
            return false;
        }
        int carry = 0;
        for (int i = 0; i < 26; i++) {
            carry = MOD10_TABLE[(carry + s.charAt(i) - '0') % 10];
        }
        return (10 - carry) % 10 == s.charAt(26) - '0';
    }

    /** ISO Creditor Reference nach ISO 11649: RF, zwei Prüfziffern, höchstens 25 Zeichen. */
    public static boolean isValidCreditorReference(String ref) {
        String s = normalize(ref);
        if (!s.matches("RF[0-9]{2}[A-Z0-9]{1,21}")) {
            return false;
        }
        return mod97(s.substring(4) + s.substring(0, 4));
    }
}
