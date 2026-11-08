package ch.painmigrator;

import ch.painmigrator.xml.Xml;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Erzeugt aus dem Schema ch.02 zufällige, gültige Quelldateien. Gleicher Startwert, gleiche Datei.
 *
 * <p>Das Schema bestimmt die Struktur, danach bekommen die Zahlungen fachlich sinnvolle Fälle: ESR, ES, SEPA,
 * QR, Checks, ungültige IBAN, vergangene Daten, falsche Summen. Ausgelassen sind nur Quellen, die das Zielformat
 * nicht abbilden kann und die die Schlussprüfung darum blockiert (siehe README, Grenzen).
 */
final class SourceGenerator {
    private static final String NS = "http://www.six-interbank-clearing.com/de/pain.001.001.03.ch.02.xsd";
    private static final List<String> TEXTS = List.of("Müller & Söhne AG", "Café <Zürich>", "L'Atelier \"Nord\"", "Hans  Meier",
            "Société Générale, Filiale 3", "Bäckerei Nandu", "François Dupont", "Lukas", "Ärzte/Zentrum", "a",
            "Rechnung 4711 vom 01.02.2026", "x+y=z!", "100% sicher (fast)", "Test; Text: Ende?", "Grüezi mitenand");
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcxyz0123456789";
    private static final String DIGITS = "0123456789";
    private static final int[] MOD10 = {0, 9, 4, 6, 8, 2, 7, 1, 3, 5};

    private static final Map<String, Element> COMPLEX = new HashMap<>();
    private static final Map<String, Element> SIMPLE = new HashMap<>();
    private static final String ROOT_TYPE;

    static {
        try (InputStream in = SourceGenerator.class.getResourceAsStream("/xsd/pain.001.001.03.ch.02.xsd")) {
            Element schema = Xml.parse(in.readAllBytes()).getDocumentElement();
            String root = null;
            for (Element e : Xml.children(schema)) {
                switch (e.getLocalName()) {
                    case "complexType" -> COMPLEX.put(e.getAttribute("name"), e);
                    case "simpleType" -> SIMPLE.put(e.getAttribute("name"), e);
                    case "element" -> root = e.getAttribute("type");
                    default -> {
                    }
                }
            }
            ROOT_TYPE = root;
        } catch (IOException | SAXException e) {
            throw new IllegalStateException(e);
        }
    }

    private final Random random;
    private final double optional;
    private final boolean shortAddresses;
    private final Document doc = Xml.newDocument();
    private int ids;

    private SourceGenerator(long seed) {
        random = new Random(seed);
        optional = new double[] {.1, .3, .5, .75, .95}[random.nextInt(5)];
        shortAddresses = random.nextBoolean();
    }

    static byte[] generate(long seed) {
        return new SourceGenerator(seed).build();
    }

    private byte[] build() {
        Element root = doc.createElementNS(NS, "Document");
        doc.appendChild(root);
        particles(COMPLEX.get(ROOT_TYPE), root, "");
        businessCases(Xml.child(root, "CstmrCdtTrfInitn"));
        return Xml.serialize(doc);
    }

    // ---- Struktur aus dem Schema -----------------------------------------------------------------------

