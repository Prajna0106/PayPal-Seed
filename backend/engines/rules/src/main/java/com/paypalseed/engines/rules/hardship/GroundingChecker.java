package com.paypalseed.engines.rules.hardship;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proves that AI-written text quotes only numbers, dates and options the code produced (03 §11). The
 * model does no arithmetic: every amount, percentage, date, duration, count and option it mentions must be
 * in {@link Allowed}, which the caller builds from engine output. Anything else fails, and the caller
 * replaces the text with the deterministic template.
 *
 * <p>Tokens are consumed in a fixed order (dates, money, percentages, durations, counts, options) and
 * masked as they go, so no digit is judged twice; any digit left over must still be an allowed value.
 */
public final class GroundingChecker {

    public enum Audience {
        MEMBER,
        OFFICER
    }

    /** What the text may mention. Money in cents, percentages in bps, durations split by unit. */
    public record Allowed(Set<Long> moneyCents, Set<Integer> percentBps, Set<LocalDate> dates, Set<Integer> counts,
                          Set<Integer> durationsDays, Set<Integer> durationsMonths, Set<String> optionLabels) {

        public Allowed {
            moneyCents = Set.copyOf(moneyCents);
            percentBps = Set.copyOf(percentBps);
            dates = Set.copyOf(dates);
            counts = Set.copyOf(counts);
            durationsDays = Set.copyOf(durationsDays);
            durationsMonths = Set.copyOf(durationsMonths);
            optionLabels = Set.copyOf(optionLabels);
        }
    }

    /** @param failureKind null when passed; otherwise one of the {@code FAIL_*} kinds */
    public record Result(boolean passed, String failureKind, String failingToken) {

        static final Result PASS = new Result(true, null, null);

        static Result fail(String kind, String token) {
            return new Result(false, kind, token);
        }
    }

    public static final String FAIL_EMPTY = "empty";
    public static final String FAIL_LENGTH = "length";
    public static final String FAIL_FORBIDDEN = "forbidden";
    public static final String FAIL_LANGUAGE = "language";
    public static final String FAIL_MONEY = "money";
    public static final String FAIL_PERCENT = "percent";
    public static final String FAIL_DATE = "date";
    public static final String FAIL_DURATION = "duration";
    public static final String FAIL_COUNT = "count";
    public static final String FAIL_OPTION = "option";
    public static final String FAIL_NUMBER = "number";

