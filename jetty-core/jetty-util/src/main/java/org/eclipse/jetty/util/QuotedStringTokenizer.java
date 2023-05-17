//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.Iterator;

/**
 * A Tokenizer that splits a string into parts, allowing for quotes.
 */
public interface QuotedStringTokenizer
{
    /**
     * A QuotedStringTokenizer for comma separated values with optional white space.
     */
    QuotedStringTokenizer CSV = QuotedStringTokenizer.builder().delimiters(",").ignoreOptionalWhiteSpace().build();

    /**
     * @return A Builder for a {@link QuotedStringTokenizer}.
     */
    static Builder builder()
    {
        return new Builder();
    }

    /**
     * @param s The string to test
     * @return True if the string is quoted.
     */
    static boolean isQuoted(String s)
    {
        return s != null && s.length() > 0 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"';
    }

    /**
     * Quote a string.
     * The string is quoted only if quoting is required due to
     * embedded delimiters, quote characters or the
     * empty string.
     *
     * @param s The string to quote.
     * @return quoted string
     */
    default String quote(String s)
    {
        if (s == null)
            return null;
        if (s.length() == 0)
            return "\"\"";

        StringBuffer b = new StringBuffer(s.length() + 8);
        quote(b, s);
        return b.toString();
    }

    /**
     * Quote a string into an Appendable, escaping any characters that
     * need to be escaped.
     *
     * @param buffer The Appendable to append the quoted and escaped string into.
     * @param input The String to quote.
     */
    void quote(Appendable buffer, String input);

    /**
     * Unquote a string and expand any escaped characters
     *
     * @param s The string to unquote.
     * @return unquoted string with escaped characters expanded.
     */
    String unquote(String s);

    /**
     * Tokenize the passed string into an {@link Iterator} of tokens
     * split from the string by delimiters. Tokenization is done as the
     * iterator is advanced.
     * @param string The string to be tokenized
     * @return An iterator of token strings.
     */
    Iterator<String> tokenize(String string);

    /**
     * @param c A character
     * @return True if a string containing the character should be quoted.
     */
    boolean needsQuoting(char c);

    /**
     * Quote a string.
     * The string is quoted only if quoting is required due to
     * embedded delimiters, quote characters or the empty string.
     *
     * @param s The string to quote.
     * @return quoted string
     */
    String quoteIfNeeded(String s);

    /**
     * Append into buf the provided string, adding quotes if needed.
     * <p>
     * Quoting is determined if any of the characters in the {@code delim} are found in the input {@code str}.
     *
     * @param buf the buffer to append to
     * @param str the string to possibly quote
     */
    void quoteIfNeeded(StringBuilder buf, String str);

    class Builder
    {
        private String _delim;
        private boolean _returnQuotes;
        private boolean _returnDelimiters;
        private boolean _optionalWhiteSpace;
        private boolean _embeddedQuotes;
        private boolean _singleQuotes;
        private boolean _escapeOnlyQuote;
        private boolean _legacy;

        private Builder()
        {
        }

        /**
         * @param delim A string containing the set of characters that are considered delimiters.
         * @return this {@code Builder}
         */
        public Builder delimiters(String delim)
        {
            _delim = delim;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will return tokens with quotes interpreted but not removed.
         * @return this {@code Builder}
         */
        public Builder returnQuotes()
        {
            _returnQuotes = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will return delimiter characters as individual tokens.
         * @return this {@code Builder}
         */
        public Builder returnDelimiters()
        {
            _returnDelimiters = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will ignore optional white space characters before
         * and after delimiters. This is not supported together with {@link #legacy()}. For example, the
         * string {@code a, b ,c} with delimiter {@code ,} will be tokenized with this option as {@code a},
         * {@code b} and {@code c}, all trimmed of spaces. Without this option, the second token would be {@code b} with one
         * space before and after.
         * @return this {@code Builder}
         */
        public Builder ignoreOptionalWhiteSpace()
        {
            _optionalWhiteSpace = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will interpret quote characters within a token as initiating
         * a sequence of quoted characters, rather than being part of the token value itself.
         * For example the string {@code name1=value1; name2="value;2"} with {@code ;} delimiter, would result in
         * two tokens: {@code name1=value1} and {@code name2=value;2}. Without this option
         * the result would be three tokens: {@code name1=value1}, {@code name2="value} and {@code 2"}.
         * @return this {@code Builder}
         */
        public Builder allowEmbeddedQuotes()
        {
            _embeddedQuotes = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will allow quoting with the single quote character {@code '}.
         * This can only be used with {@link #legacy()}.
         * @return this {@code Builder}
         */
        public Builder allowSingleQuote()
        {
            _singleQuotes = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will only allow escapes to be used with
         * the quote character.  Specifically the escape character itself cannot be escaped.
         * Any usage of the escape character, other than for quotes, is considered as a literal escape character.
         * For example the string {@code "test\"tokenizer\test"} will be unquoted as
         * {@code test"tokenizer\test}.
         * @return this {@code Builder}
         */
        public Builder allowEscapeOnlyForQuotes()
        {
            _escapeOnlyQuote = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will use the legacy implementation from prior to
         * jetty-12. The legacy implementation does not comply with any current RFC. Using {@code legacy} also
         * implies {@link #allowEmbeddedQuotes()}.
         * @return this {@code Builder}
         */
        public Builder legacy()
        {
            _legacy = true;
            _embeddedQuotes = true;
            return this;
        }

        /**
         * @return The built immutable {@link QuotedStringTokenizer}.
         */
        public QuotedStringTokenizer build()
        {
            if (_legacy)
            {
                if (_optionalWhiteSpace)
                    throw new IllegalArgumentException("OWS not supported by legacy");
                if (_escapeOnlyQuote)
                    throw new IllegalArgumentException("EscapeOnlyQuote not supported by legacy");
                if (!_embeddedQuotes)
                    throw new IllegalArgumentException("EmbeddedQuotes must be used with legacy");
                return new LegacyQuotedStringTokenizer(_delim, _returnDelimiters, _returnQuotes, _singleQuotes);
            }
            if (StringUtil.isEmpty(_delim))
                throw new IllegalArgumentException("Delimiters must be provided");
            if (_singleQuotes)
                throw new IllegalArgumentException("Single quotes not supported by RFC9110");
            return new RFC9110QuotedStringTokenizer(_delim, _optionalWhiteSpace, _returnDelimiters, _returnQuotes, _embeddedQuotes, _escapeOnlyQuote);
        }
    }
}