    private void particles(Element node, Element parent, String path) {
        for (Element c : Xml.children(node)) {
            switch (c.getLocalName()) {
                case "sequence", "complexContent", "restriction", "extension" -> particles(c, parent, path);
                case "element" -> element(c, parent, path, false);
                case "choice" -> {
                    if (!"0".equals(c.getAttribute("minOccurs")) || random.nextDouble() < optional) {
                        List<Element> options = Xml.children(c);
                        Element pick = options.get(random.nextInt(options.size()));
                        if ("element".equals(pick.getLocalName())) {
                            element(pick, parent, path, true);
                        } else {
                            particles(pick, parent, path);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void element(Element declaration, Element parent, String path, boolean forced) {
        String name = declaration.getAttribute("name");
        String here = path + "/" + name;
        boolean mayOmit = "0".equals(declaration.getAttribute("minOccurs"));
        if ((mayOmit && !forced && random.nextDouble() >= optional) || notConvertible(here)) {
            return;
        }
        String max = declaration.getAttribute("maxOccurs");
        int limit = max.isEmpty() ? 1 : "unbounded".equals(max) ? 3 : Math.min(3, Integer.parseInt(max));
        if ("InstrForCdtrAgt".equals(name) || "Othr".equals(name)) {
            limit = Math.min(limit, 2);
        }
        int count = "PmtInf".equals(name) || "CdtTrfTxInf".equals(name) ? 1 + random.nextInt(3)
                : limit > 1 ? 1 + random.nextInt(limit) : 1;
        for (int i = 0; i < count; i++) {
            Element e = doc.createElementNS(NS, name);
            parent.appendChild(e);
            fill(e, declaration.getAttribute("type"), here);
        }
    }

    private static boolean notConvertible(String path) {
        return path.contains("/DbtrAgt/") && path.endsWith("/ClrSysMmbId");
    }

    private void fill(Element e, String type, String path) {
        Element complex = COMPLEX.get(type);
        if (complex == null) {
            e.setTextContent(simple(type, path));
        } else if (Xml.child(complex, "simpleContent") != null) {
            e.setTextContent(amount());
            e.setAttribute("Ccy", pick("CHF", "EUR", "USD"));
        } else {
            particles(complex, e, path);
        }
    }

    private String simple(String type, String path) {
        switch (type) {
            case "Max35Text-Swift":
                return "ID" + (++ids) + digits(6);
            case "ISODate":
                return path.contains("ReqdExctnDt") ? LocalDate.of(2026, 11, 8).plusDays(random.nextInt(400)).toString()
                        : "1980-02-29";
            case "ISODateTime":
                return "2026-11-01T12:00:00";
            case "DecimalNumber", "Max15NumericText":
                return "1";
            case "IBAN2007Identifier":
                return domesticIban();
            case "BICIdentifier", "AnyBICIdentifier":
                return pick("UBSWCHZH80A", "POFICHBEXXX", "DEUTDEFF", "BNPAFRPP", "CHASUS33", "BPCHLI2X");
            case "CountryCode":
                return pick("CH", "LI", "DE", "FR", "AT", "IT", "US", "GB");
            case "ActiveOrHistoricCurrencyCode", "ActiveCurrencyCode", "CurrencyCode":
                return pick("CHF", "EUR", "USD");
            case "PhoneNumber":
                return "+41-791234567";
            case "xs:boolean", "TrueFalseIndicator", "BatchBookingIndicator":
                return pick("true", "false");
            default:
                break;
        }
        Element restriction = Xml.child(SIMPLE.get(type), "restriction");
        if (restriction == null) {
            throw new IllegalStateException("Typ unbekannt: " + type + " in " + path);
        }
        List<Element> values = Xml.children(restriction, "enumeration");
        if (!values.isEmpty()) {
            return values.get(random.nextInt(values.size())).getAttribute("value");
        }
        Element maximum = Xml.child(restriction, "maxLength");
        if (maximum != null) {
            return shortAddresses && path.contains("PstlAdr") ? "w" + digits(6)
                    : text(Integer.parseInt(maximum.getAttribute("value")));
        }
        if (Xml.child(restriction, "totalDigits") != null) {
            return amount();
        }
        return pick("true", "false");
    }

    private String text(int maxLength) {
        String s = TEXTS.get(random.nextInt(TEXTS.size()));
        return s.substring(0, Math.min(s.length(), maxLength)).trim();
    }

    private String amount() {
        return (1 + random.nextInt(99999)) + "." + String.format(Locale.ROOT, "%02d", random.nextInt(100));
    }

    private String pick(String... values) {
        return values[random.nextInt(values.length)];
    }

    private String digits(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DIGITS.charAt(random.nextInt(10)));
        }
        return b.toString();
    }

    // ---- Prüfziffern -----------------------------------------------------------------------------------

    private static int mod97(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(Character.digit(c, 36));
        }
        return new BigInteger(b.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    private static String iban(String country, String account) {
        return country + String.format(Locale.ROOT, "%02d", 98 - mod97(account + country + "00")) + account;
    }

    private String domesticIban() {
        return iban("CH", "04835" + digits(12));
    }

    private String qrReference() {
        String base = digits(26);
        int carry = 0;
        for (char c : base.toCharArray()) {
            carry = MOD10[(carry + c - '0') % 10];
        }
        return base + (10 - carry) % 10;
    }

    private String creditorReference() {
        StringBuilder b = new StringBuilder();
        for (int i = 1 + random.nextInt(21); i > 0; i--) {
            b.append(ALPHANUMERIC.substring(0, 26).charAt(random.nextInt(26)));
        }
        return "RF" + String.format(Locale.ROOT, "%02d", 98 - mod97(b + "RF00")) + b;
    }

    // ---- Fachliche Fälle -------------------------------------------------------------------------------

    private void businessCases(Element initn) {
        BigInteger total = BigInteger.ZERO;
        int count = 0;
        for (Element group : Xml.children(initn, "PmtInf")) {
            boolean cheque = random.nextInt(8) == 0;
            Xml.child(group, "PmtMtd").setTextContent(cheque ? "CHK" : pick("TRF", "TRF", "TRA"));
            Element institution = Xml.find(group, "DbtrAgt/FinInstnId");
            if (Xml.children(institution).isEmpty()) {
                institution.appendChild(leaf("BIC", "UBSWCHZH80A"));
            }
            Element debtor = Xml.child(group, "Dbtr");
            if (Xml.child(debtor, "Nm") == null) {
                debtor.insertBefore(leaf("Nm", text(70)), debtor.getFirstChild());
            }
            Element debtorIban = Xml.find(group, "DbtrAcct/Id/IBAN");
            if (debtorIban != null) {
                debtorIban.setTextContent(random.nextInt(6) == 0 ? "CH0000000000000000000" : domesticIban());
            }
            BigInteger sum = BigInteger.ZERO;
            int n = 0;
            for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
                Element creditor = child(tx, "Cdtr", "CdtrAcct", "UltmtCdtr", "InstrForCdtrAgt", "InstrForDbtrAgt", "Purp",
                        "RgltryRptg", "RmtInf");
                if (Xml.child(creditor, "Nm") == null) {
                    creditor.insertBefore(leaf("Nm", text(70)), creditor.getFirstChild());
                }
                if (!cheque) {
                    paymentCase(tx);
                }
                sum = sum.add(new java.math.BigDecimal(amountOf(tx).getTextContent()).movePointRight(2).toBigInteger());
                n++;
            }
            set(group, "NbOfTxs", String.valueOf(n));
            set(group, "CtrlSum", random.nextInt(10) == 0 ? "1.00" : money(sum));
            if (random.nextInt(7) == 0) {
                Xml.child(group, "ReqdExctnDt").setTextContent("2025-01-15");
            }
            total = total.add(sum);
            count += n;
        }
        Element header = Xml.child(initn, "GrpHdr");
        set(header, "NbOfTxs", String.valueOf(count));
        set(header, "CtrlSum", money(total));
    }

    private static Element amountOf(Element tx) {
        Element instructed = Xml.find(tx, "Amt/InstdAmt");
        return instructed != null ? instructed : Xml.find(tx, "Amt/EqvtAmt/Amt");
    }

    private static String money(BigInteger cents) {
        return new java.math.BigDecimal(cents).movePointLeft(2).toPlainString();
    }

    private static void set(Element parent, String name, String value) {
        Element e = Xml.child(parent, name);
        if (e != null) {
            e.setTextContent(value);
        }
    }

    private void paymentCase(Element tx) {
        switch (random.nextInt(11)) {
            case 0 -> {
                paymentType(tx, null, "CH01");
                account(tx, null, pick("01-12345-6", "010000123"));
                reference(tx, qrReference());
            }
            case 1 -> {
                paymentType(tx, null, "CH02");
                account(tx, null, pick("80-5522-9", "30-12345-7"));
            }
            case 2 -> {
                paymentType(tx, null, "CH03");
                account(tx, domesticIban(), null);
            }
            case 3, 4 -> {
                paymentType(tx, "SEPA", null);
                account(tx, iban("DE", digits(18)), null);
                Element instructed = Xml.find(tx, "Amt/InstdAmt");
                if (instructed != null) {
                    instructed.setAttribute("Ccy", "EUR");
                }
            }
            case 5, 6 -> {
                paymentType(tx, null, null);
                account(tx, domesticIban(), null);
                if (random.nextBoolean()) {
                    reference(tx, random.nextBoolean() ? creditorReference() : "RECHNUNG" + digits(4));
                }
            }
            case 7, 8 -> {
                paymentType(tx, null, null);
                account(tx, iban("CH", "31999" + digits(12)), null);
                reference(tx, qrReference());
                if (random.nextBoolean()) {
                    Element remittance = Xml.child(tx, "RmtInf");
                    remittance.insertBefore(leaf("Ustrd", text(140)), remittance.getFirstChild());
                }
            }
            case 9 -> account(tx, "CH0000000000000000000", null);
            default -> {
            }
        }
    }

    private Element leaf(String name, String value) {
        Element e = doc.createElementNS(NS, name);
        e.setTextContent(value);
        return e;
    }

    /** Liefert ein Kind und legt es bei Bedarf vor dem ersten der genannten Folgeelemente an. */
    private Element child(Element parent, String name, String... followers) {
        Element e = Xml.child(parent, name);
        if (e != null) {
            return e;
        }
        e = doc.createElementNS(NS, name);
        for (Element sibling : Xml.children(parent)) {
            if (List.of(followers).contains(sibling.getLocalName())) {
                parent.insertBefore(e, sibling);
                return e;
            }
        }
        parent.appendChild(e);
        return e;
    }

    private void paymentType(Element tx, String serviceLevel, String localInstrument) {
        Xml.remove(Xml.child(tx, "PmtTpInf"));
        if (serviceLevel == null && localInstrument == null) {
            return;
        }
        Element type = doc.createElementNS(NS, "PmtTpInf");
        if (serviceLevel != null) {
            Element level = doc.createElementNS(NS, "SvcLvl");
            level.appendChild(leaf("Cd", serviceLevel));
            type.appendChild(level);
        }
        if (localInstrument != null) {
            Element local = doc.createElementNS(NS, "LclInstrm");
            local.appendChild(leaf("Prtry", localInstrument));
            type.appendChild(local);
        }
        tx.insertBefore(type, Xml.child(tx, "PmtId").getNextSibling());
    }

    private void account(Element tx, String iban, String other) {
        Element account = child(tx, "CdtrAcct", "UltmtCdtr", "InstrForCdtrAgt", "InstrForDbtrAgt", "Purp", "RgltryRptg",
                "RmtInf");
        for (Element e : new ArrayList<>(Xml.children(account))) {
            Xml.remove(e);
        }
        Element id = doc.createElementNS(NS, "Id");
        account.appendChild(id);
        if (iban != null) {
            id.appendChild(leaf("IBAN", iban));
        } else {
            Element othr = doc.createElementNS(NS, "Othr");
            othr.appendChild(leaf("Id", other));
            id.appendChild(othr);
        }
    }

    private void reference(Element tx, String ref) {
        Xml.remove(Xml.child(tx, "RmtInf"));
        Element remittance = doc.createElementNS(NS, "RmtInf");
        Element structured = doc.createElementNS(NS, "Strd");
        Element info = doc.createElementNS(NS, "CdtrRefInf");
        info.appendChild(leaf("Ref", ref));
        structured.appendChild(info);
        remittance.appendChild(structured);
        tx.appendChild(remittance);
    }
}
