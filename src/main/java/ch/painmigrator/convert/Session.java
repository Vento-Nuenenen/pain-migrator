package ch.painmigrator.convert;

import ch.painmigrator.convert.Converter.Result;
import ch.painmigrator.model.Decision;
import ch.painmigrator.model.Issue;
import ch.painmigrator.model.PaymentView;
import ch.painmigrator.validate.RoundTripCheck;
import ch.painmigrator.validate.Schemas;
import ch.painmigrator.xml.Xml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Eine geöffnete Datei mit den bisherigen Entscheidungen des Benutzers. */
public final class Session {

    /** Die Datei lässt sich nicht verarbeiten. Die Meldung ist für den Benutzer bestimmt. */
    public static final class RejectedFile extends Exception {
        private static final long serialVersionUID = 1L;

        public RejectedFile(String message) {
            super(message);
        }
    }

    /** Fertige Datei und das Ergebnis der Schlussprüfung. */
    public record Export(byte[] xml, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final Document source;
    private final List<PaymentView> originals;
    private final List<String> sourceErrors;
    private final Clock clock;
    private final Map<String, Decision> decisions = new HashMap<>();
    private Result result;

    private Session(Document source, List<PaymentView> originals, List<String> sourceErrors, Clock clock) {
        this.source = source;
        this.originals = originals;
        this.sourceErrors = sourceErrors;
        this.clock = clock;
        refresh();
    }

    public static Session open(Path file) throws RejectedFile, IOException {
        return open(Files.readAllBytes(file), Clock.systemDefaultZone());
    }

    public static Session open(byte[] data, Clock clock) throws RejectedFile {
        Document doc;
        try {
            doc = Xml.parse(data);
        } catch (SAXException e) {
            throw new RejectedFile("Die Datei ist kein gültiges XML oder enthält eine nicht erlaubte DOCTYPE-Angabe.\n"
                    + e.getMessage());
        }
        String ns = doc.getDocumentElement().getNamespaceURI();
        if (Converter.NS_NEW.equals(ns)) {
            throw new RejectedFile("Die Datei liegt bereits im Format pain.001.001.09 vor.");
        }
        if (!Converter.NS_OLD.equals(ns)) {
            throw new RejectedFile("Das Format wird nicht unterstützt. Erwartet wird pain.001.001.03.ch.02.\n"
                    + "Gefundener Namespace: " + ns);
        }
        if (Views.root(doc) == null) {
            throw new RejectedFile("Die Datei enthält kein Element CstmrCdtTrfInitn.");
        }
        try {
            return new Session(doc, Views.read(doc), Schemas.validate(Schemas.SOURCE, data), clock);
        } catch (NumberFormatException e) {
            throw new RejectedFile("Die Datei enthält einen Betrag, der keine gültige Zahl ist.");
        }
    }

    private void refresh() {
        result = Converter.convert(source, decisions, LocalDateTime.now(clock), sourceErrors, version());
    }

    private static String version() {
        try (InputStream in = Session.class.getResourceAsStream("/version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            String v = p.getProperty("version", "");
            return v.isEmpty() || v.startsWith("$") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }

    public Result result() {
        return result;
    }

    public List<PaymentView> originals() {
        return originals;
    }

    public void decide(String key, Decision decision) {
        decisions.put(key, decision);
        refresh();
    }

    public void undo(String key) {
        decisions.remove(key);
        refresh();
    }

    public void setExcluded(String paymentId, boolean excluded) {
        if (excluded) {
            decisions.put(Converter.excludeKey(paymentId), Decision.exclude());
        } else {
            decisions.remove(Converter.excludeKey(paymentId));
        }
        refresh();
    }

    /** Bestätigt alle Vorschläge. Spätere Punkte, die dadurch erst sichtbar werden, gehören dazu. */
    public int confirmAllProposals() {
        int confirmed = 0;
        boolean again = true;
        while (again) {
            again = false;
            for (Issue i : new ArrayList<>(result.issues())) {
                if (i.severity() == Issue.Severity.REVIEW && !i.resolved()) {
                    decisions.put(i.key(), Decision.confirm());
                    confirmed++;
                    again = true;
                }
            }
            if (again) {
                refresh();
            }
        }
        return confirmed;
    }

    /** Erzeugt die Datei und führt die Schlussprüfung auf den fertigen Bytes aus. */
    public Export export() {
        if (!result.ready()) {
            return new Export(new byte[0], List.of("Es sind noch Punkte offen oder keine Zahlungen enthalten."));
        }
        byte[] xml = Xml.serialize(result.output());
        List<String> errors = new ArrayList<>(Schemas.validate(Schemas.TARGET, xml));
        if (errors.isEmpty()) {
            try {
                Set<String> excluded = new HashSet<>();
                for (PaymentView p : result.payments()) {
                    if (p.status() == PaymentView.Status.EXCLUDED) {
                        excluded.add(p.id());
                    }
                }
                errors.addAll(RoundTripCheck.compare(originals, excluded, result.changes(), Xml.parse(xml)));
            } catch (SAXException e) {
                errors.add("Die erzeugte Datei ist nicht lesbar: " + e.getMessage());
            }
        }
        return new Export(xml, errors);
    }
}
