package ch.painmigrator.convert;

import ch.painmigrator.convert.AddressParser.Parsed;
import ch.painmigrator.convert.AddressParser.Street;
import ch.painmigrator.model.ChangeLog;
import ch.painmigrator.model.Decision;
import ch.painmigrator.model.Decision.Action;
import ch.painmigrator.model.Field;
import ch.painmigrator.model.Issue;
import ch.painmigrator.model.Issue.Severity;
import ch.painmigrator.model.PaymentView;
import ch.painmigrator.validate.Checksums;
import ch.painmigrator.validate.Schemas;
import ch.painmigrator.xml.Xml;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Wandelt pain.001.001.03.ch.02 nach pain.001.001.09.ch.03.
 *
 * <p>Die Umwandlung ist eine reine Funktion aus Quelldokument und Benutzerentscheidungen. Nach jeder
 * Entscheidung läuft sie vollständig neu; dabei entstehen Ausgabe, Vorschau und offene Punkte frisch.
 * Regelgrundlage sind die Swiss Implementation Guidelines Credit Transfer, Version 2.3 (SPS 2026).
 */
public final class Converter {
    public static final String NS_OLD = "http://www.six-interbank-clearing.com/de/pain.001.001.03.ch.02.xsd";
    public static final String NS_NEW = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";

    private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String NS_XMLNS = "http://www.w3.org/2000/xmlns/";
    private static final Pattern ALLOWED_TEXT = Pattern.compile("[\\x20-\\x7E\\xA0-\\xAC\\xAE-\\u017F\\u0218-\\u021B\\u20AC]*");
    private static final Pattern ALLOWED_ID = Pattern.compile("[A-Za-z0-9 '()+,\\-./:?]*");
    private static final Set<String> LEGACY_LOCAL = Set.of("CH01", "CH02", "CH03");
    private static final int MAX_MESSAGE = 140;
    /** Diese Beträge stehen in ch.02 direkt im Element, in ch.03 in einem Unterelement Amt. */
    private static final Set<String> DOCUMENT_AMOUNTS = Set.of("DscntApldAmt", "TaxAmt");
    private static final Set<String> ID_ELEMENTS = Set.of("MsgId", "PmtInfId", "InstrId", "EndToEndId");

    private static final List<String> ADDRESS_ORDER = List.of("Dept", "SubDept", "StrtNm", "BldgNb", "BldgNm", "Flr",
            "PstBx", "Room", "PstCd", "TwnNm", "TwnLctnNm", "DstrctNm", "CtrySubDvsn", "Ctry");
    private static final String[] AFTER_CDTR = {"CdtrAcct", "UltmtCdtr", "InstrForCdtrAgt", "InstrForDbtrAgt", "Purp",
        "RgltryRptg", "Tax", "RltdRmtInf", "RmtInf", "SplmtryData"};
    private static final String[] AFTER_CHRG_BR = {"ChqInstr", "UltmtDbtr", "IntrmyAgt1", "IntrmyAgt1Acct", "IntrmyAgt2",
        "IntrmyAgt2Acct", "IntrmyAgt3", "IntrmyAgt3Acct", "CdtrAgt", "CdtrAgtAcct", "Cdtr"};
    private static final String[] AFTER_CDTR_ACCT = {"UltmtCdtr", "InstrForCdtrAgt", "InstrForDbtrAgt", "Purp",
        "RgltryRptg", "Tax", "RltdRmtInf", "RmtInf", "SplmtryData"};

