package ch.painmigrator.validate;

import ch.painmigrator.convert.AddressParser;
import ch.painmigrator.convert.Views;
import ch.painmigrator.model.ChangeLog;
import ch.painmigrator.model.ChangeLog.Leaf;
import ch.painmigrator.xml.Xml;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Vergleicht jeden Wert der Quelle mit der fertigen Datei.
 *
 * <p>Erwartet wird: Quelle, in die neuen Pfade übersetzt, plus Änderungsprotokoll. Die Übersetzung steht hier
 * bewusst ein zweites Mal und nutzt keinen Code der Umwandlung, damit ein Fehler dort auffällt.
 */
public final class FullComparison {
    private static final int MAX_ERRORS = 30;
    private static final String HEADER_PATH = "CstmrCdtTrfInitn/GrpHdr/";
    /** Diese Werte entstehen neu. Anzahl und Summen werden separat nachgerechnet. */
    private static final Set<String> GENERATED = Set.of(HEADER_PATH + "CreDtTm", HEADER_PATH + "NbOfTxs",
            HEADER_PATH + "CtrlSum", "NbOfTxs", "CtrlSum");
    private static final Set<String> LEGACY_LOCAL = Set.of("CH01", "CH02", "CH03");

    private final ChangeLog log;
    private final List<String> errors = new ArrayList<>();

    private FullComparison(ChangeLog log) {
        this.log = log;
    }

    /**
     * @param source Quelldatei
     * @param output fertige Datei, aus ihren Bytes neu eingelesen
     * @param excludedIds bewusst ausgeschlossene Zahlungen
     * @param log gewollte Änderungen
     * @return gefundene Abweichungen, leer bedeutet in Ordnung
     */
    public static List<String> compare(Document source, Document output, Set<String> excludedIds, ChangeLog log) {
        FullComparison c = new FullComparison(log);
        c.run(source, output, excludedIds);
        if (c.errors.size() > MAX_ERRORS) {
            int more = c.errors.size() - MAX_ERRORS;
            List<String> shown = new ArrayList<>(c.errors.subList(0, MAX_ERRORS));
            shown.add("… und " + more + " weitere Abweichungen");
            return shown;
        }
        return c.errors;
    }

    private void run(Document source, Document output, Set<String> excludedIds) {
        Set<String> withoutGroups = Set.of("PmtInf");
        scope(ChangeLog.HEADER, ChangeLog.leaves(source.getDocumentElement(), "", withoutGroups),
                ChangeLog.leaves(output.getDocumentElement(), "", withoutGroups));

        Set<String> withoutPayments = Set.of("CdtTrfTxInf");
        Iterator<Element> outGroups = Xml.children(Views.root(output), "PmtInf").iterator();
        int index = 0;
        for (Element group : Xml.children(Views.root(source), "PmtInf")) {
            index++;
            Map<String, Element> kept = new java.util.LinkedHashMap<>();
            int number = 0;
            for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
                number++;
                String id = index + "." + number;
                if (!excludedIds.contains(id)) {
                    kept.put(id, tx);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            String scope = ChangeLog.group(index);
            if (!outGroups.hasNext()) {
                errors.add(scope + " fehlt in der Ausgabe.");
                return;
            }
            Element outGroup = outGroups.next();
            List<Element> outPayments = Xml.children(outGroup, "CdtTrfTxInf");
            if (outPayments.size() != kept.size()) {
                errors.add(scope + ": erwartet " + kept.size() + " Zahlungen, gefunden " + outPayments.size() + ".");
                continue;
            }
            scope(scope, ChangeLog.leaves(group, "", withoutPayments), ChangeLog.leaves(outGroup, "", withoutPayments));
            Iterator<Element> payments = outPayments.iterator();
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, Element> e : kept.entrySet()) {
                Element outTx = payments.next();
                scope(e.getKey(), ChangeLog.leaves(e.getValue(), ""), ChangeLog.leaves(outTx, ""));
                sum = sum.add(Views.amount(outTx));
            }
            String count = Xml.text(outGroup, "NbOfTxs");
            String control = Xml.text(outGroup, "CtrlSum");
            if (count != null && !count.equals(String.valueOf(outPayments.size()))) {
                errors.add(scope + ": NbOfTxs stimmt nicht mit der Anzahl Zahlungen überein.");
            }
            if (control != null && new BigDecimal(control).compareTo(sum) != 0) {
                errors.add(scope + ": CtrlSum stimmt nicht mit der Summe der Zahlungen überein.");
            }
        }
        if (outGroups.hasNext()) {
            errors.add("Die Ausgabe enthält mehr Gruppen als die Quelle.");
        }
    }

