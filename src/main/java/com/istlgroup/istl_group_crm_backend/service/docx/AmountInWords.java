package com.istlgroup.istl_group_crm_backend.service.docx;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Indian-system amount in words, in the wording the reference proposals use:
 * {@code "Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only."}
 */
public final class AmountInWords {

    private static final String[] ONES = {
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountInWords() { }

    public static String rupees(BigDecimal amount) {
        if (amount == null) return "";
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        long value = rounded.longValue();
        if (value == 0) return "Rupees Zero Only.";
        StringBuilder sb = new StringBuilder("Rupees");
        if (value < 0) { sb.append(" Minus"); value = -value; }
        sb.append(' ').append(words(value)).append(" Only.");
        return sb.toString();
    }

    /** The number itself, without the "Rupees … Only." wrapper. */
    public static String words(long value) {
        if (value == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        long crore = value / 10_000_000;
        value %= 10_000_000;
        long lakh = value / 100_000;
        value %= 100_000;
        long thousand = value / 1_000;
        value %= 1_000;
        long hundred = value / 100;
        long rest = value % 100;

        if (crore > 0) append(sb, twoOrThree(crore), crore == 1 ? "Crore" : "Crores");
        if (lakh > 0) append(sb, twoOrThree(lakh), lakh == 1 ? "Lakh" : "Lakhs");
        if (thousand > 0) append(sb, twoOrThree(thousand), "Thousand");
        if (hundred > 0) append(sb, twoOrThree(hundred), "Hundred");
        if (rest > 0) append(sb, twoOrThree(rest), null);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String number, String scale) {
        if (number.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(number);
        if (scale != null) sb.append(' ').append(scale);
    }

    /** 0–999 in words. Crore groups can exceed two digits, hence the hundreds arm. */
    private static String twoOrThree(long n) {
        if (n >= 100) {
            String head = ONES[(int) (n / 100)] + " Hundred";
            long rest = n % 100;
            return rest == 0 ? head : head + " " + twoOrThree(rest);
        }
        if (n < 20) return ONES[(int) n];
        String t = TENS[(int) (n / 10)];
        long unit = n % 10;
        return unit == 0 ? t : t + " " + ONES[(int) unit];
    }
}
