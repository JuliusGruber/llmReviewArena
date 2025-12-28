package dev.reviewarena.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.util.regex.Pattern;

/**
 * Picocli type converter that validates commit hash format at parse time.
 * Accepts 7-40 character hexadecimal strings (Git abbreviated or full commit hashes).
 * Normalizes valid hashes to lowercase.
 */
public class CommitHashConverter implements ITypeConverter<String> {

    private static final Pattern HASH_PATTERN = Pattern.compile("^[a-fA-F0-9]{7,40}$");

    @Override
    public String convert(String value) throws TypeConversionException {
        if (!HASH_PATTERN.matcher(value).matches()) {
            throw new TypeConversionException(
                "Invalid commit hash format: " + value + " (expected 7-40 hex characters)");
        }
        return value.toLowerCase();
    }
}
