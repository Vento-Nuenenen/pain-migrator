package ch.painmigrator.model;

import ch.painmigrator.xml.Xml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

/**
 * Hält jede gewollte Änderung an den Daten fest, Wert für Wert.
 *
 * <p>Die Umwandlung trägt hier ein, was sie entfernt oder ergänzt. Die Schlussprüfung rechnet das Protokoll
 * auf die Quelle an und erwartet exakt die Ausgabe. Eine Änderung ohne Eintrag fällt deshalb auf.
 *
 * <p>Ein Bereich ist der Kopf ({@link #HEADER}), eine Gruppe ({@link #group}) oder eine Zahlung (ihre ID).
 */
public final class ChangeLog {
    public static final String HEADER = "Kopf";

    /** Ein Wert mit Pfad relativ zu seinem Bereich. */
    public record Leaf(String path, String text, String attributes) {
    }

    private final Map<String, Map<Leaf, Integer>> changes = new HashMap<>();
    private final Map<String, Set<String>> rewritten = new HashMap<>();

    public static String group(int index) {
        return "Gruppe " + index;
    }

    /** Alle Werte unter einem Element. Leere Elemente tragen keine Daten und zählen nicht. */
    public static List<Leaf> leaves(Element e, String path) {
        List<Leaf> found = new ArrayList<>();
        collect(e, path, Set.of(), found);
        return found;
    }

    /** Wie {@link #leaves(Element, String)}, lässt aber direkte und tiefere Kinder mit diesen Namen aus. */
    public static List<Leaf> leaves(Element e, String path, Set<String> skipped) {
        List<Leaf> found = new ArrayList<>();
        collect(e, path, skipped, found);
        return found;
    }

    private static void collect(Element e, String path, Set<String> skipped, List<Leaf> found) {
        List<Element> kids = Xml.children(e);
        if (!kids.isEmpty()) {
            for (Element k : kids) {
                if (!skipped.contains(k.getLocalName())) {
                    collect(k, path.isEmpty() ? k.getLocalName() : path + "/" + k.getLocalName(), skipped, found);
                }
            }
            return;
        }
        String text = e.getTextContent().trim();
        String attributes = attributes(e);
        if (!text.isEmpty() || !attributes.isEmpty()) {
            found.add(new Leaf(path, text, attributes));
        }
    }

    private static String attributes(Element e) {
        NamedNodeMap all = e.getAttributes();
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < all.getLength(); i++) {
            Attr a = (Attr) all.item(i);
            if (a.getNamespaceURI() == null) {
                sorted.put(a.getName(), a.getValue());
            }
        }
        return sorted.isEmpty() ? "" : sorted.toString();
    }

    public void removed(String scope, Leaf leaf) {
        changes.computeIfAbsent(scope, k -> new HashMap<>()).merge(leaf, -1, Integer::sum);
    }

    public void added(String scope, Leaf leaf) {
        changes.computeIfAbsent(scope, k -> new HashMap<>()).merge(leaf, 1, Integer::sum);
    }

    /** Vermerkt, dass ein bestätigter Vorschlag die Adresse neu aufgeteilt hat. Ihr Inhalt muss erhalten bleiben. */
    public void rewritten(String scope, String addressPath) {
        rewritten.computeIfAbsent(scope, k -> new HashSet<>()).add(addressPath);
    }

    /** Pro Wert die Änderung seiner Anzahl: negativ entfernt, positiv ergänzt. */
    public Map<Leaf, Integer> changes(String scope) {
        return changes.getOrDefault(scope, Map.of());
    }

    public Set<String> rewritten(String scope) {
        return rewritten.getOrDefault(scope, Set.of());
    }
}
