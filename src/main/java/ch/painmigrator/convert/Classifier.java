package ch.painmigrator.convert;

import ch.painmigrator.xml.Xml;
import org.w3c.dom.Element;

/** Bestimmt die Zahlungsart nach den Swiss Payment Standards. */
final class Classifier {

    /**
     * Werte der Zahlungsgruppe, einmal pro Gruppe gelesen.
     *
     * <p>Ein fehlendes Element auf Gruppenebene zu suchen heisst, alle Zahlungen der Gruppe zu durchlaufen.
     * Pro Zahlung wiederholt, wäre das bei grossen Dateien quadratisch.
     */
    record Group(String method, String localInstrument, String serviceLevel) {
        static Group of(Element group) {
            return new Group(Xml.text(group, "PmtMtd"), Xml.text(group, "PmtTpInf/LclInstrm/Prtry"),
                    Xml.text(group, "PmtTpInf/SvcLvl/Cd"));
        }
    }

    private Classifier() {
    }

    private static String either(Element tx, String path, String groupValue) {
        String v = Xml.text(tx, path);
        return v != null ? v : groupValue;
    }

    private static boolean isChLi(String code) {
        return "CH".equals(code) || "LI".equals(code);
    }

    /** Bank in CH/LI anhand von IID oder BIC. */
    private static boolean domesticAgent(Element tx) {
        Element fi = Xml.find(tx, "CdtrAgt/FinInstnId");
        if (fi == null) {
            return false;
        }
        if ("CHBCC".equals(Xml.text(fi, "ClrSysMmbId/ClrSysId/Cd"))) {
            return true;
        }
        String bic = Xml.text(fi, "BICFI");
        return bic != null && bic.length() >= 6 && isChLi(bic.substring(4, 6));
    }

    private static boolean domestic(Element tx) {
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        return iban != null ? iban.length() >= 2 && isChLi(iban.substring(0, 2)) : domesticAgent(tx);
    }

    private static String currency(Element tx) {
        Element instructed = Xml.find(tx, "Amt/InstdAmt");
        return instructed != null ? instructed.getAttribute("Ccy") : Xml.text(tx, "Amt/EqvtAmt/CcyOfTrf");
    }

    /** Zahlungsart nach SPS 2021 (Tabellen 3 bis 5 der Implementation Guidelines 1.11). */
    static String oldType(Element tx, Group group) {
        if ("CHK".equals(group.method())) {
            return "8";
        }
        String local = either(tx, "PmtTpInf/LclInstrm/Prtry", group.localInstrument());
        if ("CH01".equals(local)) {
            return "1";
        }
        if ("CH02".equals(local)) {
            return "2.1";
        }
        if ("CH03".equals(local)) {
            return "2.2";
        }
        if ("SEPA".equals(either(tx, "PmtTpInf/SvcLvl/Cd", group.serviceLevel()))) {
            return "5";
        }
        if (!domestic(tx)) {
            return "6";
        }
        String ccy = currency(tx);
        return "CHF".equals(ccy) || "EUR".equals(ccy) ? "3" : "4";
    }

    /** Zahlungsart nach SPS 2026 (Abbildung 10 der Implementation Guidelines 2.3). */
    static String newType(Element tx, Group group) {
        if ("CHK".equals(group.method())) {
            return "C";
        }
        if ("SEPA".equals(either(tx, "PmtTpInf/SvcLvl/Cd", group.serviceLevel()))) {
            return "S";
        }
        if (!domestic(tx)) {
            return "X";
        }
        String ccy = currency(tx);
        return "CHF".equals(ccy) || "EUR".equals(ccy) ? "D" : "X";
    }
}
