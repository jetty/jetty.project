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

public interface QuotedStringTokenizer
{
    QuotedStringTokenizer CSV = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().build();

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

    class Builder
    {
        private String _delim = "\t\n\r";
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

        public Builder delimiters(String delim)
        {
            _delim = delim;
            return this;
        }

        public Builder returnQuotes()
        {
            _returnQuotes = true;
            return this;
        }

        public Builder returnDelimiters()
        {
            _returnDelimiters = true;
            return this;
        }

        public Builder optionalWhiteSpace()
        {
            _optionalWhiteSpace = true;
            return this;
        }

        public Builder embeddedQuotes()
        {
            _embeddedQuotes = true;
            return this;
        }

        public Builder singleQuotes()
        {
            _singleQuotes = true;
            return this;
        }

        public Builder escapeOnlyQuote()
        {
            _escapeOnlyQuote = true;
            return this;
        }

        public Builder legacy()
        {
            _legacy = true;
            _embeddedQuotes = true;
            return this;
        }

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
            if (_singleQuotes)
                throw new IllegalArgumentException("Single quotes not supported by RFC9110");
            return new QuotedStringTokenizerRfc9110(_delim, _optionalWhiteSpace, _returnDelimiters, _returnQuotes, _embeddedQuotes, _escapeOnlyQuote);
        }
    }

    Iterator<String> tokenize(String string);

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
}
