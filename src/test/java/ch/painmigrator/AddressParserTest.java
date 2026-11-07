package ch.painmigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.painmigrator.convert.AddressParser;
import ch.painmigrator.convert.AddressParser.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressParserTest {

    @Test
    void swissAddressInTwoLines() {
        Parsed p = AddressParser.parse(List.of("Bahnhofstrasse 12", "8001 Zürich"), "CH", null);
        assertEquals("Bahnhofstrasse", p.street());
        assertEquals("12", p.building());
        assertEquals("8001", p.postCode());
        assertEquals("Zürich", p.town());
        assertEquals("CH", p.country());
        assertTrue(p.confident());
    }

    @Test
    void countryPrefixInTownLine() {
        Parsed p = AddressParser.parse(List.of("Rosenauweg 4", "DE-80036 München"), null, null);
        assertEquals("DE", p.country());
        assertEquals("80036", p.postCode());
        assertEquals("München", p.town());
        assertTrue(p.confident());
    }

    @Test
    void liechtensteinPrefixFl() {
        Parsed p = AddressParser.parse(List.of("Städtle 1", "FL-9490 Vaduz"), null, null);
        assertEquals("LI", p.country());
        assertTrue(p.confident());
    }

    @Test
    void countryNameInLastLine() {
        Parsed p = AddressParser.parse(List.of("Hauptstrasse 5", "6300 Zug", "Schweiz"), null, null);
        assertEquals("CH", p.country());
        assertEquals("Zug", p.town());
        assertTrue(p.confident());
    }

    @Test
    void singleLineWithComma() {
        Parsed p = AddressParser.parse(List.of("Bahnhofstrasse 12, 8001 Zürich"), "CH", null);
        assertEquals("Bahnhofstrasse", p.street());
        assertEquals("Zürich", p.town());
    }

    @Test
    void numberAddition() {
        Parsed p = AddressParser.parse(List.of("Dorfstrasse 7a", "3000 Bern"), "CH", null);
        assertEquals("Dorfstrasse", p.street());
        assertEquals("7a", p.building());
    }

    @Test
    void frenchNumberFirst() {
        Parsed p = AddressParser.parse(List.of("12, rue de la Gare", "1200 Genève"), "CH", null);
        assertEquals("rue de la Gare", p.street());
        assertEquals("12", p.building());
        assertEquals("Genève", p.town());
    }

    @Test
    void postBox() {
        Parsed p = AddressParser.parse(List.of("Postfach 123", "3000 Bern"), "CH", null);
        assertEquals("Postfach 123", p.postBox());
        assertNull(p.street());
        assertTrue(p.confident());
    }

    @Test
    void streetNameWithDigitsKeepsThem() {
        Parsed p = AddressParser.parse(List.of("Strasse des 17. Juni 135", "10623 Berlin"), "DE", null);
        assertEquals("Strasse des 17. Juni", p.street());
        assertEquals("135", p.building());
    }

    @Test
    void townOnlyWithKnownCountry() {
        Parsed p = AddressParser.parse(List.of("SELDWYLA"), "CH", null);
        assertEquals("SELDWYLA", p.town());
        assertTrue(p.confident());
    }

    @Test
    void careOfIsNotGuessedAsStreet() {
        Parsed p = AddressParser.parse(List.of("c/o Muster AG", "Bahnhofstrasse 1", "8001 Zürich"), "CH", null);
        assertEquals("Bahnhofstrasse", p.street());
        assertEquals("c/o Muster AG", p.extra());
        assertFalse(p.confident());
    }

    @Test
    void countryFromIbanIsOnlyAGuess() {
        Parsed p = AddressParser.parse(List.of("Bahnhofstrasse 1", "8001 Zürich"), null, "CH");
        assertEquals("CH", p.country());
        assertFalse(p.confident());
    }

    @Test
    void postCodeNotMatchingCountryIsUnsure() {
        Parsed p = AddressParser.parse(List.of("Hauptstrasse 1", "80036 München"), "CH", null);
        assertFalse(p.confident());
    }

    @Test
    void contradictingCountriesAreUnsure() {
        Parsed p = AddressParser.parse(List.of("Rosenauweg 4", "DE-80036 München"), "CH", null);
        assertFalse(p.confident());
    }

    @Test
    void unknownFormatIsUnsure() {
        Parsed p = AddressParser.parse(List.of("Irgendwo 3 Etage 2", "unleserlich 99"), null, null);
        assertFalse(p.confident());
    }

    @Test
    void splitStreetWithoutNumber() {
        assertNull(AddressParser.splitStreet("Rosenauweg"));
    }
}