    /** Übersetzt einen Wert der Quelle in seine Form im Zielformat. Null bedeutet: fällt immer weg. */
    private static Leaf translate(Leaf leaf) {
        String path = leaf.path();
        String text = leaf.text();
        if (path.endsWith("PstlAdr/AdrTp") || (path.endsWith("PmtTpInf/LclInstrm/Prtry") && LEGACY_LOCAL.contains(text))) {
            return null;
        }
        if ("PmtMtd".equals(path) && "TRA".equals(text)) {
            text = "TRF";
        }
        path = path.replace("BICOrBEI", "AnyBIC");
        if (path.endsWith("FinInstnId/BIC")) {
            path += "FI";
        } else if ("ReqdExctnDt".equals(path)) {
            path += "/Dt";
        } else if (path.endsWith("RfrdDocAmt/DscntApldAmt") || path.endsWith("RfrdDocAmt/TaxAmt")) {
            path += "/Amt";
        }
        return new Leaf(path, text, leaf.attributes());
    }

    private static boolean generated(Leaf leaf) {
        return GENERATED.contains(leaf.path());
    }

    private void scope(String scope, List<Leaf> source, List<Leaf> output) {
        Map<Leaf, Integer> expected = new HashMap<>();
        for (Leaf s : source) {
            Leaf translated = translate(s);
            if (translated != null && !generated(translated)) {
                expected.merge(translated, 1, Integer::sum);
            }
        }
        Map<Leaf, Integer> actual = new HashMap<>();
        for (Leaf o : output) {
            if (!generated(o)) {
                actual.merge(o, 1, Integer::sum);
            }
        }
        Map<Leaf, Integer> changes = new HashMap<>(log.changes(scope));
        for (String address : log.rewritten(scope)) {
            checkAddress(scope, address, expected, actual, changes);
        }
        changes.forEach((leaf, n) -> expected.merge(leaf, n, Integer::sum));

        Set<Leaf> all = new java.util.LinkedHashSet<>(expected.keySet());
        all.addAll(actual.keySet());
        for (Leaf leaf : all) {
            int difference = actual.getOrDefault(leaf, 0) - expected.getOrDefault(leaf, 0);
            if (difference < 0) {
                errors.add(scope + ": " + leaf.path() + " «" + leaf.text() + "» fehlt in der Ausgabe.");
            } else if (difference > 0) {
                errors.add(scope + ": " + leaf.path() + " «" + leaf.text() + "» ist unerwartet in der Ausgabe.");
            }
        }
    }

    /**
     * Eine neu aufgeteilte Adresse hat andere Felder, aber keinen Inhalt verloren.
     *
     * <p>Die Werte verlassen danach den exakten Vergleich. Entfallen dürfen nur Länderkürzel vor der Postleitzahl,
     * Ländernamen und was das Protokoll ausdrücklich nennt.
     */
    private void checkAddress(String scope, String address, Map<Leaf, Integer> expected, Map<Leaf, Integer> actual,
            Map<Leaf, Integer> changes) {
        String prefix = address + "/";
        Set<String> allowed = new TreeSet<>();
        changes.keySet().removeIf(leaf -> {
            boolean inside = leaf.path().startsWith(prefix);
            if (inside && changes.get(leaf) < 0) {
                allowed.addAll(tokens(leaf.text()));
            }
            return inside;
        });
        StringBuilder written = new StringBuilder();
        actual.keySet().removeIf(leaf -> {
            boolean inside = leaf.path().startsWith(prefix);
            if (inside) {
                written.append(leaf.text().replaceAll("\\s", "").toLowerCase(Locale.ROOT)).append('|');
            }
            return inside;
        });
        expected.keySet().removeIf(leaf -> {
            boolean inside = leaf.path().startsWith(prefix);
            if (inside) {
                for (String token : tokens(leaf.text())) {
                    if (!allowed.contains(token) && !AddressParser.isCountryName(token)
                            && !written.toString().contains(token.toLowerCase(Locale.ROOT))) {
                        errors.add(scope + ": Adressteil «" + token + "» aus " + leaf.path() + " fehlt in der Ausgabe. "
                                + "Trage die Adresse in der Rückfrage von Hand ein.");
                    }
                }
            }
            return inside;
        });
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String part : text.split(",")) {
            for (String token : part.trim().replaceFirst("^[A-Za-z]{1,2}\\s*-\\s*(?=\\d)", "").split("\\s+")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }
}