    /** Elemente unter CdtTrfTxInf, die eine Zahlungsart nach IG 2.3 nicht enthalten darf. */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "D", List.of("PmtTpInf/LclInstrm", "ChqInstr", "InstrForCdtrAgt", "CdtrAgt/FinInstnId/Othr"),
            "S", List.of("ChqInstr", "InstrForCdtrAgt", "InstrForDbtrAgt", "XchgRateInf", "CdtrAgt/FinInstnId/Othr",
                    "RmtInf/Strd/RfrdDocInf", "RmtInf/Strd/RfrdDocAmt", "RmtInf/Strd/Invcr", "RmtInf/Strd/Invcee",
                    "RmtInf/Strd/TaxRmt", "RmtInf/Strd/GrnshmtRmt", "RmtInf/Strd/AddtlRmtInf"),
            "X", List.of("ChqInstr", "CdtrAgt/FinInstnId/Othr"),
            "C", List.of("CdtrAcct", "CdtrAgt", "Cdtr/Id", "UltmtCdtr/Id", "InstrForCdtrAgt",
                    "RmtInf/Strd/AddtlRmtInf"));

    /** Ergebnis eines Laufs. */
    public record Result(Document output, List<Issue> issues, List<PaymentView> payments, String msgId,
            Map<String, BigDecimal> sums, Map<String, Set<String>> changes, ChangeLog log) {

        public boolean ready() {
            return activeCount() > 0 && issues.stream().allMatch(Issue::resolved);
        }

        public long activeCount() {
            return payments.stream().filter(p -> p.status() != PaymentView.Status.EXCLUDED).count();
        }

        public long excludedCount() {
            return payments.size() - activeCount();
        }

        public long openCount() {
            return issues.stream().filter(i -> !i.resolved()).count();
        }
    }

    /** Bricht die Bearbeitung einer Zahlung ab, die ausgeschlossen wurde. */
    private static final class Excluded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Excluded() {
            super(null, null, false, false);
        }
    }

    private final Document source;
    private final Map<String, Decision> decisions;
    private final LocalDateTime now;
    private final List<String> sourceErrors;
    private final Document out = Xml.newDocument();
    private final Map<String, Issue> issues = new LinkedHashMap<>();
    private final List<PaymentView> views = new ArrayList<>();
    private final Map<String, Set<String>> changes = new HashMap<>();
    private final ChangeLog log = new ChangeLog();
    private final Set<String> groupIssues = new HashSet<>();
    private String current;

    private Converter(Document source, Map<String, Decision> decisions, LocalDateTime now, List<String> sourceErrors) {
        this.source = source;
        this.decisions = decisions;
        this.now = now;
        this.sourceErrors = sourceErrors;
    }

    public static Result convert(Document source, Map<String, Decision> decisions, LocalDateTime now,
            List<String> sourceErrors) {
        return new Converter(source, decisions, now, sourceErrors).run();
    }

    /** Schlüssel für das Ausschliessen einer einzelnen Zahlung über die Vorschau. */
    public static String excludeKey(String paymentId) {
        return "EXCL|" + paymentId;
    }

    private Result run() {
        if (!sourceErrors.isEmpty()) {
            ask("SRC_XSD", Severity.CONFIRM, "Quelldatei verletzt das Schema ch.02",
                    "Die Datei entspricht nicht dem offiziellen Schema. Die Umwandlung ist trotzdem möglich, "
                    + "die Ausgabe wird aber streng geprüft.\n\n" + String.join("\n", firstLines(sourceErrors)),
                    List.of(), null, false);
        }
        Element root = copyRoot();
        Element initn = Xml.child(root, "CstmrCdtTrfInitn");
        removeAddressTypes(root);
        header(initn);

        int i = 0;
        BigDecimal sourceSum = BigDecimal.ZERO;
        int sourceCount = 0;
        for (Element sourceGroup : Xml.children(Views.root(source), "PmtInf")) {
            for (Element sourceTx : Xml.children(sourceGroup, "CdtTrfTxInf")) {
                sourceSum = sourceSum.add(Views.amount(sourceTx));
                sourceCount++;
            }
        }
        for (Element group : Xml.children(initn, "PmtInf")) {
            i++;
            group(i, group);
        }
        totals(initn, sourceCount, sourceSum);
        checkPaths(root);
        checkText(root);
        checkDuplicates(initn);

        finishStatus();
        Element grpHdr = Xml.child(initn, "GrpHdr");
        return new Result(out, List.copyOf(issues.values()), List.copyOf(views), Xml.text(grpHdr, "MsgId"),
                sumsByCurrency(), changes, log);
    }

    private static List<String> firstLines(List<String> lines) {
        return lines.size() <= 8 ? lines : lines.subList(0, 8);
    }

    // ---- Kopie in den neuen Namespace ------------------------------------------------------------------

    private Element copyRoot() {
        Element sourceRoot = source.getDocumentElement();
        Element root = out.createElementNS(NS_NEW, "Document");
        root.setAttributeNS(NS_XMLNS, "xmlns:xsi", NS_XSI);
        root.setAttributeNS(NS_XSI, "xsi:schemaLocation", NS_NEW + " " + Schemas.TARGET);
        for (Element c : Xml.children(sourceRoot)) {
            root.appendChild(copy(c));
        }
        out.appendChild(root);
        return root;
    }

    private Element copy(Element s) {
        String name = s.getLocalName();
        if (DOCUMENT_AMOUNTS.contains(name) && s.getParentNode() instanceof Element p
                && "RfrdDocAmt".equals(p.getLocalName())) {
            Element amount = el("Amt", s.getTextContent().trim());
            amount.setAttribute("Ccy", s.getAttribute("Ccy"));
            Element wrapper = el(name, null);
            wrapper.appendChild(amount);
            return wrapper;
        }
        if ("BIC".equals(name) && s.getParentNode() instanceof Element p && "FinInstnId".equals(p.getLocalName())) {
            name = "BICFI";
        } else if ("BICOrBEI".equals(name)) {
            name = "AnyBIC";
        }
        Element t = out.createElementNS(NS_NEW, name);
        NamedNodeMap attributes = s.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr a = (Attr) attributes.item(i);
            if (a.getNamespaceURI() == null) {
                t.setAttribute(a.getName(), a.getValue());
            }
        }
        List<Element> kids = Xml.children(s);
        if (kids.isEmpty()) {
            t.setTextContent(s.getTextContent().trim());
        } else {
            for (Element k : kids) {
                t.appendChild(copy(k));
            }
        }
        return t;
    }

    private Element el(String name, String text) {
        Element e = out.createElementNS(NS_NEW, name);
        if (text != null) {
            e.setTextContent(text);
        }
        return e;
    }

    private static void insertBefore(Element parent, Element child, String... followers) {
        Set<String> names = Set.of(followers);
        for (Element e : Xml.children(parent)) {
            if (names.contains(e.getLocalName())) {
                parent.insertBefore(child, e);
                return;
            }
        }
        parent.appendChild(child);
    }

    // ---- Änderungsprotokoll ---------------------------------------------------------------------------

    private record Place(String scope, String path) {
    }

    /** Bereich und Pfad eines Elements im Sinn des Änderungsprotokolls. */
    private static Place place(Element e) {
        String path = "";
        for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
            if (el.getUserData("id") instanceof String id) {
                return new Place(id, path);
            }
            if (el.getUserData("index") instanceof Integer index) {
                return new Place(ChangeLog.group(index), path);
            }
            if (el.getParentNode() instanceof Document) {
                return new Place(ChangeLog.HEADER, path);
            }
            path = path.isEmpty() ? el.getLocalName() : el.getLocalName() + "/" + path;
        }
        throw new IllegalStateException("Element ist nicht Teil der Ausgabe: " + e.getLocalName());
    }

    private void logRemoved(Element e) {
        Place p = place(e);
        ChangeLog.leaves(e, p.path()).forEach(leaf -> log.removed(p.scope(), leaf));
    }

    private void logAdded(Element e) {
        Place p = place(e);
        ChangeLog.leaves(e, p.path()).forEach(leaf -> log.added(p.scope(), leaf));
    }

    private void drop(Element e) {
        if (e != null && e.getParentNode() != null) {
            logRemoved(e);
            Xml.remove(e);
        }
    }

    private void dropAndPrune(Element e) {
        logRemoved(e);
        Xml.removeAndPrune(e);
    }

    private void setText(Element e, String value) {
        logRemoved(e);
        e.setTextContent(value);
        logAdded(e);
    }

    // ---- Rückfragen ------------------------------------------------------------------------------------

    private Decision ask(String key, Severity severity, String title, String detail, List<Field> fields, String omit,
            boolean excludable) {
        Issue issue = issues.get(key);
        if (issue == null) {
            issue = new Issue(key, severity, title, detail, fields, omit, excludable);
            issue.decision(decisions.get(key));
            issues.put(key, issue);
        }
        if (current == null) {
            groupIssues.add(key);
        } else {
            issue.addPayment(current);
        }
        Decision d = issue.decision();
        if (d != null && d.action() == Action.EXCLUDE && excludable && current != null) {
            throw new Excluded();
        }
        return d;
    }

    /** Verwirft eine Antwort, die das Problem nicht löst. Der Punkt bleibt dadurch offen. */
    private void reopen(String key) {
        issues.get(key).decision(null);
    }

    private void info(String key, String title, String detail) {
        ask(key, Severity.INFO, title, detail, List.of(), null, false);
    }

    private void changed(String what) {
        if (current != null) {
            changes.computeIfAbsent(current, k -> new HashSet<>()).add(what);
        }
    }

    /** Vermerkt eine Änderung an einem Element. Die PmtInfId gehört zu allen Zahlungen ihrer Gruppe. */
    private void changedAt(Element e, String what) {
        if (!"PmtInfId".equals(e.getLocalName())) {
            changed(what);
            return;
        }
        for (Element tx : Xml.children((Element) e.getParentNode(), "CdtTrfTxInf")) {
            changes.computeIfAbsent((String) tx.getUserData("id"), k -> new HashSet<>()).add(what);
        }
    }

    // ---- Kopf (A-Ebene) --------------------------------------------------------------------------------

    private void removeAddressTypes(Element root) {
        List<Element> found = new ArrayList<>();
        collect(root, "AdrTp", found);
        for (Element e : found) {
            Xml.remove(e);
        }
        if (!found.isEmpty()) {
            info("ADRTP", "Adresstyp entfernt", "Das Element AdrTp ist in pain.001.001.09.ch.03 nicht mehr erlaubt.");
        }
    }

    private static void collect(Element e, String name, List<Element> found) {
        for (Element c : Xml.children(e)) {
            if (name.equals(c.getLocalName())) {
                found.add(c);
            } else {
                collect(c, name, found);
            }
        }
    }

    private void header(Element initn) {
        Element grpHdr = Xml.child(initn, "GrpHdr");
        Xml.child(grpHdr, "CreDtTm").setTextContent(now.truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        Element party = Xml.child(grpHdr, "InitgPty");
        // CtctDtls ist optional. Die alten Angaben beschreiben die Software, die die alte Datei erzeugt hat,
        // und passen nicht zur neuen Struktur, deshalb entfallen sie.
        Element contact = Xml.child(party, "CtctDtls");
        if (contact != null) {
            drop(contact);
            info("SOFTWARE", "Software-Angaben entfernt", "Die Angaben in InitgPty/CtctDtls beschreiben die Software, "
                    + "die die alte Datei erzeugt hat. Sie sind freiwillig und entfallen in der neuen Datei.");
        }
        // SIX verlangt im Auftraggeber mindestens Name oder Identifikation (Fehlercode CH21).
        // Leere Elemente zählen nicht, sie fallen später weg.
        Element id = Xml.child(party, "Id");
        String givenName = Xml.text(party, "Nm");
        if ((givenName == null || givenName.isEmpty()) && (id == null || id.getTextContent().isBlank())) {
            Decision d = ask("INITGPTY", Severity.OPEN, "Auftraggeber ohne Name",
                    "Die Datei nennt den Auftraggeber (InitgPty) weder mit Namen noch mit Identifikation. SIX "
                    + "verlangt eines davon, sonst lehnt die Bank die Datei ab. Vorgeschlagen ist der Name des "
                    + "ersten Belastungskontos.",
                    List.of(Field.text("Nm", "Name", Xml.text(initn, "PmtInf/Dbtr/Nm"), true, 70)), null, false);
            String name = d == null ? null : d.value("Nm");
            if (name != null && textProblem("Nm", name) == null) {
                drop(Xml.child(party, "Nm"));
                Element nm = el("Nm", name);
                party.insertBefore(nm, party.getFirstChild());
                logAdded(nm);
            } else if (d != null) {
                reopen("INITGPTY");
            }
        }
    }

    // ---- Zahlungsgruppe (B-Ebene) ----------------------------------------------------------------------

    private void group(int index, Element group) {
        current = null;
        groupIssues.clear();
        group.setUserData("index", index, null);

        Element method = Xml.child(group, "PmtMtd");
        if ("TRA".equals(Xml.text(method))) {
            method.setTextContent("TRF");
            info("PMTMTD", "Zahlungsmethode TRA durch TRF ersetzt", "Laut SPS darf PmtMtd nur noch TRF oder CHK enthalten.");
        }
        executionDate(group);
        debtorAccount(group);
        address(Xml.child(group, "Dbtr"), "Debitor", false, false, true, false, null, "");
        Element ultimate = Xml.child(group, "UltmtDbtr");
        if (ultimate != null) {
            address(ultimate, "Abweichender Debitor", false, false, true, false, null, "");
        }
        Set<String> groupKeys = new HashSet<>(groupIssues);

        Classifier.Group facts = Classifier.Group.of(group);
        Element groupChargeBearer = Xml.child(group, "ChrgBr");
        List<String> ids = new ArrayList<>();
        List<Element> notSepa = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        int j = 0;
        BigDecimal sourceSum = BigDecimal.ZERO;
        Set<String> currencies = new HashSet<>();
        for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
            j++;
            String id = index + "." + j;
            sourceSum = sourceSum.add(Views.amount(tx));
            current = id;
            tx.setUserData("id", id, null);
            String oldType = Classifier.oldType(tx, facts);
            String[] newType = {"?"};
            try {
                if (decisions.containsKey(excludeKey(id))) {
                    throw new Excluded();
                }
                transaction(group, facts, groupChargeBearer, tx, oldType, newType);
                views.add(Views.of(id, group, tx, oldType, newType[0], PaymentView.Status.OK));
                ids.add(id);
                if (!"S".equals(newType[0])) {
                    notSepa.add(tx);
                }
                sum = sum.add(Views.amount(tx));
                currencies.add(Views.of(id, group, tx, null, null, null).currency());
                count++;
            } catch (Excluded e) {
                views.add(Views.of(id, group, tx, oldType, newType[0], PaymentView.Status.EXCLUDED));
                Xml.remove(tx);
            }
        }
        current = null;
        for (String key : groupKeys) {
            ids.forEach(issues.get(key)::addPayment);
        }
        keepChargeBearer(groupChargeBearer, notSepa);

        groupLevelCleanup(group, index, count, sum, j, sourceSum, currencies);
    }

    /** Entfällt die Gebührenregelung der Gruppe wegen SEPA, gilt sie für die übrigen Zahlungen weiter. */
    private void keepChargeBearer(Element bearer, List<Element> notSepa) {
        if (bearer == null || bearer.getParentNode() != null) {
            return;
        }
        for (Element tx : notSepa) {
            if (Xml.child(tx, "ChrgBr") == null) {
                Element inherited = el("ChrgBr", Xml.text(bearer));
                insertBefore(tx, inherited, AFTER_CHRG_BR);
                logAdded(inherited);
            }
        }
    }

    private void groupLevelCleanup(Element group, int index, int count, BigDecimal sum, int sourceCount,
            BigDecimal sourceSum, Set<String> currencies) {
        Element local = Xml.find(group, "PmtTpInf/LclInstrm/Prtry");
        if (local != null && LEGACY_LOCAL.contains(Xml.text(local))) {
            Xml.removeAndPrune(Xml.find(group, "PmtTpInf/LclInstrm"));
        }
        if (count == 0) {
            Xml.remove(group);
            return;
        }
        Element nb = Xml.child(group, "NbOfTxs");
        Element ctrl = Xml.child(group, "CtrlSum");
        String id = Xml.text(group, "PmtInfId");
        boolean nbOff = nb != null && !nb.getTextContent().trim().equals(String.valueOf(sourceCount));
        boolean sumOff = ctrl != null && new BigDecimal(ctrl.getTextContent().trim()).compareTo(sourceSum) != 0;
        if (nbOff || sumOff) {
            ask("SUM_B|" + index, Severity.CONFIRM, "Summe der Gruppe " + id + " stimmt nicht",
                    "In der Quelldatei stehen Anzahl " + Xml.text(nb) + " und Summe " + Xml.text(ctrl)
                    + ", nachgerechnet ergibt sich Anzahl " + sourceCount + " und Summe " + sourceSum.toPlainString()
                    + ".\nBestätige nur, wenn die einzelnen Zahlungen korrekt sind.", List.of(), null, false);
        }
        if (nb != null) {
            nb.setTextContent(String.valueOf(count));
        }
        if (ctrl != null) {
            ctrl.setTextContent(sum.toPlainString());
        }
        if (currencies.size() > 1 && !"false".equals(Xml.text(group, "BtchBookg"))) {
            info("MIXED|" + index, "Mehrere Währungen in Gruppe " + id,
                    "Laut SPS ist pro Währung eine eigene Gruppe vorgesehen. Die Bank kann die Datei ablehnen.");
        }
    }

    private void executionDate(Element group) {
        Element date = Xml.child(group, "ReqdExctnDt");
        String old = Xml.text(date);
        date.setTextContent("");
        Element dt = el("Dt", old);
        date.appendChild(dt);
        LocalDate today = now.toLocalDate();
        if (usableDate(old, today)) {
            return;
        }
        String key = "DATE|" + old;
        Decision d = ask(key, Severity.OPEN, "Ausführungsdatum " + old + " liegt in der Vergangenheit",
                "Lege ein neues Ausführungsdatum fest.",
                List.of(Field.date("date", "Neues Datum", today.toString(), today)), null, false);
        if (d == null) {
            return;
        }
        String value = d.value("date");
        if (usableDate(value, today)) {
            setText(dt, value);
            markGroupDateChanged(group);
        } else {
            reopen(key);
        }
    }

    private static boolean usableDate(String value, LocalDate today) {
        try {
            // xs:date erlaubt eine Zeitzone hinter dem Datum, zum Beispiel 2026-12-01+01:00.
            return value != null && !LocalDate.parse(value.substring(0, Math.min(10, value.length()))).isBefore(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void markGroupDateChanged(Element group) {
        group.setUserData("dateChanged", Boolean.TRUE, null);
    }

    private void debtorAccount(Element group) {
        Element ibanEl = Xml.find(group, "DbtrAcct/Id/IBAN");
        String iban = Xml.text(ibanEl);
        if (iban == null || (Checksums.isValidIban(iban) && !Checksums.isQrIban(iban))) {
            return;
        }
        String key = "DBTRACCT|" + iban;
        Decision d = ask(key, Severity.OPEN, "Belastungskonto " + iban + " ist ungültig",
                "Die IBAN des Belastungskontos ist fehlerhaft oder eine QR-IBAN. Eine QR-IBAN ist hier nicht erlaubt.",
                List.of(Field.of("iban", "IBAN Belastungskonto", iban, true, 34, Field.Type.DEBTOR_IBAN)), null, false);
        if (d == null) {
            return;
        }
        String fixed = d.value("iban");
        if (fixed != null && Checksums.isValidIban(fixed) && !Checksums.isQrIban(fixed)) {
            setText(ibanEl, Checksums.normalize(fixed));
        } else {
            reopen(key);
        }
    }

    // ---- Zahlung (C-Ebene) -----------------------------------------------------------------------------

    private void transaction(Element group, Classifier.Group facts, Element groupChargeBearer, Element tx,
            String oldType, String[] newType) {
        if (Boolean.TRUE.equals(group.getUserData("dateChanged"))) {
            changed("date");
        }
        String sourceAccount = sourceAccount(tx);
        boolean accountKnown = account(tx, oldType, sourceAccount);

        Element local = Xml.find(tx, "PmtTpInf/LclInstrm/Prtry");
        if (local != null && LEGACY_LOCAL.contains(Xml.text(local))) {
            Xml.removeAndPrune(Xml.find(tx, "PmtTpInf/LclInstrm"));
        }

        if (accountKnown) {
            newType[0] = Classifier.newType(tx, facts);
            sepaCurrency(tx, newType[0]);
            agent(tx, newType[0]);
            reference(tx, newType[0]);
            forbidden(tx, newType[0]);
            chargeBearer(tx, groupChargeBearer, newType[0]);
            qrUnstructured(tx);
        }
        creditor(tx, newType[0], sourceAccount);
        Element ultimateDebtor = Xml.child(tx, "UltmtDbtr");
        if (ultimateDebtor != null) {
            address(ultimateDebtor, "Abweichender Debitor", false, false, true, false, null, "");
        }
        Element ultimateCreditor = Xml.child(tx, "UltmtCdtr");
        if (ultimateCreditor != null) {
            address(ultimateCreditor, "Abweichender Empfänger", false, false, true, false, null, "");
        }
        Element agentInstitution = Xml.find(tx, "CdtrAgt/FinInstnId");
        if (agentInstitution != null && Xml.child(agentInstitution, "Nm") != null) {
            address(agentInstitution, "Bank des Empfängers", true, false, false, false, null, "");
        }
    }

    private static String sourceAccount(Element tx) {
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        return iban != null ? iban : Xml.text(tx, "CdtrAcct/Id/Othr/Id");
    }

    private void setIban(Element tx, String iban) {
        Element acct = Xml.child(tx, "CdtrAcct");
        if (acct == null) {
            acct = el("CdtrAcct", null);
            insertBefore(tx, acct, AFTER_CDTR_ACCT);
        }
        Element id = Xml.child(acct, "Id");
        if (id == null) {
            id = el("Id", null);
            acct.insertBefore(id, acct.getFirstChild());
        }
        for (Element e : Xml.children(id)) {
            drop(e);
        }
        Element ibanEl = el("IBAN", Checksums.normalize(iban));
        id.appendChild(ibanEl);
        logAdded(ibanEl);
        changed("account");
    }

    /** Liefert true, wenn das Empfängerkonto nun feststeht. */
    private boolean account(Element tx, String oldType, String account) {
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        // Ohne Konto gibt es nichts, das zwei Zahlungen verbindet: jede bekommt ihre eigene Rückfrage.
        String shown = account == null ? "(ohne Konto, Empfänger " + Xml.text(tx, "Cdtr/Nm") + ")" : account;
        // Dieselbe Nummer bei einem anderen Empfänger kann ein anderes Konto sein, die IBAN darf nicht mitwandern.
        String key = account == null ? "ohne Konto|" + current : account + "|" + Xml.text(tx, "Cdtr/Nm");
        switch (oldType) {
            case "8":
                return true;
            case "1": {
                Decision d = ask("ACCT1|" + key, Severity.OPEN, "ESR-Zahlung an " + shown,
                        "Der ESR wurde am 30.09.2022 eingestellt. Die ESR-Teilnehmernummer lässt sich nicht in eine "
                        + "QR-IBAN umrechnen. Trage die QR-IBAN aus der aktuellen QR-Rechnung ein.\n"
                        + "Die bisherige Referenz wird als QR-Referenz übernommen. Prüfe sie gegen die Rechnung.",
                        List.of(Field.of("iban", "QR-IBAN", null, true, 34, Field.Type.QR_IBAN)), null, true);
                return applyIban(tx, d);
            }
            case "2.1":
            case "2.2":
                if (iban == null) {
                    Decision d = ask("ACCT2|" + key, Severity.OPEN, "ES-Zahlung an Konto " + shown,
                            "Der ES wurde am 30.09.2022 eingestellt. Trage die IBAN des Empfängers ein.",
                            List.of(Field.of("iban", "IBAN", null, true, 34, Field.Type.IBAN)), null, true);
                    return applyIban(tx, d);
                }
                ask("LEGACY|" + iban, Severity.CONFIRM, "ES-Zahlung an " + iban + " wird normale Zahlung",
                        "Die Zahlung war ein zweistufiger ES (CH03). Sie wird ohne diese Kennzeichnung übernommen.",
                        List.of(), null, true);
                break;
            default:
                break;
        }
        if (iban != null) {
            if (Checksums.isValidIban(iban)) {
                return true;
            }
            Decision d = ask("IBAN|" + iban, Severity.OPEN, "IBAN " + iban + " ist ungültig",
                    "Format oder Prüfziffer stimmen nicht. Korrigiere die IBAN.",
                    List.of(Field.of("iban", "IBAN", iban, true, 34, Field.Type.IBAN)), null, true);
            return applyIban(tx, d);
        }
        boolean agentGiven = Xml.find(tx, "CdtrAgt/FinInstnId/BICFI") != null
                || Xml.find(tx, "CdtrAgt/FinInstnId/ClrSysMmbId") != null;
        if (agentGiven && account != null) {
            return true;
        }
        Decision d = ask("ACCT|" + key, Severity.OPEN, "Konto " + shown + " ohne IBAN",
                "Ein Konto ohne IBAN ist nur zusammen mit einem IID oder BIC möglich. Trage die IBAN ein.",
                List.of(Field.of("iban", "IBAN", null, true, 34, Field.Type.IBAN)), null, true);
        return applyIban(tx, d);
    }

    private boolean applyIban(Element tx, Decision d) {
        if (d == null || d.value("iban") == null) {
            return false;
        }
        setIban(tx, d.value("iban"));
        return true;
    }

    private void sepaCurrency(Element tx, String type) {
        String currency = Classifier.currency(tx);
        if ("S".equals(type) && !"EUR".equals(currency)) {
            ask("SEPA_CCY|" + currency, Severity.CONFIRM, "SEPA-Zahlung in " + currency,
                    "SEPA-Zahlungen sind nur in EUR möglich. Die Zahlung wird unverändert übernommen, die Bank wird sie "
                    + "voraussichtlich ablehnen. Korrigiere sie im Ursprungssystem oder schliesse sie aus.",
                    List.of(), null, true);
        }
    }

    private void agent(Element tx, String type) {
        Element fi = Xml.find(tx, "CdtrAgt/FinInstnId");
        if (fi == null) {
            return;
        }
        boolean bic = Xml.child(fi, "BICFI") != null;
        if ("D".equals(type) || "S".equals(type) || bic) {
            boolean removed = false;
            for (String name : List.of("Nm", "PstlAdr")) {
                Element e = Xml.child(fi, name);
                if (e != null) {
                    drop(e);
                    removed = true;
                }
            }
            if (removed) {
                info("AGENT_ADR", "Name und Adresse der Empfängerbank entfernt", "Bei den Zahlungsarten D und S "
                        + "sowie zusammen mit einem BIC sind diese Angaben nicht erlaubt.");
            }
        }
        Element clearing = Xml.child(fi, "ClrSysMmbId");
        if (clearing != null && "S".equals(type)) {
            drop(clearing);
            info("AGENT_IID", "IID der Empfängerbank entfernt", "Bei SEPA-Zahlungen ist der IID nicht erlaubt.");
        } else if (clearing != null && bic) {
            Decision d = ask("AGENT_BOTH|" + Xml.text(fi, "BICFI") + "|" + Xml.text(clearing, "MmbId"), Severity.CONFIRM,
                    "BIC und IID gleichzeitig angegeben", "BICFI und ClrSysMmbId dürfen nicht zusammen vorkommen. "
                    + "Der BIC " + Xml.text(fi, "BICFI") + " bleibt, der IID " + Xml.text(clearing, "MmbId")
                    + " wird entfernt.", List.of(), null, true);
            if (d != null) {
                drop(clearing);
            }
        }
        if (Xml.children(fi).isEmpty()) {
            Xml.remove(Xml.child(tx, "CdtrAgt"));
        }
    }

    private void reference(Element tx, String type) {
        Element info = Xml.find(tx, "RmtInf/Strd/CdtrRefInf");
        Element refEl = Xml.child(info, "Ref");
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        boolean qr = iban != null && Checksums.isQrIban(iban);
        if (refEl == null) {
            if (qr) {
                Decision d = ask("REFMISSING|" + iban, Severity.OPEN, "QR-Referenz fehlt für " + iban,
                        "Zu einer QR-IBAN gehört zwingend eine QR-Referenz.",
                        List.of(Field.of("ref", "QR-Referenz", null, true, 35, Field.Type.QR_REFERENCE)), null, true);
                if (d != null && d.value("ref") != null) {
                    setReference(tx, d.value("ref"));
                    changed("reference");
                    reference(tx, type);
                }
            }
            return;
        }
        String ref = Xml.text(refEl);
        String code = Xml.text(info, "Tp/CdOrPrtry/Cd");
        String proprietary = Xml.text(info, "Tp/CdOrPrtry/Prtry");
        String issuer = Xml.text(info, "Tp/Issr");
        String problem = null;

        if (code == null && proprietary == null) {
            if (qr && Checksums.isValidQrReference(ref)) {
                setReferenceType(info, null, "QRR");
            } else if (!qr && Checksums.isValidCreditorReference(ref)) {
                setReferenceType(info, "SCOR", null);
            } else {
                problem = "Die Referenz hat keinen Typ, und er lässt sich nicht bestimmen.";
            }
        } else if ("QRR".equals(proprietary)) {
            if (!qr) {
                problem = "Eine QR-Referenz ist nur zusammen mit einer QR-IBAN erlaubt.";
            } else if (!Checksums.isValidQrReference(ref)) {
                problem = "Die Prüfziffer der QR-Referenz stimmt nicht.";
            }
        } else if ("SCOR".equals(code)) {
            if (qr) {
                problem = "Zu einer QR-IBAN gehört eine QR-Referenz, keine ISO-Referenz.";
            } else if (("D".equals(type) || "ISO".equals(issuer)) && !Checksums.isValidCreditorReference(ref)) {
                problem = "Die ISO-Referenz ist formal ungültig.";
            }
        } else if ("D".equals(type) || "S".equals(type)) {
            problem = "Dieser Referenztyp ist bei Zahlungsart " + type + " nicht erlaubt.";
        }
        if (problem == null) {
            return;
        }
        String existing = Xml.text(tx, "RmtInf/Ustrd");
        String message = existing == null ? ref : existing + " " + ref;
        boolean asMessage = !qr && message.length() <= MAX_MESSAGE;
        String key = "REF|" + ref + "|" + qr;
        Decision d = ask(key, Severity.OPEN, "Referenz " + ref + " ist nicht verwendbar", problem,
                List.of(Field.of("ref", qr ? "QR-Referenz" : "ISO-Referenz", ref, true, 35,
                        qr ? Field.Type.QR_REFERENCE : Field.Type.CREDITOR_REFERENCE)),
                asMessage ? "Als Mitteilung übernehmen" : null, true);
        if (d == null) {
            return;
        }
        if (d.action() == Action.OMIT) {
            if (asMessage) {
                moveReferenceToMessage(tx, info, message);
            } else {
                reopen(key);
            }
            return;
        }
        // Nur eine gültige Referenz löst den Punkt. Das beendet auch die Rekursion.
        String fixed = d.value("ref");
        if (fixed != null && (qr ? Checksums.isValidQrReference(fixed) : Checksums.isValidCreditorReference(fixed))) {
            setReference(tx, fixed);
            changed("reference");
            reference(tx, type);
        } else {
            reopen(key);
        }
    }

    private void setReference(Element tx, String ref) {
        Element rmt = Xml.child(tx, "RmtInf");
        if (rmt == null) {
            rmt = el("RmtInf", null);
            insertBefore(tx, rmt, "SplmtryData");
        }
        Element strd = Xml.child(rmt, "Strd");
        if (strd == null) {
            strd = el("Strd", null);
            rmt.appendChild(strd);
        }
        Element info = Xml.child(strd, "CdtrRefInf");
        if (info == null) {
            info = el("CdtrRefInf", null);
            insertBefore(strd, info, "Invcr", "Invcee", "TaxRmt", "GrnshmtRmt", "AddtlRmtInf");
        }
        for (Element e : Xml.children(info)) {
            drop(e);
        }
        Element refEl = el("Ref", Checksums.normalize(ref));
        info.appendChild(refEl);
        logAdded(refEl);
    }

    private void setReferenceType(Element info, String code, String proprietary) {
        drop(Xml.child(info, "Tp"));
        Element tp = el("Tp", null);
        Element choice = el("CdOrPrtry", null);
        choice.appendChild(code != null ? el("Cd", code) : el("Prtry", proprietary));
        tp.appendChild(choice);
        info.insertBefore(tp, info.getFirstChild());
        logAdded(tp);
    }

    private void moveReferenceToMessage(Element tx, Element info, String message) {
        Element rmt = Xml.child(tx, "RmtInf");
        dropAndPrune(info);
        if (rmt.getParentNode() == null) {
            rmt = el("RmtInf", null);
            insertBefore(tx, rmt, "SplmtryData");
        }
        Element text = Xml.child(rmt, "Ustrd");
        if (text == null) {
            text = el("Ustrd", message);
            rmt.insertBefore(text, rmt.getFirstChild());
            logAdded(text);
        } else {
            setText(text, message);
        }
        changed("reference");
    }

    private void forbidden(Element tx, String type) {
        for (String path : FORBIDDEN.getOrDefault(type, List.of())) {
            for (Element e : Xml.findAll(tx, path)) {
                String value = e.getTextContent().trim();
                Decision d = ask("DROP|" + type + "|" + path + "|" + value, Severity.CONFIRM,
                        path + " bei Zahlungsart " + type + " nicht erlaubt",
                        "Das Element wird entfernt. Bisheriger Inhalt: " + shorten(value), List.of(), null, true);
                if (d != null) {
                    dropAndPrune(e);
                    if ("CdtrAcct".equals(path)) {
                        changed("account");
                    }
                }
            }
        }
    }

    private void chargeBearer(Element tx, Element groupBearer, String type) {
        if (!"S".equals(type)) {
            return;
        }
        for (Element bearer : new Element[] {Xml.child(tx, "ChrgBr"), groupBearer}) {
            String value = Xml.text(bearer);
            if ("DEBT".equals(value) || "CRED".equals(value)) {
                Decision d = ask("CHRGBR|" + value, Severity.CONFIRM, "Gebührenregelung " + value + " bei SEPA nicht erlaubt",
                        "Bei SEPA sind nur SLEV und SHAR erlaubt. Das Element wird entfernt.", List.of(), null, true);
                if (d != null) {
                    drop(bearer);
                }
            }
        }
    }

    private void qrUnstructured(Element tx) {
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        Element text = Xml.find(tx, "RmtInf/Ustrd");
        if (iban != null && Checksums.isQrIban(iban) && text != null) {
            String value = Xml.text(text);
            Decision d = ask("QRTEXT|" + value, Severity.CONFIRM, "Mitteilung neben QR-IBAN nicht erlaubt",
                    "Zu einer QR-IBAN darf kein Ustrd geliefert werden. Der Text wird entfernt: " + shorten(value),
                    List.of(), null, true);
            if (d != null) {
                drop(text);
            }
        }
    }

    private void creditor(Element tx, String type, String sourceAccount) {
        Element cdtr = Xml.child(tx, "Cdtr");
        if (cdtr == null) {
            cdtr = el("Cdtr", null);
            insertBefore(tx, cdtr, AFTER_CDTR);
        }
        boolean cheque = "C".equals(type);
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        String fallback = iban != null && iban.length() >= 2 ? iban.substring(0, 2) : null;
        // Gleicher Name auf anderem Konto kann eine andere Person sein. Ohne Konto gibt es nichts Verbindendes.
        String extra = sourceAccount != null ? sourceAccount : current;
        address(cdtr, "Empfänger", true, true, !cheque, cheque, fallback, extra);
    }

    // ---- Adressen --------------------------------------------------------------------------------------

    /**
     * Bringt die Adresse einer Partei in die strukturierte Form.
     *
     * @param required die Adresse muss vorhanden sein, auch wenn die Quelle keine hat
     * @param needName die Partei braucht zwingend einen Namen
     * @param mayOmit der Benutzer darf die Adresse weglassen
     * @param needPostCode die Postleitzahl ist Pflicht
     */
    private void address(Element party, String role, boolean required, boolean needName, boolean mayOmit,
            boolean needPostCode, String fallbackCountry, String keyExtra) {
        Element nameEl = Xml.child(party, "Nm");
        String name = Xml.text(nameEl);
        Element adr = Xml.child(party, "PstlAdr");
        if (adr == null && !required && !(needName && name == null)) {
            return;
        }
        Map<String, String> known = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        if (adr != null) {
            for (Element e : Xml.children(adr)) {
                if ("AdrLine".equals(e.getLocalName())) {
                    lines.add(Xml.text(e));
                } else {
                    known.put(e.getLocalName(), Xml.text(e));
                }
            }
        }
        String street = known.get("StrtNm");
        Street split = known.containsKey("BldgNb") ? null : AddressParser.splitStreet(street);
        boolean complete = lines.isEmpty() && known.containsKey("TwnNm") && known.containsKey("Ctry");
        if (complete && split == null && name != null && !(needPostCode && !known.containsKey("PstCd"))) {
            writeAddress(party, known);
            return;
        }

        Parsed parsed = AddressParser.parse(lines, known.get("Ctry"), fallbackCountry);
        Map<String, String> proposal = new LinkedHashMap<>(known);
        if (split != null) {
            proposal.put("StrtNm", split.name());
            proposal.put("BldgNb", split.number());
        }
        proposal.putIfAbsent("StrtNm", parsed.street());
        proposal.putIfAbsent("BldgNb", parsed.building());
        proposal.putIfAbsent("PstBx", parsed.postBox());
        proposal.putIfAbsent("PstCd", parsed.postCode());
        proposal.putIfAbsent("TwnNm", parsed.town());
        proposal.putIfAbsent("Ctry", parsed.country());
        List<String> leftovers = new ArrayList<>();
        addLeftover(leftovers, proposal, "StrtNm", parsed.street());
        addLeftover(leftovers, proposal, "BldgNb", parsed.building());
        addLeftover(leftovers, proposal, "PstBx", parsed.postBox());
        addLeftover(leftovers, proposal, "PstCd", parsed.postCode());
        addLeftover(leftovers, proposal, "TwnNm", parsed.town());
        if (parsed.extra() != null) {
            leftovers.add(parsed.extra());
        }
        if (!leftovers.isEmpty()) {
            String existing = proposal.get("Dept");
            proposal.put("Dept", (existing == null ? "" : existing + ", ") + String.join(", ", leftovers));
        }
        proposal.values().removeIf(v -> v == null || v.isBlank());

        boolean sure = leftovers.isEmpty() && name != null && proposal.containsKey("TwnNm") && proposal.containsKey("Ctry")
                && (lines.isEmpty() ? known.containsKey("Ctry") : parsed.confident())
                && !(needPostCode && !proposal.containsKey("PstCd"));
        String raw = String.join(" / ", lines);
        String structured = String.join(" ", known.values());
        String was = (raw + " " + structured).trim();
        List<Field> fields = new ArrayList<>();
        if (name == null && needName) {
            fields.add(Field.text("Nm", "Name", null, true, 70));
        }
        fields.add(Field.text("StrtNm", "Strasse", proposal.get("StrtNm"), false, 70));
        fields.add(Field.text("BldgNb", "Hausnummer", proposal.get("BldgNb"), false, 16));
        fields.add(Field.text("PstBx", "Postfach", proposal.get("PstBx"), false, 16));
        fields.add(Field.text("PstCd", "PLZ", proposal.get("PstCd"), needPostCode, 16));
        fields.add(Field.text("TwnNm", "Ort", proposal.get("TwnNm"), true, 35));
        fields.add(Field.of("Ctry", "Land", proposal.get("Ctry"), true, 2, Field.Type.COUNTRY));
        fields.add(Field.text("Dept", "Zusatz", proposal.get("Dept"), false, 70));

        sure &= fields.stream().allMatch(f -> f.value().length() <= f.maxLength());

        String who = name != null ? name : "Unbekannt";
        Decision d = ask("ADDR|" + role + "|" + name + "|" + was + "|" + keyExtra,
                sure ? Severity.REVIEW : Severity.OPEN, role + ": " + who,
                was.isEmpty() ? "Die Quelldatei enthält keine Adresse." : "Bisher: " + was, fields,
                mayOmit ? "Ohne Adresse weiter" : null, current != null);
        if (d == null) {
            writeAddress(party, proposal);
            return;
        }
        if (d.action() == Action.OMIT) {
            drop(adr);
            if (name == null) {
                setName(party, d);
            }
            return;
        }
        Map<String, String> finalValues = new LinkedHashMap<>(proposal);
        for (Field f : fields) {
            if (d.values().containsKey(f.name())) {
                String v = d.value(f.name());
                if (v == null) {
                    finalValues.remove(f.name());
                } else {
                    finalValues.put(f.name(), v);
                }
            }
        }
        setName(party, d);
        finalValues.remove("Nm");
        rewriteAddress(party, finalValues, finalValues.equals(proposal));
    }

    /** Ein unveränderter Vorschlag teilt nur neu auf. Geänderte Werte sind Benutzereingaben. */
    private void rewriteAddress(Element party, Map<String, String> values, boolean asProposed) {
        Element old = Xml.child(party, "PstlAdr");
        if (asProposed) {
            Place p = place(party);
            log.rewritten(p.scope(), p.path().isEmpty() ? "PstlAdr" : p.path() + "/PstlAdr");
            writeAddress(party, values);
            return;
        }
        if (old != null) {
            logRemoved(old);
        }
        writeAddress(party, values);
        logAdded(Xml.child(party, "PstlAdr"));
    }

    /** Hält Werte aus AdrLine fest, die wegen eines vorhandenen strukturierten Felds keinen Platz fanden. */
    private static void addLeftover(List<String> leftovers, Map<String, String> proposal, String field, String value) {
        if (value != null && !value.equals(proposal.get(field))) {
            leftovers.add(value);
        }
    }

    private void setName(Element party, Decision d) {
        String value = d.value("Nm");
        if (value != null && Xml.child(party, "Nm") == null) {
            Element entered = el("Nm", value);
            party.insertBefore(entered, party.getFirstChild());
            logAdded(entered);
            changed("creditor");
        }
    }

    private void writeAddress(Element party, Map<String, String> values) {
        Element old = Xml.child(party, "PstlAdr");
        Element adr = el("PstlAdr", null);
        for (String name : ADDRESS_ORDER) {
            String v = values.get(name);
            if (v != null && !v.isBlank()) {
                adr.appendChild(el(name, v));
            }
        }
        if (old != null) {
            party.replaceChild(adr, old);
        } else {
            Element nm = Xml.child(party, "Nm");
            party.insertBefore(adr, nm == null ? party.getFirstChild() : nm.getNextSibling());
        }
    }

    // ---- Abschliessende Prüfungen ----------------------------------------------------------------------

    private void totals(Element initn, int sourceCount, BigDecimal sourceSum) {
        Element grpHdr = Xml.child(initn, "GrpHdr");
        Element nb = Xml.child(grpHdr, "NbOfTxs");
        Element ctrl = Xml.child(grpHdr, "CtrlSum");
        boolean nbOff = !Xml.text(nb).equals(String.valueOf(sourceCount));
        boolean sumOff = ctrl != null && new BigDecimal(Xml.text(ctrl)).compareTo(sourceSum) != 0;
        if (nbOff || sumOff) {
            ask("SUM_A", Severity.CONFIRM, "Kontrollsumme der Datei stimmt nicht", "Im Kopf stehen Anzahl " + Xml.text(nb)
                    + " und Summe " + Xml.text(ctrl) + ", nachgerechnet ergibt sich Anzahl " + sourceCount + " und Summe "
                    + sourceSum.toPlainString() + ".\nBestätige nur, wenn die einzelnen Zahlungen korrekt sind.",
                    List.of(), null, false);
        }
        int count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Element group : Xml.children(initn, "PmtInf")) {
            for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
                count++;
                sum = sum.add(Views.amount(tx));
            }
        }
        nb.setTextContent(String.valueOf(count));
        if (ctrl == null) {
            ctrl = el("CtrlSum", null);
            grpHdr.insertBefore(ctrl, nb.getNextSibling());
        }
        ctrl.setTextContent(sum.toPlainString());
    }

    private void forPayment(Element e) {
        current = null;
        for (Node n = e; n != null; n = n.getParentNode()) {
            if (n.getUserData("id") instanceof String id) {
                current = id;
                return;
            }
        }
    }

    private static String shorten(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + " …";
    }

    /** Alles, was das Zielschema nicht kennt, darf nur nach Bestätigung verschwinden. */
    private void checkPaths(Element root) {
        Set<String> known = Schemas.targetPaths();
        List<Element> unknown = new ArrayList<>();
        findUnknown(root, "/" + root.getLocalName(), known, unknown);
        for (Element e : unknown) {
            forPayment(e);
            Element target = e;
            String path = Xml.path(e).replace("/Document/CstmrCdtTrfInitn/", "");
            if (path.endsWith("/ClrSysId/Prtry")) {
                target = (Element) e.getParentNode().getParentNode();
            }
            String value = target.getTextContent().trim();
            Decision d = ask("PATH|" + path + "|" + value, Severity.CONFIRM, "Element ohne Entsprechung: " + path,
                    "Das Zielformat kennt dieses Element nicht, es würde beim Speichern entfernt.\nInhalt: " + shorten(value),
                    List.of(), null, false);
            if (d != null) {
                dropAndPrune(target);
            }
        }
        current = null;
    }

    private static void findUnknown(Element e, String path, Set<String> known, List<Element> found) {
        if (!known.contains(path)) {
            found.add(e);
            return;
        }
        for (Element c : Xml.children(e)) {
            findUnknown(c, path + "/" + c.getLocalName(), known, found);
        }
    }

    private void checkText(Element e) {
        List<Element> kids = Xml.children(e);
        for (Element k : kids) {
            checkText(k);
        }
        if (!kids.isEmpty()) {
            return;
        }
        String value = e.getTextContent();
        String name = e.getLocalName();
        String problem = textProblem(name, value);
        if (problem == null) {
            return;
        }
        forPayment(e);
        String path = Xml.path(e).replace("/Document/CstmrCdtTrfInitn/", "");
        String key = "TXT|" + path + "|" + value;
        Decision d = ask(key, Severity.OPEN, "Ungültiger Inhalt in " + name, problem + "\nPfad: " + path,
                List.of(Field.text("value", name, value, false, 2048)), null, false);
        if (d != null) {
            String fixed = d.value("value");
            if (fixed != null && textProblem(name, fixed) != null) {
                reopen(key);
            } else {
                changedAt(e, comparedField(name, path));
                if (fixed == null) {
                    dropAndPrune(e);
                } else {
                    setText(e, fixed);
                }
            }
        }
        current = null;
    }

    private static String textProblem(String name, String value) {
        if (value.isBlank()) {
            return "Leere Elemente sind nicht erlaubt.";
        }
        if (!ALLOWED_TEXT.matcher(value).matches()) {
            return "Der Text enthält Zeichen, die laut SPS nicht erlaubt sind.";
        }
        if (ID_ELEMENTS.contains(name) && !validId(value)) {
            return "In Referenzfeldern sind nur A-Z, a-z, 0-9, Leerzeichen und ' ( ) + , - . / : ? erlaubt. "
                    + "Sie dürfen nicht mit Leerzeichen oder / beginnen, nicht mit / enden und kein // enthalten.";
        }
        return null;
    }

    /** Ordnet ein korrigiertes Element dem Feld zu, das der Vergleich alt gegen neu prüft. */
    private static String comparedField(String name, String path) {
        if (ID_ELEMENTS.contains(name)) {
            return "id";
        }
        if ("Ref".equals(name) || "Ustrd".equals(name)) {
            return "reference";
        }
        if (path.endsWith("/CdtrAcct/Id/Othr/Id")) {
            return "account";
        }
        return path.endsWith("/Cdtr/Nm") ? "creditor" : "other";
    }

    private static boolean validId(String v) {
        return ALLOWED_ID.matcher(v).matches() && !v.startsWith(" ") && !v.startsWith("/") && !v.endsWith("/")
                && !v.contains("//");
    }

    private void checkDuplicates(Element initn) {
        Set<String> groups = new HashSet<>();
        for (Element group : Xml.children(initn, "PmtInf")) {
            Element id = Xml.child(group, "PmtInfId");
            if (!groups.add(Xml.text(id))) {
                fixDuplicate("DUPB|" + group.getUserData("index"), id, "Die PmtInfId muss in der Datei eindeutig sein.",
                        groups);
            }
            Set<String> instructions = new HashSet<>();
            for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
                Element instr = Xml.find(tx, "PmtId/InstrId");
                if (instr != null && !instructions.add(Xml.text(instr))) {
                    fixDuplicate("DUPC|" + tx.getUserData("id"), instr, "Die InstrId muss in der Gruppe eindeutig sein.",
                            instructions);
                }
            }
        }
    }

    private void fixDuplicate(String key, Element e, String detail, Set<String> used) {
        forPayment(e);
        String label = e.getLocalName();
        Decision d = ask(key, Severity.OPEN, label + " " + Xml.text(e) + " ist doppelt", detail,
                List.of(Field.text("value", label, Xml.text(e), true, 35)), null, false);
        if (d != null) {
            String fixed = d.value("value");
            if (fixed != null && validId(fixed) && used.add(fixed)) {
                setText(e, fixed);
                changedAt(e, "id");
            } else {
                reopen(key);
            }
        }
        current = null;
    }

    private void finishStatus() {
        Map<String, PaymentView.Status> status = new HashMap<>();
        for (Issue i : issues.values()) {
            if (i.resolved()) {
                continue;
            }
            for (String id : i.payments()) {
                if (i.severity() == Severity.OPEN) {
                    status.put(id, PaymentView.Status.OPEN);
                } else {
                    status.putIfAbsent(id, PaymentView.Status.REVIEW);
                }
            }
        }
        views.replaceAll(v -> v.status() == PaymentView.Status.EXCLUDED || !status.containsKey(v.id()) ? v
                : v.withStatus(status.get(v.id())));
    }

    private Map<String, BigDecimal> sumsByCurrency() {
        Map<String, BigDecimal> sums = new TreeMap<>();
        for (PaymentView v : views) {
            if (v.status() != PaymentView.Status.EXCLUDED) {
                sums.merge(v.currency(), v.amount(), BigDecimal::add);
            }
        }
        return sums;
    }
}
