package ch.painmigrator.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ein Punkt, den die Umwandlung nicht allein entscheiden kann oder melden muss.
 *
 * <p>Der Schlüssel wird aus Quelldaten gebildet. Dadurch bleibt er über wiederholte Läufe stabil, und
 * gleiche Fälle (etwa derselbe Empfänger in mehreren Zahlungen) erscheinen nur einmal.
 */
public final class Issue {

    public enum Severity {
        /** Eine Eingabe ist zwingend nötig. */
        OPEN,
        /** Es gibt einen Vorschlag, der bestätigt werden muss. */
        REVIEW,
        /** Reine Information, keine Aktion nötig. */
        INFO
    }

    private final String key;
    private final Severity severity;
    private final String title;
    private final String detail;
    private final List<Field> fields;
    private final String omitLabel;
    private final boolean excludable;
    private final List<String> payments = new ArrayList<>();
    private final Set<String> paymentIds = new HashSet<>();
    private Decision decision;

    public Issue(String key, Severity severity, String title, String detail, List<Field> fields,
            String omitLabel, boolean excludable) {
        this.key = key;
        this.severity = severity;
        this.title = title;
        this.detail = detail;
        this.fields = List.copyOf(fields);
        this.omitLabel = omitLabel;
        this.excludable = excludable;
    }

    public String key() {
        return key;
    }

    public Severity severity() {
        return severity;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public List<Field> fields() {
        return fields;
    }

    /** Beschriftung der Alternative, oder null, wenn es keine gibt. */
    public String omitLabel() {
        return omitLabel;
    }

    public boolean excludable() {
        return excludable;
    }

    /** IDs der betroffenen Zahlungen. Leer, wenn der Punkt die ganze Datei betrifft. */
    public List<String> payments() {
        return payments;
    }

    /** Vermerkt eine betroffene Zahlung. Jede Zahlung zählt nur einmal. */
    public void addPayment(String id) {
        if (paymentIds.add(id)) {
            payments.add(id);
        }
    }

    public Decision decision() {
        return decision;
    }

    public void decision(Decision d) {
        this.decision = d;
    }

    public boolean resolved() {
        return severity == Severity.INFO || decision != null;
    }
}
