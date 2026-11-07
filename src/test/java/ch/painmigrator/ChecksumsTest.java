package ch.painmigrator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.painmigrator.validate.Checksums;
import org.junit.jupiter.api.Test;

class ChecksumsTest {

    @Test
    void validIbansPass() {
        assertTrue(Checksums.isValidIban("CH93 0076 2011 6238 5295 7"));
        assertTrue(Checksums.isValidIban("DE89370400440532013000"));
    }

    @Test
    void wrongCheckDigitFails() {
        assertFalse(Checksums.isValidIban("CH9400762011623852957"));
    }

    @Test
    void swissIbanNeedsExactLength() {
        assertFalse(Checksums.isValidIban("CH930076201162385295"));
    }

    @Test
    void qrIbanIsRecognisedByIid() {
        assertTrue(Checksums.isQrIban("CH4431999123000889012"));
        assertFalse(Checksums.isQrIban("CH9300762011623852957"));
    }

    @Test
    void qrReferenceCheckDigit() {
        assertTrue(Checksums.isValidQrReference("210000000003139471430009017"));
        assertFalse(Checksums.isValidQrReference("210000000003139471430009018"));
        assertFalse(Checksums.isValidQrReference("12345"));
    }

    @Test
    void creditorReferenceCheckDigit() {
        assertTrue(Checksums.isValidCreditorReference("RF18539007547034"));
        assertFalse(Checksums.isValidCreditorReference("RF19539007547034"));
        assertFalse(Checksums.isValidCreditorReference("XX18539007547034"));
    }
}
