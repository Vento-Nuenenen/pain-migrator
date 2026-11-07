# PAIN-Migrator

Wandelt Zahlungsdateien von `pain.001.001.03.ch.02` nach `pain.001.001.09` um und prüft sie gegen das Schweizer Schema `pain.001.001.09.ch.03`. Regelgrundlage sind die Swiss Implementation Guidelines Credit Transfer, Version 2.3 (SPS 2026, gültig ab 14.11.2026).

Das Programm läuft lokal, baut keine Netzwerkverbindungen auf und speichert keine Zahlungsdaten außerhalb der von dir gewählten Ausgabedatei.

## Starten

Voraussetzung ist Java 17 oder neuer. Das Programm selbst wird nicht installiert.

```
java -jar pain-migrator.jar
```

Je nach System genügt auch ein Doppelklick auf die Datei. Optional kann eine Datei direkt mitgegeben werden: `java -jar pain-migrator.jar zahlungen.xml`.

## Ablauf

1. Datei öffnen oder ins Fenster ziehen. Andere Formate werden mit einer Meldung abgelehnt.
2. Die Vorschau zeigt alle Zahlungen mit Status, Zahlungsart alt → neu, Empfänger, Konto, Betrag und Referenz.
3. Rückfragen in der unteren Liste per Doppelklick beantworten. Gleiche Fälle erscheinen nur einmal, etwa derselbe Empfänger in mehreren Zahlungen.
4. Eindeutige Vorschläge lassen sich gesammelt mit «Alle Vorschläge bestätigen» übernehmen. Prüfe sie vorher in der Vorschau.
5. Einzelne Zahlungen können über die Spalte «Aufnehmen» ausgeschlossen werden.
6. «Speichern» wird aktiv, sobald alles geklärt ist. Davor läuft eine Schlussprüfung. Schlägt sie fehl, wird nichts gespeichert.

## Was automatisch geschieht

| Bereich | Umwandlung |
|---|---|
| Namespace | auf `urn:iso:std:iso:20022:tech:xsd:pain.001.001.09` |
| Ausführungsdatum | `ReqdExctnDt` wird zu `ReqdExctnDt/Dt` |
| Banken | `BIC` wird zu `BICFI`, `BICOrBEI` zu `AnyBIC` |
| Zahlungsarten | 3 → D, 4 → X, 5 → S, 6 → X, 8 → C, nach dem Flussdiagramm der IG 2.3 |
| Referenzen | fehlender Referenztyp wird bei eindeutiger QR- oder ISO-Referenz ergänzt |
| Methode | `TRA` wird zu `TRF` |
| Software-Angaben | `CtctDtls` beschreibt danach dieses Programm (`NAME`, `VRSN`, `SPSV`) |
| Entfernt | `AdrTp`, `CH01` bis `CH03`, bei D und S Name und Adresse der Empfängerbank |

`MsgId`, `PmtInfId`, `InstrId` und `EndToEndId` bleiben unverändert. Banken prüfen die `MsgId` auf Duplikate. Wurde die alte Datei bereits eingereicht, wird die neue deshalb als Duplikat abgelehnt, was eine Doppelzahlung verhindert. `CreDtTm` wird neu gesetzt.

## Wann das Programm nachfragt

| Fall | Rückfrage |
|---|---|
| Adresse nur in `AdrLine` | Vorschlag für Strasse, Hausnummer, PLZ, Ort, Land. Unklare Fälle müssen ergänzt werden. |
| Empfänger ohne Adresse oder Name | Eingabe, oder bewusst ohne Adresse weiter. Die Bank kann dann ablehnen. |
| ESR (Zahlungsart 1) | QR-IBAN aus der aktuellen QR-Rechnung, außerdem Name und Adresse des Empfängers. Die Referenz wird als QR-Referenz übernommen und muss gegen die Rechnung geprüft werden. |
| ES (Zahlungsart 2.1, 2.2) | IBAN des Empfängers, oder Bestätigung, falls schon vorhanden |
| Konto ohne IBAN und ohne IID/BIC | IBAN |
| Ungültige IBAN oder Referenz | Korrektur |
| Ausführungsdatum in der Vergangenheit | neues Datum |
| Falsche Anzahl oder Kontrollsumme in der Quelle | ausdrückliche Bestätigung, nie stille Korrektur |
| Element ohne Entsprechung im Zielformat | Anzeige mit Pfad und Inhalt, Entfernen nur nach Bestätigung |
| Unerlaubte Zeichen, doppelte IDs | Korrektur |

## Schlussprüfung vor dem Speichern

- Die erzeugten Bytes werden gegen das offizielle `pain.001.001.09.ch.03.xsd` geprüft.
- Die fertige Datei wird neu eingelesen und mit dem Original verglichen. Betrag und Währung dürfen sich nie ändern. Konto, Referenz, Datum, Empfänger und IDs nur durch eine Benutzereingabe.
- Anzahl und Kontrollsumme werden nachgerechnet.

## Grenzen

- **Einreichung testen:** Banken ergänzen eigene Regeln. Reiche eine erzeugte Datei zuerst im Testkanal deiner Bank ein.
- **Hybride Adressen** werden nicht erzeugt, die Ausgabe ist immer strukturiert.
- **Adressvorschläge** sind für CH, LI, DE, AT, FR und IT ausgelegt. Andere Länder werden fast immer abgefragt.
- **Nicht geprüft:** die Grenze von 140 Zeichen für `Strd` bei SEPA, bankspezifische Betragsgrenzen, unterstützte Währungen pro Bank.
- **Nicht unterstützt:** andere Quellformate als `pain.001.001.03.ch.02`, zum Beispiel generisches ISO `pain.001.001.03`.

## Entwicklung

```
./mvnw verify
```

Das fertige Programm liegt danach unter `target/pain-migrator.jar`. Es hat keine Laufzeit-Abhängigkeiten, die Oberfläche nutzt Swing aus dem JDK.

| Paket | Inhalt |
|---|---|
| `convert` | `Converter` als reine Funktion aus Quelle und Entscheidungen, dazu `Session`, `Classifier`, `AddressParser` |
| `validate` | Schemas, Prüfziffern, Vergleich alt gegen neu |
| `model` | Rückfragen, Entscheidungen, Vorschau |
| `ui` | Swing-Oberfläche |

`src/main/resources/xsd` enthält die unveränderten Schemas von SIX. Die Beispiele `pain_001_Beispiel_*.xml` in den Testdaten stammen ebenfalls von SIX, `alle_zahlungsarten.xml` ist erfunden.

## Quellen

- [SIX Download Center](https://www.six-group.com/en/products-services/banking-services/payment-standardization/downloads-faq/download-center.html)
- [Implementation Guidelines Credit Transfer SPS 2026](https://www.six-group.com/dam/download/banking-services/standardization/sps/ig-credit-transfer-sps-2026-en.pdf)
- [Merkblatt Adressen und Meldungsversionen](https://www.six-group.com/dam/download/banking-services/standardization/sps/factsheet-address-messageversion-erp-de.pdf)