    public static final int MEMBER_LIMIT_EN = 480;
    public static final int MEMBER_LIMIT_ES = 520;
    public static final int OFFICER_LIMIT = 1200;
    public static final int PERCENT_TOLERANCE_BPS = 50;

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
            Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20),
            Map.entry("cero", 0), Map.entry("un", 1), Map.entry("una", 1), Map.entry("uno", 1), Map.entry("dos", 2),
            Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7),
            Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12),
            Map.entry("trece", 13), Map.entry("catorce", 14), Map.entry("quince", 15), Map.entry("dieciséis", 16),
            Map.entry("dieciseis", 16), Map.entry("diecisiete", 17), Map.entry("dieciocho", 18), Map.entry("diecinueve", 19),
            Map.entry("veinte", 20));

    private static final String NUM = "(\\d+|" + String.join("|", NUMBER_WORDS.keySet().stream()
            .sorted((a, b) -> b.length() - a.length()).toList()) + ")";

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("jan", 1), Map.entry("february", 2), Map.entry("feb", 2),
            Map.entry("march", 3), Map.entry("mar", 3), Map.entry("april", 4), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("june", 6), Map.entry("jun", 6), Map.entry("july", 7), Map.entry("jul", 7), Map.entry("august", 8),
            Map.entry("aug", 8), Map.entry("september", 9), Map.entry("sept", 9), Map.entry("sep", 9),
            Map.entry("october", 10), Map.entry("oct", 10), Map.entry("november", 11), Map.entry("nov", 11),
            Map.entry("december", 12), Map.entry("dec", 12),
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
            Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
            Map.entry("septiembre", 9), Map.entry("setiembre", 9), Map.entry("octubre", 10), Map.entry("noviembre", 11),
            Map.entry("diciembre", 12));

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern URL = Pattern.compile(
            "(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:com|net|org|io|ly|me|co|app|dev|info|biz|link)\\b", CI);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w$])\\+?\\d(?:[\\s().-]*\\d){6,}");
    private static final Pattern FOREIGN_CURRENCY = Pattern.compile("[€£¥₹]|\\b(?:EUR|GBP|MXN|INR|CAD|euros?|pesos|libras)\\b", CI);
    private static final Pattern CLAIM_OR_PRESSURE = Pattern.compile(
            "\\b(?:approved|guaranteed|waived|forgiven|aprobad[oa]s?|garantizad[oa]s?|perdonad[oa]s?|condonad[oa]s?"
                    + "|urgent|urgente|final notice|last chance|act now|aviso final|[uú]ltima oportunidad)\\b"
                    + "|no effect on your record|sin efecto en tu historial", CI);

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern EN_DATE = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|october|november|december"
                    + "|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?", CI);
    private static final Pattern ES_DATE = Pattern.compile(
            "\\b(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre"
                    + "|noviembre|diciembre)\\b(?:\\s+de\\s+(\\d{4}))?", CI);
    private static final Pattern MONEY_SYMBOL = Pattern.compile("\\$\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?");
    private static final Pattern MONEY_WORD = Pattern.compile(
            "\\b(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{2}))?\\s*(?:usd|dollars?|d[oó]lares)\\b", CI);
    private static final Pattern MONEY_NUMBER_WORD = Pattern.compile("\\b" + NUM + "\\s+(?:dollars?|d[oó]lares)\\b", CI);
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent\\b|per cent\\b|por\\s?ciento\\b)", CI);
    private static final Pattern DURATION = Pattern.compile(
            "\\b" + NUM + "[\\s-]+(?:(?:more|extra|additional|m[aá]s)\\s+)?(days?|d[ií]as?|months?|mes(?:es)?)\\b", CI);
    private static final Pattern COUNT = Pattern.compile(
            "\\b" + NUM + "\\s+(?:(?:more|extra|equal|smaller|monthly|weekly|m[aá]s)\\s+)?"
                    + "(?:payments?|instal(?:l)?ments?|weeks?|pagos?|cuotas?|semanas?)\\b", CI);
    // The label must be a capital letter: in Spanish "opción a" and "opción y" are ordinary words ("to", "and").
    private static final Pattern OPTION_LETTER = Pattern.compile("\\b(?iu:option|opci[oó]n)\\s+([A-Z])\\b");
    private static final Map<Pattern, String> OPTION_PHRASES = Map.of(
            Pattern.compile("\\b(?:move the date|move your payment|later date|mover la fecha|cambiar la fecha)\\b", CI), "A",
            Pattern.compile("\\b(?:pause[sd]?|pausing|pausa[r]?)\\b", CI), "B",
            Pattern.compile("\\b(?:smaller payments|smaller, longer plan|longer plan|pagos m[aá]s peque[nñ]os|plan m[aá]s largo)\\b", CI), "C",
            Pattern.compile("\\b(?:pay what you can|pag(?:a|ar) lo que puedas)\\b", CI), "D");
    private static final Pattern LIST_ORDINAL = Pattern.compile("(?m)^\\s*\\d{1,2}[.)]\\s");
    private static final Pattern LEFTOVER_NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static final Set<String> EN_STOPWORDS = Set.of("the", "and", "you", "your", "is", "are", "to", "of", "for",
            "with", "this", "that", "will", "can", "it", "be", "we", "our", "if", "or", "an", "have", "has", "would");
    private static final Set<String> ES_STOPWORDS = Set.of("el", "la", "los", "las", "y", "de", "que", "tu", "su", "es",
            "para", "con", "por", "una", "se", "del", "al", "lo", "te", "si", "puedes", "tus", "pago", "este");
    private static final int LANGUAGE_MIN_WORDS = 6;

    private GroundingChecker() {
    }

    public static Result check(String text, Allowed allowed, String locale, Audience audience, LocalDate scanDate) {
        Objects.requireNonNull(allowed, "allowed");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(scanDate, "scanDate");
        if (!"en".equals(locale) && !"es".equals(locale)) {
            throw new IllegalArgumentException("locale must be en or es");
        }
        if (text == null || text.isBlank()) {
            return Result.fail(FAIL_EMPTY, "");
        }
        int limit = audience == Audience.OFFICER ? OFFICER_LIMIT : "es".equals(locale) ? MEMBER_LIMIT_ES : MEMBER_LIMIT_EN;
        if (text.length() > limit) {
            return Result.fail(FAIL_LENGTH, String.valueOf(text.length()));
        }
        Optional<String> forbidden = firstForbidden(text);
        if (forbidden.isPresent()) {
            return Result.fail(FAIL_FORBIDDEN, forbidden.get());
        }
        if (!languageMatches(text, locale)) {
            return Result.fail(FAIL_LANGUAGE, locale);
        }

        StringBuilder work = new StringBuilder(text);
        for (Step step : new Step[] {
                () -> dates(work, allowed, scanDate),
                () -> money(work, allowed),
                () -> percents(work, allowed),
                () -> durations(work, allowed),
                () -> counts(work, allowed),
                () -> options(work, allowed),
                () -> leftovers(work, allowed)}) {
            Result r = step.run();
            if (!r.passed()) {
                return r;
            }
        }
        return Result.PASS;
    }

    @FunctionalInterface
    private interface Step {
        Result run();
    }

    private static Optional<String> firstForbidden(String text) {
        for (Pattern p : new Pattern[] {URL, EMAIL, FOREIGN_CURRENCY, CLAIM_OR_PRESSURE}) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return Optional.of(m.group());
            }
        }
        // An ISO date is a run of eight digits; it must not read as a phone number.
        Matcher phone = PHONE.matcher(ISO_DATE.matcher(text).replaceAll("date"));
        return phone.find() ? Optional.of(phone.group()) : Optional.empty();
    }

    private static boolean languageMatches(String text, String locale) {
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        int words = 0;
        int en = 0;
        int es = 0;
        while (m.find()) {
            words++;
            String w = m.group();
            en += EN_STOPWORDS.contains(w) ? 1 : 0;
            es += ES_STOPWORDS.contains(w) ? 1 : 0;
        }
        if (words < LANGUAGE_MIN_WORDS) {
            return true;
        }
        return "en".equals(locale) ? en >= es : es >= en;
    }

    private static Result dates(StringBuilder work, Allowed allowed, LocalDate scanDate) {
        Matcher iso = ISO_DATE.matcher(work);
        while (iso.find()) {
            LocalDate date;
            try {
                date = LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            } catch (DateTimeException e) {
                return Result.fail(FAIL_DATE, iso.group());
            }
            if (!allowed.dates().contains(date)) {
                return Result.fail(FAIL_DATE, iso.group());
            }
            mask(work, iso.start(), iso.end());
        }
        Matcher en = EN_DATE.matcher(work);
        while (en.find()) {
            String month = en.group(1);
            // "you may 2..." is the verb, not the month: only capitalised May is a date.
            if (month.equalsIgnoreCase("may") && Character.isLowerCase(month.charAt(0))) {
                continue;
            }
            Result r = date(work, en, MONTHS.get(month.toLowerCase(Locale.ROOT)), en.group(2), en.group(3), allowed, scanDate);
            if (r != null) {
                return r;
            }
        }
        Matcher es = ES_DATE.matcher(work);
        while (es.find()) {
            Result r = date(work, es, MONTHS.get(es.group(2).toLowerCase(Locale.ROOT)), es.group(1), es.group(3), allowed, scanDate);
            if (r != null) {
                return r;
            }
        }
        return Result.PASS;
    }

    /** @return a failure, or null after masking a matched allowed date */
    private static Result date(StringBuilder work, Matcher m, int month, String dayText, String yearText, Allowed allowed,
                               LocalDate scanDate) {
        int day = Integer.parseInt(dayText);
        boolean ok;
        if (yearText != null) {
            Optional<LocalDate> exact = safeDate(Integer.parseInt(yearText), month, day);
            ok = exact.isPresent() && allowed.dates().contains(exact.get());
        } else {
            ok = withoutYear(month, day, allowed, scanDate);
        }
        if (!ok) {
            return Result.fail(FAIL_DATE, m.group());
        }
        mask(work, m.start(), m.end());
        return null;
    }

    /**
     * "October 20" names whichever allowed October 20 lies within a year of the scan date. Picking the
     * calendar-nearest year instead would read a plan date seven months ahead as last year's.
     */
    private static boolean withoutYear(int month, int day, Allowed allowed, LocalDate scanDate) {
        for (int y = scanDate.getYear() - 1; y <= scanDate.getYear() + 1; y++) {
            Optional<LocalDate> candidate = safeDate(y, month, day);
            if (candidate.isPresent() && allowed.dates().contains(candidate.get())
                    && Math.abs(ChronoUnit.DAYS.between(scanDate, candidate.get())) <= 366) {
                return true;
            }
        }
        return false;
    }

    private static Optional<LocalDate> safeDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Result money(StringBuilder work, Allowed allowed) {
        for (Pattern p : new Pattern[] {MONEY_SYMBOL, MONEY_WORD}) {
            Matcher m = p.matcher(work);
            while (m.find()) {
                long cents = Long.parseLong(m.group(1).replace(",", "")) * 100 + fraction(m.group(2));
                if (!allowed.moneyCents().contains(cents)) {
                    return Result.fail(FAIL_MONEY, m.group());
                }
                mask(work, m.start(), m.end());
            }
        }
        Matcher words = MONEY_NUMBER_WORD.matcher(work);
        while (words.find()) {
            if (!allowed.moneyCents().contains(number(words.group(1)) * 100L)) {
                return Result.fail(FAIL_MONEY, words.group());
            }
            mask(work, words.start(), words.end());
        }
        return Result.PASS;
    }

    private static long fraction(String digits) {
        if (digits == null) {
            return 0;
        }
        return digits.length() == 1 ? Long.parseLong(digits) * 10 : Long.parseLong(digits);
    }

    private static Result percents(StringBuilder work, Allowed allowed) {
        Matcher m = PERCENT.matcher(work);
        while (m.find()) {
            int bps = new BigDecimal(m.group(1)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
            boolean near = allowed.percentBps().stream().anyMatch(a -> Math.abs(a - bps) <= PERCENT_TOLERANCE_BPS);
            if (!near) {
                return Result.fail(FAIL_PERCENT, m.group());
            }
            mask(work, m.start(), m.end());
        }
        return Result.PASS;
    }

    private static Result durations(StringBuilder work, Allowed allowed) {
        Matcher m = DURATION.matcher(work);
        while (m.find()) {
            int n = number(m.group(1));
            boolean months = m.group(2).toLowerCase(Locale.ROOT).startsWith("m");
            if (!(months ? allowed.durationsMonths() : allowed.durationsDays()).contains(n)) {
                return Result.fail(FAIL_DURATION, m.group());
            }
            mask(work, m.start(), m.end());
        }
        return Result.PASS;
    }

    private static Result counts(StringBuilder work, Allowed allowed) {
        Matcher m = COUNT.matcher(work);
        while (m.find()) {
            if (!allowed.counts().contains(number(m.group(1)))) {
                return Result.fail(FAIL_COUNT, m.group());
            }
            mask(work, m.start(), m.end());
        }
        return Result.PASS;
    }

    private static Result options(StringBuilder work, Allowed allowed) {
        Matcher letter = OPTION_LETTER.matcher(work);
        while (letter.find()) {
            String label = letter.group(1).toUpperCase(Locale.ROOT);
            if (!allowed.optionLabels().contains(label)) {
                return Result.fail(FAIL_OPTION, letter.group());
            }
            mask(work, letter.start(), letter.end());
        }
        for (Map.Entry<Pattern, String> phrase : OPTION_PHRASES.entrySet()) {
            Matcher m = phrase.getKey().matcher(work);
            if (m.find() && !allowed.optionLabels().contains(phrase.getValue())) {
                return Result.fail(FAIL_OPTION, m.group());
            }
        }
        return Result.PASS;
    }

    /** A digit nothing above claimed must still be some allowed value (bare "7" is fine only if 7 is allowed). */
    private static Result leftovers(StringBuilder work, Allowed allowed) {
        Matcher ordinal = LIST_ORDINAL.matcher(work);
        while (ordinal.find()) {
            mask(work, ordinal.start(), ordinal.end());
        }
        Matcher m = LEFTOVER_NUMBER.matcher(work);
        while (m.find()) {
            BigDecimal value = new BigDecimal(m.group().replace(",", ""));
            long cents = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
            if (allowed.moneyCents().contains(cents)) {
                continue;
            }
            if (value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0) {
                long n = value.longValue();
                boolean known = n <= Integer.MAX_VALUE && (allowed.counts().contains((int) n)
                        || allowed.durationsDays().contains((int) n) || allowed.durationsMonths().contains((int) n)
                        || allowed.percentBps().contains((int) n * 100));
                if (known) {
                    continue;
                }
            }
            return Result.fail(FAIL_NUMBER, m.group());
        }
        return Result.PASS;
    }

    private static int number(String token) {
        Integer word = NUMBER_WORDS.get(token.toLowerCase(Locale.ROOT));
        return word != null ? word : Integer.parseInt(token);
    }

    private static void mask(StringBuilder work, int start, int end) {
        for (int i = start; i < end; i++) {
            work.setCharAt(i, ' ');
        }
    }
}
