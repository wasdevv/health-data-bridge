package dev.wasdevv.hdb.core.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CpfTest {

    @Test
    void generatedCpfsAreValidAndDeterministicForASeed() {
        Set<String> seen = new HashSet<>();
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String cpf = Cpf.generate(random);
            assertThat(Cpf.isValid(cpf)).as(cpf).isTrue();
            assertThat(Cpf.isValid(Cpf.format(cpf))).isTrue();
            seen.add(cpf);
        }
        assertThat(seen).hasSizeGreaterThan(990);
        assertThat(Cpf.generate(new Random(7))).isEqualTo(Cpf.generate(new Random(7)));
    }

    @Test
    void rejectsWrongCheckDigitsAndRepeatedDigits() {
        String cpf = Cpf.generate(new Random(1));
        String wrong = cpf.substring(0, 10) + ((cpf.charAt(10) - '0' + 1) % 10);

        assertThat(Cpf.isValid(wrong)).isFalse();
        assertThat(Cpf.isValid("11111111111")).isFalse();
        assertThat(Cpf.isValid("123")).isFalse();
        assertThat(Cpf.isValid(null)).isFalse();
    }

    @Test
    void formatsWithPunctuation() {
        assertThat(Cpf.format("12345678909")).isEqualTo("123.456.789-09");
    }
}
