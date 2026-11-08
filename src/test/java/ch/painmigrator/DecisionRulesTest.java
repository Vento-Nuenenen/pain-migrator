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
import ch.painmigrator.validate.Checksums;
import ch.painmigrator.xml.Xml;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Antworten auf Rückfragen: Sie müssen das Problem wirklich lösen und dürfen nicht in eine Sackgasse führen. */
class DecisionRulesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String IBAN = "CH5604835012345678009";
    private static final String QR_IBAN = "CH4431999123000889012";
    private static final String GERMAN_IBAN = "DE89370400440532013000";
    private static final String QR_REFERENCE = "210000000003139471430009017";
    private static final String WRONG_QR_REFERENCE = "210000000003139471430009018";
    private static final String ISO_REFERENCE = "RF18539007547034";
    private static final String SEPA = "<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl></PmtTpInf>";
    private static final String ADDRESS = "<PstlAdr><StrtNm>Weg</StrtNm><BldgNb>1</BldgNb><PstCd>8001</PstCd>"
            + "<TwnNm>Zürich</TwnNm><Ctry>CH</Ctry></PstlAdr>";

    private static String account(String iban) {
        return "<CdtrAcct><Id><IBAN>" + iban + "</IBAN></Id></CdtrAcct>";
    }

    private static String reference(String type, String ref) {
        return "<RmtInf><Strd><CdtrRefInf>" + type + "<Ref>" + ref + "</Ref></CdtrRefInf></Strd></RmtInf>";
    }

    private static String qrType() {
        return "<Tp><CdOrPrtry><Prtry>QRR</Prtry></CdOrPrtry></Tp>";
    }

    private static String isoType() {
        return "<Tp><CdOrPrtry><Cd>SCOR</Cd></CdOrPrtry></Tp>";
    }

    private static String payment(String id, String name, String ccy, String paymentType, String after) {
        return "<CdtTrfTxInf><PmtId><InstrId>" + id + "</InstrId><EndToEndId>E" + id + "</EndToEndId></PmtId>" + paymentType
                + "<Amt><InstdAmt Ccy=\"" + ccy + "\">1.00</InstdAmt></Amt><Cdtr><Nm>" + name + "</Nm>" + ADDRESS + "</Cdtr>"
                + after + "</CdtTrfTxInf>";
    }

    private static String group(String id, String method, String chargeBearer, String... payments) {
        return "<PmtInf><PmtInfId>" + id + "</PmtInfId><PmtMtd>" + method + "</PmtMtd>"
                + "<ReqdExctnDt>2026-11-20</ReqdExctnDt><Dbtr><Nm>Firma</Nm>" + ADDRESS + "</Dbtr>"
                + "<DbtrAcct><Id><IBAN>CH9300762011623852957</IBAN></Id></DbtrAcct>"
                + "<DbtrAgt><FinInstnId><BIC>RAIFCH22</BIC></FinInstnId></DbtrAgt>" + chargeBearer
                + String.join("", payments) + "</PmtInf>";
    }

    private static String file(int count, String... groups) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Document xmlns=\"http://www.six-interbank-clearing.com/de/pain.001.001.03.ch.02.xsd\">"
                + "<CstmrCdtTrfInitn><GrpHdr><MsgId>M1</MsgId><CreDtTm>2026-11-02T08:00:00</CreDtTm><NbOfTxs>" + count
                + "</NbOfTxs><CtrlSum>" + count + ".00</CtrlSum><InitgPty><Nm>Firma</Nm></InitgPty></GrpHdr>"
                + String.join("", groups) + "</CstmrCdtTrfInitn></Document>";
    }

    private static Session open(String xml) throws Session.RejectedFile {
        return Session.open(xml.getBytes(StandardCharsets.UTF_8), CLOCK);
    }

    private static Session openPayment(String after) throws Session.RejectedFile {
        return open(file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", after))));
    }

    private static Issue issue(Session s, String keyPrefix) {
        return s.result().issues().stream().filter(i -> i.key().startsWith(keyPrefix)).findFirst().orElse(null);
    }

    private static long issues(Session s, String keyPrefix) {
        return s.result().issues().stream().filter(i -> i.key().startsWith(keyPrefix)).count();
    }

    /** Bestätigt alle Punkte ohne Eingabefelder, wie es ein Benutzer einzeln täte. */
    private static void confirmEverything(Session s) {
        s.confirmAllProposals();
        boolean again = true;
        while (again) {
            again = false;
            for (Issue i : s.result().issues()) {
                if (!i.resolved() && i.fields().isEmpty()) {
                    s.decide(i.key(), Decision.confirm());
                    again = true;
                    break;
                }
            }
        }
    }

    private static Document assertExports(Session s) throws Exception {
        confirmEverything(s);
        assertTrue(s.result().ready(), "offen: " + s.result().openCount());
        Session.Export export = s.export();
        assertEquals(List.of(), export.errors());
        return Xml.parse(export.xml());
    }

    private static List<Element> payments(Document d) {
        List<Element> all = new java.util.ArrayList<>();
        for (Element g : Xml.children(Xml.child(d.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf")) {
            all.addAll(Xml.children(g, "CdtTrfTxInf"));
        }
        return all;
    }

    // ---- Datentreue -----------------------------------------------------------------------------------

    private static String creditorPayment(String id, String creditor, String after) {
        return "<CdtTrfTxInf><PmtId><EndToEndId>E" + id + "</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"CHF\">1.00</InstdAmt></Amt><Cdtr>" + creditor + "</Cdtr>" + after + "</CdtTrfTxInf>";
    }

    @Test
    void executionDateWithTimeZoneIsKept() throws Exception {
        Session s = open(file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("<ReqdExctnDt>2026-11-20<", "<ReqdExctnDt>2026-12-01+01:00<"));
        assertNull(issue(s, "DATE"), "Ein künftiges Datum darf nicht als vergangen gelten.");
        Document out = assertExports(s);
        assertEquals("2026-12-01+01:00", Xml.text(Xml.find(Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn"),
                "PmtInf/ReqdExctnDt/Dt")));
    }

    @Test
    void countryGuessedFromIbanIsNotBulkConfirmed() throws Exception {
        String creditor = "<Nm>A</Nm><PstlAdr><StrtNm>Weg</StrtNm><BldgNb>1</BldgNb><PstCd>8001</PstCd>"
                + "<TwnNm>Zürich</TwnNm></PstlAdr>";
        Session s = open(file(1, group("G", "TRF", "", creditorPayment("1", creditor, account(GERMAN_IBAN)))));
        assertEquals(0, s.confirmAllProposals());
        assertEquals(Issue.Severity.OPEN, issue(s, "ADDR|Empfänger").severity());
    }

    @Test
    void sameAccountNumberOfDifferentCreditorsIsAskedSeparately() throws Exception {
        String account = "<CdtrAcct><Id><Othr><Id>12345</Id></Othr></Id></CdtrAcct>";
        Session s = open(file(2, group("G", "TRF", "", creditorPayment("1", "<Nm>A</Nm>" + ADDRESS, account),
                creditorPayment("2", "<Nm>B</Nm>" + ADDRESS, account))));
        assertEquals(2, issues(s, "ACCT|"));
    }

    @Test
    void sameNameWithoutAddressOnOtherAccountIsAskedSeparately() throws Exception {
        Session s = open(file(2, group("G", "TRF", "", creditorPayment("1", "<Nm>Max Muster</Nm>", account(IBAN)),
                creditorPayment("2", "<Nm>Max Muster</Nm>", account(GERMAN_IBAN)))));
        assertEquals(2, issues(s, "ADDR|Empfänger"));
    }

    @Test
    void sameCreditorOnSameAccountIsAskedOnce() throws Exception {
        Session s = open(file(2, group("G", "TRF", "", creditorPayment("1", "<Nm>Max Muster</Nm>", account(IBAN)),
                creditorPayment("2", "<Nm>Max Muster</Nm>", account(IBAN)))));
        assertEquals(1, issues(s, "ADDR|Empfänger"));
    }

    @Test
    void discountAndTaxAmountOfDocumentGetTheNewStructure() throws Exception {
        Session s = openPayment(account(IBAN) + "<RmtInf><Strd><RfrdDocAmt><DscntApldAmt Ccy=\"CHF\">5.00</DscntApldAmt>"
                + "<TaxAmt Ccy=\"EUR\">7.70</TaxAmt></RfrdDocAmt></Strd></RmtInf>");
        Element amounts = Xml.find(payments(assertExports(s)).get(0), "RmtInf/Strd/RfrdDocAmt");
        Element discount = Xml.find(amounts, "DscntApldAmt/Amt");
        Element tax = Xml.find(amounts, "TaxAmt/Amt");
        assertEquals("5.00", Xml.text(discount));
        assertEquals("CHF", discount.getAttribute("Ccy"));
        assertEquals("7.70", Xml.text(tax));
        assertEquals("EUR", tax.getAttribute("Ccy"));
    }

    @Test
    void equivalentAmountIsShownInItsOwnCurrency() throws Exception {
        Session s = open(file(1, group("G", "TRF", "", "<CdtTrfTxInf><PmtId><EndToEndId>E1</EndToEndId></PmtId><Amt><EqvtAmt>"
                + "<Amt Ccy=\"EUR\">1.00</Amt><CcyOfTrf>CHF</CcyOfTrf></EqvtAmt></Amt><Cdtr><Nm>A</Nm>" + ADDRESS + "</Cdtr>"
                + account(IBAN) + "</CdtTrfTxInf>")));
        assertEquals("EUR", s.result().payments().get(0).currency());
        assertEquals(Map.of("EUR", new java.math.BigDecimal("1.00")), s.result().sums());
        assertExports(s);
    }

    @Test
    void softHyphenIsAskedBeforeSaving() throws Exception {
        Session s = openPayment(account(IBAN) + "<RmtInf><Ustrd>Rech\u00ADnung</Ustrd></RmtInf>");
        assertNotNull(issue(s, "TXT|"));
    }

    // ---- Konto ----------------------------------------------------------------------------------------

    @Test
    void paymentsWithoutAccountAreAskedOneByOne() throws Exception {
        Session s = open(file(2, group("G", "TRF", "", payment("1", "Alice", "CHF", "", ""),
                payment("2", "Bob", "CHF", "", ""))));
        assertEquals(2, issues(s, "ACCT|"));
        Issue first = issue(s, "ACCT|");
        assertTrue(first.title().contains("Alice"), first.title());
        assertEquals(1, first.payments().size());

        s.decide(first.key(), Decision.apply(Map.of("iban", IBAN)));
        List<PaymentView> views = s.result().payments();
        assertEquals(IBAN, views.get(0).account());
        assertNull(views.get(1).account());
        assertFalse(s.result().ready());
    }

    @Test
    void qrIbanIsNotAcceptedAsDebtorAccount() throws Exception {
        String xml = file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("CH9300762011623852957", QR_IBAN);
        Session s = open(xml);
        Issue i = issue(s, "DBTRACCT|");
        assertNotNull(i.fields().get(0).validate(QR_IBAN));
        s.decide(i.key(), Decision.apply(Map.of("iban", QR_IBAN)));
        assertFalse(issue(s, "DBTRACCT|").resolved());
    }

    @Test
    void lettersInIidAreNoQrIban() {
        assertFalse(Checksums.isQrIban("CH563A000000000000000"));
    }

    // ---- Referenz -------------------------------------------------------------------------------------

    @Test
    void unchangedInvalidReferenceStaysOpen() throws Exception {
        Session s = openPayment(account(QR_IBAN) + reference(qrType(), WRONG_QR_REFERENCE));
        Issue i = issue(s, "REF|");
        assertEquals(Field.Type.QR_REFERENCE, i.fields().get(0).type());
        assertNotNull(i.fields().get(0).validate(WRONG_QR_REFERENCE));
        assertNull(i.fields().get(0).validate(QR_REFERENCE));

        s.decide(i.key(), Decision.apply(Map.of("ref", WRONG_QR_REFERENCE)));
        assertFalse(issue(s, "REF|").resolved());
        assertFalse(s.result().ready());
        assertFalse(s.export().ok());

        s.decide(i.key(), Decision.apply(Map.of("ref", QR_REFERENCE)));
        assertEquals(QR_REFERENCE, Xml.text(payments(assertExports(s)).get(0), "RmtInf/Strd/CdtrRefInf/Ref"));
    }

    @Test
    void invalidCorrectionBackAndForthDoesNotOverflow() throws Exception {
        String first = "RF00INVALIDA";
        String second = "RF00INVALIDB";
        Session s = openPayment(account(IBAN) + reference(isoType(), first));
        s.decide("REF|" + first + "|false", Decision.apply(Map.of("ref", second)));
        s.decide("REF|" + second + "|false", Decision.apply(Map.of("ref", first)));
        assertFalse(issue(s, "REF|" + first).resolved());
        assertFalse(s.result().ready());
    }

    @Test
    void validReferenceOfForbiddenTypeCanBeKept() throws Exception {
        Session s = openPayment(account(IBAN) + reference("<Tp><CdOrPrtry><Prtry>X</Prtry></CdOrPrtry></Tp>", ISO_REFERENCE));
        Issue i = issue(s, "REF|");
        s.decide(i.key(), Decision.apply(Map.of("ref", ISO_REFERENCE)));
        assertEquals("SCOR", Xml.text(payments(assertExports(s)).get(0), "RmtInf/Strd/CdtrRefInf/Tp/CdOrPrtry/Cd"));
    }

    @Test
    void referenceIsAddedToExistingMessage() throws Exception {
        Session s = openPayment(account(IBAN)
                + "<RmtInf><Ustrd>Text</Ustrd><Strd><CdtrRefInf><Ref>ABC</Ref></CdtrRefInf></Strd></RmtInf>");
        s.decide("REF|ABC|false", Decision.omit());
        assertEquals("Text ABC", Xml.text(payments(assertExports(s)).get(0), "RmtInf/Ustrd"));
    }

    @Test
    void referenceThatDoesNotFitIntoMessageOffersNoAlternative() throws Exception {
        String text = "x".repeat(138);
        Session s = openPayment(account(IBAN) + "<RmtInf><Ustrd>" + text
                + "</Ustrd><Strd><CdtrRefInf><Ref>ABC</Ref></CdtrRefInf></Strd></RmtInf>");
        assertNull(issue(s, "REF|ABC").omitLabel());
    }

    // ---- Sammelbestätigung ----------------------------------------------------------------------------

    @Test
    void bulkConfirmationCoversAddressesOnly() throws Exception {
        String xml = file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("<CtrlSum>1.00</CtrlSum>", "<CtrlSum>999.00</CtrlSum>")
                .replace("<Nm>A</Nm>" + ADDRESS, "<Nm>A</Nm><PstlAdr><AdrLine>Weg 1</AdrLine><AdrLine>CH-8001 Zürich</AdrLine>"
                        + "</PstlAdr>")
                .replace("<Nm>Firma</Nm></InitgPty>", "<Nm>Firma</Nm><Unbekannt>x</Unbekannt></InitgPty>");
        Session s = open(xml);
        assertEquals(1, s.confirmAllProposals());
        assertTrue(issue(s, "ADDR|").resolved());
        for (String key : List.of("SRC_XSD", "SUM_A", "PATH|")) {
            assertEquals(Issue.Severity.CONFIRM, issue(s, key).severity(), key);
            assertFalse(issue(s, key).resolved(), key);
        }
        assertFalse(s.result().ready());
    }

    // ---- Doppelte und ungültige IDs -------------------------------------------------------------------

    @Test
    void duplicateGroupIdCanBeCorrectedAndSaved() throws Exception {
        Session s = open(file(2, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))),
                group("G", "TRF", "", payment("2", "B", "CHF", "", account(IBAN)))));
        s.decide(issue(s, "DUPB|").key(), Decision.apply(Map.of("value", "G2")));
        Document out = assertExports(s);
        List<Element> groups = Xml.children(Xml.child(out.getDocumentElement(), "CstmrCdtTrfInitn"), "PmtInf");
        assertEquals("G2", Xml.text(groups.get(1), "PmtInfId"));
    }

    @Test
    void duplicateStaysOpenUntilTheNewIdIsUnique() throws Exception {
        Session s = open(file(3, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))),
                group("G", "TRF", "", payment("2", "B", "CHF", "", account(IBAN))),
                group("G", "TRF", "", payment("3", "C", "CHF", "", account(IBAN)))));
        s.decide("DUPB|2", Decision.apply(Map.of("value", "G")));
        assertFalse(issue(s, "DUPB|2").resolved());
        s.decide("DUPB|2", Decision.apply(Map.of("value", "X")));
        s.decide("DUPB|3", Decision.apply(Map.of("value", "X")));
        assertTrue(issue(s, "DUPB|2").resolved());
        assertFalse(issue(s, "DUPB|3").resolved());
    }

    @Test
    void decisionOnDuplicateSurvivesExcludingAnEarlierGroup() throws Exception {
        Session s = open(file(3, group("A", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))),
                group("G", "TRF", "", payment("2", "B", "CHF", "", account(IBAN))),
                group("G", "TRF", "", payment("3", "C", "CHF", "", account(IBAN)))));
        s.decide("DUPB|3", Decision.apply(Map.of("value", "G3")));
        s.setExcluded("1.1", true);
        assertTrue(issue(s, "DUPB|3").resolved());
        assertExports(s);
    }

    @Test
    void invalidGroupIdCanBeCorrectedAndSaved() throws Exception {
        Session s = open(file(1, group("G//1", "TRF", "", payment("1", "A", "CHF", "", account(IBAN)))));
        s.decide(issue(s, "TXT|").key(), Decision.apply(Map.of("value", "G1")));
        assertExports(s);
    }

    @Test
    void unchangedInvalidIdStaysOpen() throws Exception {
        Session s = open(file(1, group("G//1", "TRF", "", payment("1", "A", "CHF", "", account(IBAN)))));
        s.decide(issue(s, "TXT|").key(), Decision.apply(Map.of("value", "G//1")));
        assertFalse(issue(s, "TXT|").resolved());
    }

    // ---- Entfallende Elemente -------------------------------------------------------------------------

    @Test
    void chequeWithAccountCanBeSaved() throws Exception {
        Session s = open(file(1, group("G", "CHK", "", payment("1", "A", "CHF", "", account(IBAN)))));
        assertNull(Xml.child(payments(assertExports(s)).get(0), "CdtrAcct"));
    }

    @Test
    void everyForbiddenInstructionIsRemoved() throws Exception {
        Session s = openPayment(account(IBAN) + "<InstrForCdtrAgt><InstrInf>eins</InstrInf></InstrForCdtrAgt>"
                + "<InstrForCdtrAgt><InstrInf>zwei</InstrInf></InstrForCdtrAgt>");
        assertEquals(2, issues(s, "DROP|"));
        assertNull(Xml.child(payments(assertExports(s)).get(0), "InstrForCdtrAgt"));
    }

    @Test
    void groupChargeBearerStaysForPaymentsOtherThanSepa() throws Exception {
        Session s = open(file(2, group("G", "TRF", "<ChrgBr>DEBT</ChrgBr>",
                payment("1", "A", "EUR", SEPA, account(GERMAN_IBAN)), payment("2", "B", "USD", "", account(GERMAN_IBAN)))));
        Document out = assertExports(s);
        assertNull(Xml.child(payments(out).get(0), "ChrgBr"));
        assertEquals("DEBT", Xml.text(payments(out).get(1), "ChrgBr"));
    }

    // ---- Weitere Prüfungen ----------------------------------------------------------------------------

    @Test
    void sepaOutsideEuroNeedsConfirmation() throws Exception {
        Session s = open(file(1, group("G", "TRF", "", payment("1", "A", "CHF", SEPA, account(IBAN)))));
        s.confirmAllProposals();
        assertEquals(Issue.Severity.CONFIRM, issue(s, "SEPA_CCY|CHF").severity());
        assertFalse(s.result().ready());
    }

    @Test
    void newExecutionDateMustNotBeInThePast() throws Exception {
        Session s = open(file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("2026-11-20", "2026-10-01"));
        Issue i = issue(s, "DATE|");
        Field date = i.fields().get(0);
        assertEquals(LocalDate.of(2026, 11, 7), date.notBefore());
        assertNotNull(date.validate("2026-11-06"));
        assertNull(date.validate("2026-11-07"));
        s.decide(i.key(), Decision.apply(Map.of("date", "2026-11-06")));
        assertFalse(issue(s, "DATE|").resolved());
    }

    @Test
    void paymentExcludedThroughIssueCanBeIncludedAgain() throws Exception {
        String wrong = "CH0000000000000000000";
        Session s = open(file(2, group("G", "TRF", "", payment("1", "A", "CHF", "", account(wrong)),
                payment("2", "B", "CHF", "", account(IBAN)))));
        s.decide("IBAN|" + wrong, Decision.exclude());
        assertEquals(PaymentView.Status.EXCLUDED, s.result().payments().get(0).status());
        s.setExcluded("1.1", false);
        assertEquals(PaymentView.Status.OPEN, s.result().payments().get(0).status());
        assertFalse(issue(s, "IBAN|").resolved());
    }

    @Test
    void missingRequiredElementsAreRejectedAtOpen() {
        String valid = file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))));
        for (String part : List.of("<ReqdExctnDt>2026-11-20</ReqdExctnDt>", "<NbOfTxs>1</NbOfTxs>",
                "<CreDtTm>2026-11-02T08:00:00</CreDtTm>", "<InitgPty><Nm>Firma</Nm></InitgPty>",
                "<Amt><InstdAmt Ccy=\"CHF\">1.00</InstdAmt></Amt>")) {
            assertTrue(valid.contains(part), part);
            Session.RejectedFile e = assertThrows(Session.RejectedFile.class, () -> open(valid.replace(part, "")), part);
            assertTrue(e.getMessage().contains("Pflichtelement"), e.getMessage());
        }
        String noHeader = valid.replaceAll("<GrpHdr>.*</GrpHdr>", "");
        assertThrows(Session.RejectedFile.class, () -> open(noHeader));
    }

    @Test
    void initiatingPartyWithoutNameOrIdAsksForName() throws Exception {
        String source = file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("<InitgPty><Nm>Firma</Nm></InitgPty>",
                        "<InitgPty><CtctDtls><Nm>Altsoftware</Nm><Othr>4.2</Othr></CtctDtls></InitgPty>");
        Session s = open(source);
        Issue missing = issue(s, "INITGPTY");
        assertNotNull(missing);
        assertEquals(Issue.Severity.OPEN, missing.severity());
        assertEquals("Firma", missing.fields().get(0).value());
        assertFalse(s.result().ready());

        s.decide("INITGPTY", Decision.apply(Map.of("Nm", "Auftraggeber AG")));
        Element party = Xml.find(assertExports(s).getDocumentElement(), "CstmrCdtTrfInitn/GrpHdr/InitgPty");
        assertEquals("Auftraggeber AG", Xml.text(party, "Nm"));
        assertNull(Xml.child(party, "CtctDtls"));
    }

    @Test
    void namedInitiatingPartyKeepsNameAndLosesSoftwareDetails() throws Exception {
        String source = file(1, group("G", "TRF", "", payment("1", "A", "CHF", "", account(IBAN))))
                .replace("<InitgPty><Nm>Firma</Nm></InitgPty>",
                        "<InitgPty><Nm>Firma</Nm><CtctDtls><Nm>Altsoftware</Nm></CtctDtls></InitgPty>");
        Session s = open(source);
        assertNull(issue(s, "INITGPTY"));
        Element party = Xml.find(assertExports(s).getDocumentElement(), "CstmrCdtTrfInitn/GrpHdr/InitgPty");
        assertEquals("Firma", Xml.text(party, "Nm"));
        assertNull(Xml.child(party, "CtctDtls"));
    }
}
