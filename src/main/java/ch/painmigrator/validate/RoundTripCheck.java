package ch.painmigrator.validate;

import ch.painmigrator.convert.Views;
import ch.painmigrator.model.PaymentView;
import ch.painmigrator.xml.Xml;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Vergleicht die fertige Datei unabhängig mit dem Original.
 *
 * <p>Die Ausgabe wird dazu aus den geschriebenen Bytes neu eingelesen. Abweichungen sind nur dort zulässig,
 * wo sie auf eine Benutzereingabe zurückgehen. Beträge und Währungen dürfen sich nie ändern.
 */
public final class RoundTripCheck {
    private RoundTripCheck() {
    }

    /**
     * @param original Zahlungen der Quelldatei
     * @param excludedIds bewusst ausgeschlossene Zahlungen
     * @param changes pro Zahlung die durch Benutzereingaben geänderten Felder
     * @param output fertige Datei
     * @return gefundene Abweichungen, leer bedeutet in Ordnung
     */
    public static List<String> compare(List<PaymentView> original, Set<String> excludedIds,
            Map<String, Set<String>> changes, Document output) {
        List<String> errors = new ArrayList<>();
        List<PaymentView> expected = new ArrayList<>();
        for (PaymentView p : original) {
            if (!excludedIds.contains(p.id())) {
                expected.add(p);
            }
        }
        List<PaymentView> actual = Views.read(output);
        if (expected.size() != actual.size()) {
            errors.add("Anzahl Zahlungen: erwartet " + expected.size() + ", gefunden " + actual.size());
            return errors;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < expected.size(); i++) {
            PaymentView a = expected.get(i);
            PaymentView b = actual.get(i);
            Set<String> allowed = changes.getOrDefault(a.id(), Set.of());
            String label = "Zahlung " + a.id() + " (" + a.endToEndId() + "): ";
            sum = sum.add(b.amount());
            if (a.amount().compareTo(b.amount()) != 0) {
                errors.add(label + "Betrag " + a.amount() + " wurde zu " + b.amount());
            }
            if (!Objects.equals(a.currency(), b.currency())) {
                errors.add(label + "Währung " + a.currency() + " wurde zu " + b.currency());
            }
            if (!allowed.contains("id")) {
                check(errors, label, "EndToEndId", a.endToEndId(), b.endToEndId());
                check(errors, label, "InstrId", a.instrId(), b.instrId());
                check(errors, label, "PmtInfId", a.pmtInfId(), b.pmtInfId());
            }
            if (!allowed.contains("account")) {
                check(errors, label, "Konto", Checksums.normalize(a.account()), Checksums.normalize(b.account()));
            }
            if (!allowed.contains("reference")) {
                check(errors, label, "Referenz", a.reference(), b.reference());
            }
            if (!allowed.contains("date")) {
                check(errors, label, "Ausführungsdatum", a.executionDate(), b.executionDate());
            }
            if (!allowed.contains("creditor")) {
                check(errors, label, "Empfänger", a.creditor(), b.creditor());
            }
        }
        Element header = Xml.child(Views.root(output), "GrpHdr");
        if (!String.valueOf(actual.size()).equals(Xml.text(header, "NbOfTxs"))) {
            errors.add("NbOfTxs im Kopf stimmt nicht mit der Anzahl Zahlungen überein.");
        }
        String control = Xml.text(header, "CtrlSum");
        if (control == null || new BigDecimal(control).compareTo(sum) != 0) {
            errors.add("CtrlSum im Kopf stimmt nicht mit der Summe der Zahlungen überein.");
        }
        return errors;
    }

    private static void check(List<String> errors, String label, String what, String a, String b) {
        if (!Objects.equals(a, b)) {
            errors.add(label + what + " «" + a + "» wurde zu «" + b + "»");
        }
    }
}
