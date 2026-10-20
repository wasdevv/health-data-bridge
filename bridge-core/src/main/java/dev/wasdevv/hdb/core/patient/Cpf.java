package dev.wasdevv.hdb.core.patient;

import java.util.random.RandomGenerator;

/**
 * CPF check digits. The seed and the test fixtures generate CPFs here, so every CPF in this repo is
 * synthetic: valid check digits, drawn from a seeded random, never typed in from a real document.
 */
public final class Cpf {

    private Cpf() {
    }

    public static String generate(RandomGenerator random) {
        int[] digits = new int[11];
        do {
            for (int i = 0; i < 9; i++) {
                digits[i] = random.nextInt(10);
            }
        } while (allSame(digits, 9));
        digits[9] = checkDigit(digits, 9);
        digits[10] = checkDigit(digits, 10);
        StringBuilder sb = new StringBuilder(11);
        for (int d : digits) {
            sb.append(d);
        }
        return sb.toString();
    }

    public static boolean isValid(String cpf) {
        if (cpf == null) {
            return false;
        }
        String plain = cpf.replaceAll("[.-]", "");
        if (!plain.matches("\\d{11}")) {
            return false;
        }
        int[] digits = plain.chars().map(c -> c - '0').toArray();
        return !allSame(digits, 11)
                && digits[9] == checkDigit(digits, 9)
                && digits[10] == checkDigit(digits, 10);
    }

    /** Formatted as {@code 000.000.000-00}, the way it would show up in a careless log line. */
    public static String format(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    private static int checkDigit(int[] digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += digits[i] * (length + 1 - i);
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static boolean allSame(int[] digits, int length) {
        for (int i = 1; i < length; i++) {
            if (digits[i] != digits[0]) {
                return false;
            }
        }
        return true;
    }
}
