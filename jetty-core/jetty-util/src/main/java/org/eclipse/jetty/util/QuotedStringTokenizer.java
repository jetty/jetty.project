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
    QuotedStringTokenizer CSV = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().build();

    /**
     * @return A Builder for a {@link QuotedStringTokenizer}.
     */
    static Builder builder()
    {
        return new Builder();
    }

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
     * Quote a string into an Appendable.
     *
     * @param buffer The Appendable
     * @param input The String to quote.
     */
    void quote(Appendable buffer, String input);

    /**
     * Unquote a string.
     *
     * @param s The string to unquote.
     * @return unquoted string
     */
    String unquote(String s);

    Iterator<String> tokenize(String string);

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
         * and after delimiters. This is not supported together with {@link #legacy()}.
         * @return this {@code Builder}
         */
        public Builder optionalWhiteSpace()
        {
            _optionalWhiteSpace = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will allow quotes to be within a token, not just as
         * initial/final characters as per the RFC.  For example the RFC illegal string {@code 'one, two", "three' }
         * with comma delimiter, would result in two tokens: {@code 'one' & 'two, three'}.
         * @return this {@code Builder}
         */
        public Builder embeddedQuotes()
        {
            _embeddedQuotes = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will allow single quotes. This can only be used with
         * {@link #legacy()}.
         * @return this {@code Builder}
         */
        public Builder singleQuotes()
        {
            _singleQuotes = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will only allow escapes for quote characters.
         * For example the string {@code '"test\"tokenizer\escape"'} will be unquoted as
         * {@code 'test"tokenizer\escape'}.
         * @return this {@code Builder}
         */
        public Builder escapeOnlyQuote()
        {
            _escapeOnlyQuote = true;
            return this;
        }

        /**
         * If called, the built {@link QuotedStringTokenizer} will use the legacy implementation from prior to
         * jetty-12. The legacy implementation does not comply with any current RFC. Using {@code legacy} also
         * implies {@link #embeddedQuotes()}.
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
                return new QuotedStringTokenizerLegacy(_delim, _returnDelimiters, _returnQuotes, _singleQuotes);
            }
            if (StringUtil.isEmpty(_delim))
                throw new IllegalArgumentException("Delimiters must be provided");
            if (_singleQuotes)
                throw new IllegalArgumentException("Single quotes not supported by RFC9110");
            return new QuotedStringTokenizerRfc9110(_delim, _optionalWhiteSpace, _returnDelimiters, _returnQuotes, _embeddedQuotes, _escapeOnlyQuote);
        }
    }
}
