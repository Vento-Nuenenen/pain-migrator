package ch.painmigrator.convert;

import ch.painmigrator.model.PaymentView;
import ch.painmigrator.xml.Xml;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Liest Zahlungen aus altem oder neuem Format in die Vorschau-Darstellung. */
public final class Views {
    private Views() {
    }

    public static Element root(Document doc) {
        return Xml.child(doc.getDocumentElement(), "CstmrCdtTrfInitn");
    }

    public static List<PaymentView> read(Document doc) {
        List<PaymentView> out = new ArrayList<>();
        int i = 0;
        for (Element group : Xml.children(root(doc), "PmtInf")) {
            i++;
            int j = 0;
            for (Element tx : Xml.children(group, "CdtTrfTxInf")) {
                j++;
                out.add(of(i + "." + j, group, tx, null, null, PaymentView.Status.OK));
            }
        }
        return out;
    }

    static PaymentView of(String id, Element group, Element tx, String oldType, String newType,
            PaymentView.Status status) {
        Element amount = amountElement(tx);
        return new PaymentView(id, Xml.text(group, "PmtInfId"), Xml.text(tx, "PmtId/EndToEndId"),
                Xml.text(tx, "PmtId/InstrId"), oldType, newType, Xml.text(tx, "Cdtr/Nm"),
                address(Xml.find(tx, "Cdtr/PstlAdr")), account(tx), amount(tx),
                currency(tx, amount), executionDate(group), reference(tx), status);
    }

    private static Element amountElement(Element tx) {
        Element a = Xml.find(tx, "Amt/InstdAmt");
        return a != null ? a : Xml.find(tx, "Amt/EqvtAmt/Amt");
    }

    /** Betrag der Zahlung. Massgebend für Kontrollsummen laut SPS: Instructed oder Equivalent Amount. */
    static BigDecimal amount(Element tx) {
        Element a = amountElement(tx);
        return a == null ? BigDecimal.ZERO : new BigDecimal(a.getTextContent().trim());
    }

    private static String currency(Element tx, Element amount) {
        if (amount == null) {
            return "";
        }
        return "InstdAmt".equals(amount.getLocalName()) ? amount.getAttribute("Ccy")
                : Xml.text(tx, "Amt/EqvtAmt/CcyOfTrf");
    }

    private static String account(Element tx) {
        String iban = Xml.text(tx, "CdtrAcct/Id/IBAN");
        return iban != null ? iban : Xml.text(tx, "CdtrAcct/Id/Othr/Id");
    }

    private static String executionDate(Element group) {
        Element d = Xml.child(group, "ReqdExctnDt");
        if (d == null) {
            return null;
        }
        Element date = Xml.child(d, "Dt");
        Element dateTime = Xml.child(d, "DtTm");
        return date != null ? Xml.text(date) : dateTime != null ? Xml.text(dateTime) : Xml.text(d);
    }

    private static String reference(Element tx) {
        String ref = Xml.text(tx, "RmtInf/Strd/CdtrRefInf/Ref");
        return ref != null ? ref : Xml.text(tx, "RmtInf/Ustrd");
    }

    private static String address(Element adr) {
        if (adr == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Element e : Xml.children(adr)) {
            if (!"AdrTp".equals(e.getLocalName())) {
                parts.add(Xml.text(e));
            }
        }
        return String.join(" ", parts);
    }
}
