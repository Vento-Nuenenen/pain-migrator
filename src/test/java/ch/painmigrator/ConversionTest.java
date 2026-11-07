package ch.painmigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.painmigrator.convert.Session;
import ch.painmigrator.model.Decision;
import ch.painmigrator.model.Field;
import ch.painmigrator.model.Issue;
import ch.painmigrator.model.PaymentView;
import ch.painmigrator.validate.Schemas;
import ch.painmigrator.xml.Xml;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class ConversionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String QR_IBAN = "CH4431999123000889012";
    private static final String IBAN = "CH5604835012345678009";

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = ConversionTest.class.getResourceAsStream("/samples/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static Session open(byte[] data) throws Session.RejectedFile {
        return Session.open(data, CLOCK);
    }

    private static Session open(String xml) throws Session.RejectedFile {
        return open(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** Beantwortet alle offenen Punkte so, wie es ein Benutzer im Dialog täte. */
    private static void answerEverything(Session s) {
        for (int round = 0; round < 50 && s.result().openCount() > 0; round++) {
            s.confirmAllProposals();
            for (Issue i : s.result().issues()) {
                if (i.resolved()) {
                    continue;
                }
                Map<String, String> values = new HashMap<>();
                for (Field f : i.fields()) {
                    values.put(f.name(), answer(f));
                }
                s.decide(i.key(), Decision.apply(values));
                break;
            }
        }
    }

    private static String answer(Field f) {
        if (!f.value().isEmpty()) {
            return f.value();
        }
        return switch (f.name()) {
            case "iban" -> f.type() == Field.Type.QR_IBAN ? QR_IBAN : IBAN;
            case "Nm" -> "Testempfänger";
            case "TwnNm" -> "Bern";
            case "PstCd" -> "3000";
            case "Ctry" -> "CH";
            default -> "";
        };
    }

    private static Document assertExportValid(Session s) throws Exception {
        Session.Export export = s.export();
        assertEquals(List.of(), export.errors());
        assertTrue(Schemas.validate(Schemas.TARGET, export.xml()).isEmpty());
        return Xml.parse(export.xml());
    }

    private static Issue issue(Session s, String keyPrefix) {
        return s.result().issues().stream().filter(i -> i.key().startsWith(keyPrefix)).findFirst().orElse(null);
    }

    private static PaymentView payment(Session s, String id) {
        return s.result().payments().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Element> tx(Document d) {
        List<Element> all = new java.util.ArrayList<>();
        for (Element g : Xml.children(Xml.child(d.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf")) {
            all.addAll(Xml.children(g, "CdtTrfTxInf"));
        }
        return all;
    }

    @Test
    void sourceSamplesPassTheOldSchema() throws Exception {
        for (String n : List.of("pain_001_Beispiel_ESR_ES_SEPA.xml", "pain_001_Beispiel_QRR_SCOR.xml",
                "alle_zahlungsarten.xml")) {
            assertEquals(List.of(), Schemas.validate(Schemas.SOURCE, sample(n)), n);
        }
    }

    @Test
    void sixSampleWithLegacyPaymentTypes() throws Exception {
        Session s = open(sample("pain_001_Beispiel_ESR_ES_SEPA.xml"));
        assertEquals(3, s.result().payments().size());
        assertEquals("1", payment(s, "1.1").oldType());
        assertEquals("?", payment(s, "1.1").newType());
        assertEquals(PaymentView.Status.OPEN, payment(s, "1.1").status());
        assertEquals("2.1", payment(s, "2.1").oldType());
        assertEquals("5", payment(s, "2.2").oldType());
        assertEquals("S", payment(s, "2.2").newType());
        assertFalse(s.result().ready());
        assertFalse(s.export().ok());

        answerEverything(s);
        Document out = assertExportValid(s);
        Element esr = tx(out).get(0);
        assertEquals(QR_IBAN, Xml.text(esr, "CdtrAcct/Id/IBAN"));
        assertEquals("QRR", Xml.text(esr, "RmtInf/Strd/CdtrRefInf/Tp/CdOrPrtry/Prtry"));
        assertEquals("210000000003139471430009017", Xml.text(esr, "RmtInf/Strd/CdtrRefInf/Ref"));
        assertEquals("Testempfänger", Xml.text(esr, "Cdtr/Nm"));
        assertNull(Xml.find(esr, "PmtTpInf"));
        assertEquals("D", payment(s, "1.1").newType());
        Element sepa = tx(out).get(2);
        assertEquals("Rosenauweg", Xml.text(sepa, "Cdtr/PstlAdr/StrtNm"));
        assertEquals("4", Xml.text(sepa, "Cdtr/PstlAdr/BldgNb"));
        assertEquals("München", Xml.text(sepa, "Cdtr/PstlAdr/TwnNm"));
        assertEquals("DE", Xml.text(sepa, "Cdtr/PstlAdr/Ctry"));
        assertNull(Xml.find(sepa, "Cdtr/PstlAdr/AdrLine"));
    }

    @Test
    void sixSampleQrBill() throws Exception {
        Session s = open(sample("pain_001_Beispiel_QRR_SCOR.xml"));
        answerEverything(s);
        assertExportValid(s);
    }

    @Test
    void allPaymentTypes() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        assertEquals("D", payment(s, "1.1").newType());
        assertEquals("D", payment(s, "1.3").newType());
        assertEquals("4", payment(s, "2.1").oldType());
        assertEquals("X", payment(s, "2.1").newType());
        assertEquals("6", payment(s, "2.2").oldType());
        assertEquals("X", payment(s, "2.2").newType());
        assertEquals("S", payment(s, "3.1").newType());
        assertEquals("8", payment(s, "4.1").oldType());
        assertEquals("C", payment(s, "4.1").newType());

        answerEverything(s);
        Document out = assertExportValid(s);
        Element root = Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn");
        Element header = Xml.child(root, "GrpHdr");
        assertEquals("SYNTH-2026-001", Xml.text(header, "MsgId"));
        assertEquals("7", Xml.text(header, "NbOfTxs"));
        assertEquals(0, new BigDecimal("16001.05").compareTo(new BigDecimal(Xml.text(header, "CtrlSum"))));
        assertEquals("RAIFCH22", Xml.text(header, "InitgPty/Id/OrgId/AnyBIC"));
        assertEquals("NAME", Xml.text(header, "InitgPty/CtctDtls/Othr/ChanlTp"));
        assertNull(Xml.find(header, "InitgPty/CtctDtls/Nm"));

        Element first = Xml.child(root, "PmtInf");
        assertEquals("TRF", Xml.text(first, "PmtMtd"));
        assertEquals("2026-11-12", Xml.text(first, "ReqdExctnDt/Dt"));
        assertEquals("RAIFCH22", Xml.text(first, "DbtrAgt/FinInstnId/BICFI"));
        assertNull(Xml.find(first, "Dbtr/PstlAdr/AdrTp"));
        assertEquals("Zug", Xml.text(first, "Dbtr/PstlAdr/TwnNm"));

        List<Element> txs = tx(out);
        assertEquals("Dorfstrasse", Xml.text(txs.get(2), "Cdtr/PstlAdr/StrtNm"));
        assertEquals("7a", Xml.text(txs.get(2), "Cdtr/PstlAdr/BldgNb"));
        assertNull(Xml.find(txs.get(2), "CdtrAgt/FinInstnId/Nm"));
        assertEquals("123456", Xml.text(txs.get(2), "CdtrAcct/Id/Othr/Id"));
        assertEquals("4835", Xml.text(txs.get(2), "CdtrAgt/FinInstnId/ClrSysMmbId/MmbId"));
    }

    @Test
    void sameCreditorIsAskedOnlyOnce() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue erika = s.result().issues().stream().filter(i -> i.title().contains("Erika")).findFirst().orElseThrow();
        assertEquals(List.of("1.1", "1.2"), erika.payments());
        assertEquals(1, s.result().issues().stream().filter(i -> i.title().contains("Erika")).count());
    }

    @Test
    void clearAddressIsOnlyAProposal() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue erika = s.result().issues().stream().filter(i -> i.title().contains("Erika")).findFirst().orElseThrow();
        assertEquals(Issue.Severity.REVIEW, erika.severity());
        Issue london = s.result().issues().stream().filter(i -> i.title().contains("Example Trading")).findFirst().orElseThrow();
        assertEquals(Issue.Severity.OPEN, london.severity());
    }

    @Test
    void pastExecutionDateNeedsNewDate() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue date = issue(s, "DATE|2026-10-01");
        assertNotNull(date);
        assertEquals(List.of("3.1"), date.payments());
        s.decide(date.key(), Decision.apply(Map.of("date", "2026-11-20")));
        answerEverything(s);
        Document out = assertExportValid(s);
        Element groups = Xml.children(Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf").get(2);
        assertEquals("2026-11-20", Xml.text(groups, "ReqdExctnDt/Dt"));
    }

    @Test
    void chargeBearerOnGroupLevelIsCheckedForSepa() throws Exception {
        Session s = open(modified("<PmtInfId>GRP-EUR</PmtInfId>\n      <PmtMtd>TRF</PmtMtd>\n      <PmtTpInf>\n"
                + "        <SvcLvl>\n          <Cd>SEPA</Cd>\n        </SvcLvl>\n      </PmtTpInf>\n"
                + "      <ReqdExctnDt>2026-10-01</ReqdExctnDt>",
                "<PmtInfId>GRP-EUR</PmtInfId>\n      <PmtMtd>TRF</PmtMtd>\n      <PmtTpInf>\n"
                + "        <SvcLvl>\n          <Cd>SEPA</Cd>\n        </SvcLvl>\n      </PmtTpInf>\n"
                + "      <ReqdExctnDt>2026-11-20</ReqdExctnDt>")
                .replace("<IBAN>CH9300762011623852957</IBAN>\n        </Id>\n      </DbtrAcct>\n      <DbtrAgt>\n"
                        + "        <FinInstnId>\n          <BIC>RAIFCH22</BIC>\n        </FinInstnId>\n"
                        + "      </DbtrAgt>\n      <CdtTrfTxInf>\n        <PmtId>\n          <EndToEndId>E2E-6",
                        "<IBAN>CH9300762011623852957</IBAN>\n        </Id>\n      </DbtrAcct>\n      <DbtrAgt>\n"
                        + "        <FinInstnId>\n          <BIC>RAIFCH22</BIC>\n        </FinInstnId>\n"
                        + "      </DbtrAgt>\n      <ChrgBr>DEBT</ChrgBr>\n      <CdtTrfTxInf>\n        <PmtId>\n"
                        + "          <EndToEndId>E2E-6"));
        assertEquals("S", payment(s, "3.1").newType());
        assertNotNull(issue(s, "CHRGBR|DEBT"));
        answerEverything(s);
        Document out = assertExportValid(s);
        Element sepaGroup = Xml.children(Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf").get(2);
        assertNull(Xml.child(sepaGroup, "ChrgBr"));
    }

    @Test
    void groupIssueCoversPaymentsOfAllGroups() throws Exception {
        Session s = open(modified("<ReqdExctnDt>2026-11-12</ReqdExctnDt>", "<ReqdExctnDt>2026-10-01</ReqdExctnDt>"));
        Issue date = issue(s, "DATE|2026-10-01");
        assertEquals(7, date.payments().size());
        assertEquals(PaymentView.Status.OPEN, payment(s, "2.1").status());
    }

    @Test
    void addressLineNextToStructuredFieldsIsNotLost() throws Exception {
        Session s = open(modified("<TwnNm>Basel</TwnNm>\n            <Ctry>CH</Ctry>",
                "<TwnNm>Basel</TwnNm>\n            <Ctry>CH</Ctry>\n            <AdrLine>Gebaeude C</AdrLine>"));
        Issue vera = s.result().issues().stream().filter(i -> i.title().contains("Vera")).findFirst().orElseThrow();
        assertEquals(Issue.Severity.OPEN, vera.severity());
        Field extra = vera.fields().stream().filter(f -> "Dept".equals(f.name())).findFirst().orElseThrow();
        assertEquals("Gebaeude C", extra.value());
    }

    @Test
    void overlongTownIsNotConfirmedBlindly() throws Exception {
        String town = "Sehr langer Ortsname der nicht in das Feld passt";
        Session s = open(modified("CH-8001 Zürich", "8001 " + town));
        Issue erika = s.result().issues().stream().filter(i -> i.title().contains("Erika")).findFirst().orElseThrow();
        assertEquals(Issue.Severity.OPEN, erika.severity());
    }

    @Test
    void chequeNeedsPostCode() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue vera = s.result().issues().stream().filter(i -> i.title().contains("Vera")).findFirst().orElseThrow();
        assertEquals(Issue.Severity.OPEN, vera.severity());
        assertNull(vera.omitLabel());
    }

    @Test
    void creditorAddressCanBeOmittedOnPurpose() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue erika = s.result().issues().stream().filter(i -> i.title().contains("Erika")).findFirst().orElseThrow();
        assertEquals("Ohne Adresse weiter", erika.omitLabel());
        s.decide(erika.key(), Decision.omit());
        answerEverything(s);
        assertNull(Xml.find(tx(assertExportValid(s)).get(0), "Cdtr/PstlAdr"));
    }

    @Test
    void excludedPaymentLeavesFileAndSumsFollow() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        s.setExcluded("1.1", true);
        assertEquals(PaymentView.Status.EXCLUDED, payment(s, "1.1").status());
        answerEverything(s);
        Document out = assertExportValid(s);
        Element root = Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn");
        assertEquals("6", Xml.text(root, "GrpHdr/NbOfTxs"));
        assertEquals(0, new BigDecimal("15001.05").compareTo(new BigDecimal(Xml.text(root, "GrpHdr/CtrlSum"))));
        Element first = Xml.child(root, "PmtInf");
        assertEquals("2", Xml.text(first, "NbOfTxs"));
        assertEquals(0, new BigDecimal("751.05").compareTo(new BigDecimal(Xml.text(first, "CtrlSum"))));
    }

    @Test
    void excludingWholeGroupRemovesIt() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        s.setExcluded("4.1", true);
        answerEverything(s);
        Document out = assertExportValid(s);
        assertEquals(3, Xml.children(Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf").size());
    }

    @Test
    void excludingEverythingBlocksExport() throws Exception {
        Session s = open(sample("pain_001_Beispiel_QRR_SCOR.xml"));
        for (PaymentView p : s.result().payments()) {
            s.setExcluded(p.id(), true);
        }
        answerEverything(s);
        assertFalse(s.result().ready());
        assertFalse(s.export().ok());
    }

    @Test
    void excludeViaIssueDecisionCoversAllAffected() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue erika = s.result().issues().stream().filter(i -> i.title().contains("Erika")).findFirst().orElseThrow();
        s.decide(erika.key(), Decision.exclude());
        assertEquals(PaymentView.Status.EXCLUDED, payment(s, "1.1").status());
        assertEquals(PaymentView.Status.EXCLUDED, payment(s, "1.2").status());
    }

    @Test
    void decisionCanBeTakenBack() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        Issue date = issue(s, "DATE|");
        s.decide(date.key(), Decision.apply(Map.of("date", "2026-11-20")));
        assertTrue(issue(s, "DATE|").resolved());
        s.undo(date.key());
        assertFalse(issue(s, "DATE|").resolved());
    }

    // ---- Negativfälle ----------------------------------------------------------------------------------

    private static String modified(String from, String to) throws IOException {
        String xml = new String(sample("alle_zahlungsarten.xml"), StandardCharsets.UTF_8);
        assertTrue(xml.contains(from), from);
        return xml.replace(from, to);
    }

    @Test
    void wrongControlSumIsReportedNotSilentlyFixed() throws Exception {
        Session s = open(modified("<CtrlSum>16001.05</CtrlSum>", "<CtrlSum>16001.15</CtrlSum>"));
        Issue sum = issue(s, "SUM_A");
        assertNotNull(sum);
        assertEquals(Issue.Severity.REVIEW, sum.severity());
        assertFalse(sum.resolved());
    }

    @Test
    void wrongTransactionCountIsReported() throws Exception {
        Session s = open(modified("<NbOfTxs>7</NbOfTxs>", "<NbOfTxs>8</NbOfTxs>"));
        assertNotNull(issue(s, "SUM_A"));
    }

    @Test
    void wrongGroupSumIsReported() throws Exception {
        Session s = open(modified("<CtrlSum>1751.05</CtrlSum>", "<CtrlSum>1751.00</CtrlSum>"));
        assertNotNull(issue(s, "SUM_B|1"));
    }

    @Test
    void invalidCreditorIbanNeedsCorrection() throws Exception {
        Session s = open(modified("GB29NWBK60161331926819", "GB29NWBK60161331926818"));
        Issue i = issue(s, "IBAN|GB29");
        assertNotNull(i);
        assertFalse(i.resolved());
        s.decide(i.key(), Decision.apply(Map.of("iban", "GB29NWBK60161331926819")));
        assertTrue(issue(s, "IBAN|GB29").resolved());
        answerEverything(s);
        assertEquals("GB29NWBK60161331926819", Xml.text(tx(assertExportValid(s)).get(4), "CdtrAcct/Id/IBAN"));
    }

    @Test
    void invalidDebtorIbanNeedsCorrection() throws Exception {
        Session s = open(modified("CH9300762011623852957", "CH9300762011623852958"));
        assertNotNull(issue(s, "DBTRACCT|"));
    }

    @Test
    void qrReferenceWithNormalIbanIsRejected() throws Exception {
        String xml = modified("<Ustrd>Rechnung 2026-17</Ustrd>", "<Strd><CdtrRefInf><Tp><CdOrPrtry><Prtry>QRR</Prtry>"
                + "</CdOrPrtry></Tp><Ref>210000000003139471430009017</Ref></CdtrRefInf></Strd>");
        Session s = open(xml);
        Issue ref = issue(s, "REF|210000000003139471430009017");
        assertNotNull(ref);
        assertEquals("Als Mitteilung übernehmen", ref.omitLabel());
        s.decide(ref.key(), Decision.omit());
        answerEverything(s);
        Document out = assertExportValid(s);
        assertEquals("210000000003139471430009017", Xml.text(tx(out).get(0), "RmtInf/Ustrd"));
    }

    @Test
    void doctypeIsRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d>&x;</d>";
        assertThrows(Session.RejectedFile.class, () -> open(xxe));
    }

    @Test
    void foreignFormatIsRejected() {
        String other = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.02\"/>";
        Session.RejectedFile e = assertThrows(Session.RejectedFile.class, () -> open(other));
        assertTrue(e.getMessage().contains("nicht unterstützt"));
    }

    @Test
    void targetFormatIsRejected() {
        String target = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"/>";
        Session.RejectedFile e = assertThrows(Session.RejectedFile.class, () -> open(target));
        assertTrue(e.getMessage().contains("bereits"));
    }

    @Test
    void brokenXmlIsRejected() {
        assertThrows(Session.RejectedFile.class, () -> open("<Document"));
    }

    @Test
    void invalidSourceSchemaNeedsConfirmation() throws Exception {
        Session s = open(modified("<Nm>Hans Muster</Nm>", "<Nm>Hans Muster</Nm><Unbekannt>x</Unbekannt>"));
        assertNotNull(issue(s, "SRC_XSD"));
    }

    @Test
    void unknownElementIsNotLostSilently() throws Exception {
        Session s = open(modified("<Nm>Hans Muster</Nm>", "<Nm>Hans Muster</Nm><Unbekannt>x</Unbekannt>"));
        Issue i = issue(s, "PATH|");
        assertNotNull(i);
        assertTrue(i.title().contains("Unbekannt"));
        assertFalse(i.resolved());
    }

    @Test
    void forbiddenCharactersMustBeFixed() throws Exception {
        Session s = open(modified("<Ustrd>Rechnung 2026-17</Ustrd>", "<Ustrd>Rechnung ☃</Ustrd>"));
        Issue i = issue(s, "TXT|");
        assertNotNull(i);
        s.decide(i.key(), Decision.apply(Map.of("value", "Rechnung")));
        answerEverything(s);
        assertExportValid(s);
    }

    @Test
    void badSlashInIdMustBeFixed() throws Exception {
        Session s = open(modified("<EndToEndId>E2E-4</EndToEndId>", "<EndToEndId>E2E//4</EndToEndId>"));
        Issue i = issue(s, "TXT|");
        assertNotNull(i);
        s.decide(i.key(), Decision.apply(Map.of("value", "E2E-4")));
        answerEverything(s);
        assertExportValid(s);
    }

    @Test
    void duplicateGroupIdMustBeFixed() throws Exception {
        Session s = open(modified("<PmtInfId>GRP-USD</PmtInfId>", "<PmtInfId>GRP-CHF</PmtInfId>"));
        Issue i = issue(s, "DUPB|");
        assertNotNull(i);
    }

    @Test
    void amountWithoutDigitsIsRejectedAtOpen() throws Exception {
        String xml = modified("<InstdAmt Ccy=\"CHF\">1000.00</InstdAmt>", "<InstdAmt Ccy=\"CHF\">abc</InstdAmt>");
        assertThrows(Session.RejectedFile.class, () -> open(xml));
    }

    @Test
    void outputHasNoBomAndIsUtf8() throws Exception {
        Session s = open(sample("alle_zahlungsarten.xml"));
        answerEverything(s);
        byte[] xml = s.export().xml();
        assertEquals('<', xml[0]);
        assertTrue(new String(xml, StandardCharsets.UTF_8).contains("Zürich"));
    }
}
