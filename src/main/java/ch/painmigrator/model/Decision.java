package ch.painmigrator.model;

import java.util.Map;

/** Antwort des Benutzers auf eine Rückfrage. */
public record Decision(Action action, Map<String, String> values) {

    public enum Action {
        /** Eingaben beziehungsweise Vorschlag übernehmen. */
        APPLY,
        /** Die in {@link Issue#omitLabel()} beschriebene Alternative wählen. */
        OMIT,
        /** Alle betroffenen Zahlungen aus der Datei ausschliessen. */
        EXCLUDE
    }

    public static Decision apply(Map<String, String> values) {
        return new Decision(Action.APPLY, Map.copyOf(values));
    }

    public static Decision confirm() {
        return new Decision(Action.APPLY, Map.of());
    }

    public static Decision omit() {
        return new Decision(Action.OMIT, Map.of());
    }

    public static Decision exclude() {
        return new Decision(Action.EXCLUDE, Map.of());
    }

    public String value(String name) {
        String v = values.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
