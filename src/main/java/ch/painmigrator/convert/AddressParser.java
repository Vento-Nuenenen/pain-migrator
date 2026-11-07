package ch.painmigrator.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt unstrukturierte Adresszeilen in strukturierte Felder.
 *
 * <p>Das Ergebnis ist immer nur ein Vorschlag. {@code confident} sagt, ob er so eindeutig ist, dass eine
 * Kontrolle genügt, oder ob der Benutzer die Felder selbst prüfen und ergänzen muss.
 */
public final class AddressParser {

    public record Parsed(String street, String building, String postBox, String postCode, String town,
            String country, String extra, boolean confident) {
    }

    public record Street(String name, String number) {
    }

    private static final Map<String, String> COUNTRY_PREFIX = Map.ofEntries(
            Map.entry("CH", "CH"), Map.entry("LI", "LI"), Map.entry("FL", "LI"), Map.entry("D", "DE"),
            Map.entry("DE", "DE"), Map.entry("A", "AT"), Map.entry("AT", "AT"), Map.entry("F", "FR"),
            Map.entry("FR", "FR"), Map.entry("I", "IT"), Map.entry("IT", "IT"));

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            Map.entry("schweiz", "CH"), Map.entry("suisse", "CH"), Map.entry("svizzera", "CH"),
            Map.entry("switzerland", "CH"), Map.entry("liechtenstein", "LI"), Map.entry("deutschland", "DE"),
            Map.entry("germany", "DE"), Map.entry("österreich", "AT"), Map.entry("oesterreich", "AT"),
            Map.entry("austria", "AT"), Map.entry("frankreich", "FR"), Map.entry("france", "FR"),
            Map.entry("italien", "IT"), Map.entry("italia", "IT"), Map.entry("italy", "IT"));

    private static final Map<String, String> POST_CODE_FORMAT = Map.of(
            "CH", "\\d{4}", "LI", "\\d{4}", "AT", "\\d{4}", "DE", "\\d{5}", "FR", "\\d{5}", "IT", "\\d{5}");

    private static final Pattern TOWN_LINE =
            Pattern.compile("^(?:([A-Za-z]{1,2})\\s*-\\s*)?(\\d{4,5})\\s+(\\S.*)$");
    private static final Pattern POST_BOX = Pattern.compile(
            "^(postfach|case postale|casella postale|cp|p\\.?\\s?o\\.?\\s?box|pf)\\b\\.?\\s*\\d*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_LAST =
            Pattern.compile("^(.*\\D)\\s+(\\d+\\s?[a-zA-Z]?(?:[-/]\\d+\\s?[a-zA-Z]?)?)$");
    private static final Pattern NUMBER_FIRST =
            Pattern.compile("^(\\d+\\s?[a-zA-Z]?)\\s*,?\\s+(\\p{L}.*)$");

    private AddressParser() {
    }

    /** Trennt Strassenname und Hausnummer. Liefert null, wenn keine Hausnummer erkennbar ist. */
    public static Street splitStreet(String street) {
        if (street == null) {
            return null;
        }
        String s = street.trim();
        Matcher last = NUMBER_LAST.matcher(s);
        if (last.matches()) {
            return new Street(last.group(1).trim().replaceAll(",$", ""), last.group(2).replace(" ", ""));
        }
        Matcher first = NUMBER_FIRST.matcher(s);
        if (first.matches()) {
            return new Street(first.group(2).trim(), first.group(1).replace(" ", ""));
        }
        return null;
    }

    /**
     * @param lines Inhalt der AdrLine-Elemente
     * @param knownCountry Land aus dem Feld Ctry der Quelldatei, oder null
     * @param fallbackCountry Vermutung ohne Gewähr, etwa aus der IBAN, oder null
     */
    public static Parsed parse(List<String> lines, String knownCountry, String fallbackCountry) {
        List<String> rest = new ArrayList<>();
        for (String line : lines) {
            for (String part : line.split(",")) {
                if (!part.isBlank()) {
                    rest.add(part.trim().replaceAll("\\s+", " "));
                }
            }
        }
        // Eine Hausnummer vorne mit Komma ("12, rue de la Gare") wurde oben getrennt und wird wieder verbunden.
        for (int i = 0; i + 1 < rest.size(); i++) {
            if (rest.get(i).matches("\\d+\\s?[a-zA-Z]?") && !TOWN_LINE.matcher(rest.get(i + 1)).matches()) {
                rest.set(i, rest.get(i) + " " + rest.remove(i + 1));
            }
        }

        String country = knownCountry;
        boolean confident = true;

        if (!rest.isEmpty()) {
            String named = COUNTRY_NAMES.get(rest.get(rest.size() - 1).toLowerCase(Locale.ROOT));
            if (named != null) {
                rest.remove(rest.size() - 1);
                confident &= country == null || country.equals(named);
                country = country == null ? named : country;
            }
        }

        String postCode = null;
        String town = null;
        if (!rest.isEmpty()) {
            Matcher m = TOWN_LINE.matcher(rest.get(rest.size() - 1));
            if (m.matches()) {
                rest.remove(rest.size() - 1);
                postCode = m.group(2);
                town = m.group(3).trim();
                if (m.group(1) != null) {
                    String prefix = COUNTRY_PREFIX.get(m.group(1).toUpperCase(Locale.ROOT));
                    if (prefix == null) {
                        confident = false;
                    } else {
                        confident &= country == null || country.equals(prefix);
                        country = country == null ? prefix : country;
                    }
                }
            } else if (rest.size() == 1 && rest.get(0).matches("[\\p{L} .'-]+")) {
                // Nur eine Zeile ohne Ziffern: das ist am ehesten der Ort.
                town = rest.remove(0);
            }
        }

        if (country == null) {
            country = fallbackCountry;
            confident = false;
        }
        if (town == null) {
            confident = false;
        }
        if (postCode != null && country != null) {
            String format = POST_CODE_FORMAT.get(country);
            confident &= format != null && postCode.matches(format);
        }

        String street = null;
        String building = null;
        String postBox = null;
        List<String> extra = new ArrayList<>();
        for (String line : rest) {
            if (POST_BOX.matcher(line).matches() && postBox == null) {
                postBox = line;
            } else if (street == null && !line.toLowerCase(Locale.ROOT).startsWith("c/o")) {
                Street split = splitStreet(line);
                street = split == null ? line : split.name();
                building = split == null ? null : split.number();
            } else {
                extra.add(line);
            }
        }
        confident &= extra.isEmpty();

        return new Parsed(street, building, postBox, postCode, town, country,
                extra.isEmpty() ? null : String.join(", ", extra), confident);
    }
}
