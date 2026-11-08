package ch.painmigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.painmigrator.convert.Session;
import ch.painmigrator.convert.Views;
import ch.painmigrator.model.ChangeLog;
import ch.painmigrator.model.Decision;
import ch.painmigrator.model.Field;
import ch.painmigrator.model.Issue;
import ch.painmigrator.model.PaymentView;
import ch.painmigrator.validate.FullComparison;
import ch.painmigrator.validate.Schemas;
import ch.painmigrator.xml.Xml;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Prüft die Datentreue an zufälligen Dateien aus dem Schema.
 *
 * <p>Die Anzahl lässt sich erhöhen, zum Beispiel mit {@code ./mvnw test -Dpain.seeds=5000}.
 */
class GeneratedFilesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final int SEEDS = Integer.getInteger("pain.seeds", 150);
    private static final Set<String> NOT_COMPARED_HERE = Set.of("CreDtTm", "NbOfTxs", "CtrlSum");

    private record Converted(Document source, Session session, Document output, Set<String> excluded) {
    }

    private static String answer(Field f, int round) {
        String value = f.value();
        if (!value.isEmpty() && f.validate(value) == null) {
            return value;
        }
        return switch (f.type()) {
            case QR_IBAN -> "CH4431999123000889012";
            case IBAN, DEBTOR_IBAN -> "CH5604835012345678009";
            case QR_REFERENCE -> "210000000003139471430009017";
            case CREDITOR_REFERENCE -> "RF18539007547034";
            case COUNTRY -> "CH";
            case DATE -> "2026-11-09";
            default -> switch (f.name()) {
                case "Nm" -> "Eingabe Name";
                case "TwnNm" -> value.isEmpty() ? "Bern" : value.substring(0, Math.min(35, value.length()));
                case "PstCd" -> "3000";
                case "value" -> value.isBlank() ? "" : "Korrigiert" + round;
                default -> value.substring(0, Math.min(f.maxLength(), value.length()));
            };
        };
    }

    /** Beantwortet alle Rückfragen. Mit {@code varied} wird auch ausgeschlossen und die Alternative gewählt. */
    private static void answerAll(Session s, Random random, boolean varied) {
        for (int round = 0; s.result().openCount() > 0 && round < 3000; round++) {
            s.confirmAllProposals();
            Issue open = s.result().issues().stream().filter(i -> !i.resolved()).findFirst().orElse(null);
            if (open == null) {
                return;
            }
            Map<String, String> values = new HashMap<>();
            for (Field f : open.fields()) {
                values.put(f.name(), answer(f, round));
            }
            if (varied && open.excludable() && random.nextInt(10) == 0) {
                s.decide(open.key(), Decision.exclude());
            } else if (varied && open.omitLabel() != null && random.nextInt(3) == 0) {
                s.decide(open.key(), values.containsKey("Nm")
                        ? new Decision(Decision.Action.OMIT, Map.of("Nm", values.get("Nm"))) : Decision.omit());
            } else {
                s.decide(open.key(), Decision.apply(values));
            }
        }
    }

    private static Converted convert(long seed, boolean varied) throws Exception {
        byte[] source = SourceGenerator.generate(seed);
        assertEquals(List.of(), Schemas.validate(Schemas.SOURCE, source),
                () -> "Testdatei ungültig, Startwert " + seed);
        Session s = Session.open(source, CLOCK);
        answerAll(s, new Random(seed), varied);
        Set<String> excluded = new HashSet<>();
        for (PaymentView p : s.result().payments()) {
            if (p.status() == PaymentView.Status.EXCLUDED) {
                excluded.add(p.id());
            }
        }
        if (s.result().activeCount() == 0) {
            return new Converted(Xml.parse(source), s, null, excluded);
        }
        Session.Export export = s.export();
        assertEquals(List.of(), export.errors(), () -> "Startwert " + seed + "\n"
                + new String(source, StandardCharsets.UTF_8));
        return new Converted(Xml.parse(source), s, Xml.parse(export.xml()), excluded);
    }

    private static List<String> comparison(Converted c) {
        return FullComparison.compare(c.source(), c.output(), c.excluded(), c.session().result().log());
    }

    @Test
    void everyGeneratedFileIsConvertedWithoutUnloggedChange() throws Exception {
        int payments = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            payments += convert(seed, false).session().result().activeCount();
        }
        assertTrue(payments > SEEDS, "zu wenige Zahlungen: " + payments);
    }

    @Test
    void generatedFilesSurviveExclusionsAndAlternatives() throws Exception {
        for (long seed = 100_000; seed < 100_000 + SEEDS; seed++) {
            convert(seed, true);
        }
    }

    /** Eine zweite, von der Schlussprüfung unabhängige Sicht: Beträge und IDs in gleicher Reihenfolge. */
    @Test
    void amountsAndIdsStayInOrder() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Converted c = convert(seed, false);
            List<Element> before = payments(c.source());
            List<Element> after = payments(c.output());
            assertEquals(before.size(), after.size());
            for (int i = 0; i < before.size(); i++) {
                assertEquals(0, Views.amount(before.get(i)).compareTo(Views.amount(after.get(i))));
                assertEquals(Xml.text(before.get(i), "PmtId/EndToEndId"), Xml.text(after.get(i), "PmtId/EndToEndId"));
            }
        }
    }

    private static List<Element> payments(Document d) {
        List<Element> all = new ArrayList<>();
        for (Element g : Xml.children(Views.root(d), "PmtInf")) {
            all.addAll(Xml.children(g, "CdtTrfTxInf"));
        }
        return all;
    }

    private static void collectValues(Element e, List<Element> found) {
        List<Element> kids = Xml.children(e);
        for (Element k : kids) {
            collectValues(k, found);
        }
        if (kids.isEmpty() && !e.getTextContent().isBlank() && !NOT_COMPARED_HERE.contains(e.getLocalName())
                && !Xml.path(e).contains("/PstlAdr/")) {
            found.add(e);
        }
    }

    /** Die Schlussprüfung muss jeden einzelnen verfälschten oder fehlenden Wert finden. */
    @Test
    void everyTamperedValueIsFound() throws Exception {
        int tampered = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Converted c = convert(seed, false);
            Random random = new Random(seed);
            assertEquals(List.of(), comparison(c));
            for (int round = 0; round < 4; round++) {
                List<Element> values = new ArrayList<>();
                collectValues(c.output().getDocumentElement(), values);
                Element target = values.get(random.nextInt(values.size()));
                String original = target.getTextContent();
                if (round % 2 == 0) {
                    target.setTextContent(original + "1");
                    assertFalse(comparison(c).isEmpty(), () -> "Änderung nicht gefunden: " + Xml.path(target));
                    target.setTextContent(original);
                } else {
                    Element parent = (Element) target.getParentNode();
                    org.w3c.dom.Node next = target.getNextSibling();
                    parent.removeChild(target);
                    assertFalse(comparison(c).isEmpty(), () -> "Verlust nicht gefunden: " + Xml.path(target));
                    parent.insertBefore(target, next);
                }
                tampered++;
            }
        }
        assertTrue(tampered >= SEEDS);
    }

    @Test
    void lostPartOfRewrittenAddressIsFound() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Document xmlns=\"http://www.six-interbank-clearing.com/de/pain.001.001.03.ch.02.xsd\"><CstmrCdtTrfInitn>"
                + "<GrpHdr><MsgId>M</MsgId><CreDtTm>2026-11-02T08:00:00</CreDtTm><NbOfTxs>1</NbOfTxs><InitgPty><Nm>F</Nm>"
                + "</InitgPty></GrpHdr><PmtInf><PmtInfId>P</PmtInfId><PmtMtd>TRF</PmtMtd><ReqdExctnDt>2026-12-01</ReqdExctnDt>"
                + "<Dbtr><Nm>F</Nm></Dbtr><DbtrAcct><Id><IBAN>CH9300762011623852957</IBAN></Id></DbtrAcct><DbtrAgt>"
                + "<FinInstnId><BIC>RAIFCH22</BIC></FinInstnId></DbtrAgt><CdtTrfTxInf><PmtId><EndToEndId>E</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"CHF\">1.00</InstdAmt></Amt><Cdtr><Nm>A</Nm><PstlAdr><Ctry>CH</Ctry>"
                + "<AdrLine>Bahnhofstrasse 12</AdrLine><AdrLine>CH-8001 Zürich, Schweiz</AdrLine></PstlAdr></Cdtr><CdtrAcct>"
                + "<Id><IBAN>CH5604835012345678009</IBAN></Id></CdtrAcct></CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        byte[] source = xml.getBytes(StandardCharsets.UTF_8);
        Session s = Session.open(source, CLOCK);
        assertEquals(1, s.confirmAllProposals());
        Session.Export export = s.export();
        assertEquals(List.of(), export.errors());

        Document output = Xml.parse(export.xml());
        ChangeLog log = s.result().log();
        Element town = Xml.find(payments(output).get(0), "Cdtr/PstlAdr/TwnNm");
        town.setTextContent("Bern");
        assertFalse(FullComparison.compare(Xml.parse(source), output, Set.of(), log).isEmpty());
    }
}
