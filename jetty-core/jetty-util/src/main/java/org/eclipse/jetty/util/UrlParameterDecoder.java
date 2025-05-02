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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;

import static org.eclipse.jetty.util.TypeUtil.convertHexDigit;

/**
 * Parsing for URL/URI Query and {@code application/x-www-form-urlencoded} parameters.
 */
class UrlParameterDecoder
{
    private final BiConsumer<String, String> newFieldAdder;
    private final int maxLength;
    private final int maxKeys;
    private final boolean allowBadEncoding;
    private final boolean allowBadPercent;
    private final boolean allowTruncatedEncoding;
    private final CharsetStringBuilder builder;
    private String name;
    private int keyCount;
    private int charCount;

    public UrlParameterDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder)
    {
        this(charsetStringBuilder, newFieldAdder, -1, -1);
    }

    public UrlParameterDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder, int maxLength, int maxKeys)
    {
        this(charsetStringBuilder, newFieldAdder, maxLength, maxKeys, false, false, false);
    }

    /**
     * Construct a {@code UrlParameterDecoder} that is responsible for parsing
     * the input ({@link String} or {@link InputStream}) into the provided {@code newFieldAdder}
     * using the {@link CharsetStringBuilder} (to satisfy {@link java.nio.charset.Charset})
     *
     * @param charsetStringBuilder the {@link CharsetStringBuilder} that holds parsed bytes according to {@link java.nio.charset.Charset} rules
     * @param newFieldAdder the consumer of new fields (often a {@link Fields} instance, but sometimes a {@link MultiMap} instance)
     * @param maxLength the maximum allowable length in bytes of the form (-1 to disable check)
     * @param maxKeys the maximum number of keys for the form (-1 to disable)
     * @param allowBadEncoding allow use of bad encoding with the {@link CharsetStringBuilder} (optional behavior)
     * @param allowBadPercent allow use of bad pct-encoding with the {@link CharsetStringBuilder} (optional behavior)
     * @param allowTruncatedEncoding allow use of truncated pct-encoding with the {@link CharsetStringBuilder} (optional behavior)
     */
    public UrlParameterDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder, int maxLength, int maxKeys,
                               boolean allowBadEncoding, boolean allowBadPercent, boolean allowTruncatedEncoding)
    {
        this.builder = charsetStringBuilder;
        this.newFieldAdder = newFieldAdder;
        this.maxLength = maxLength;
        this.maxKeys = maxKeys;
        this.allowBadEncoding = allowBadEncoding;
        this.allowBadPercent = allowBadPercent;
        this.allowTruncatedEncoding = allowTruncatedEncoding;
    }

    /**
     * <p>Parse a CharSequence completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * @param charSequence the char sequence to parse, completing the parsing after parsing.
     * @return true if there were coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(CharSequence charSequence) throws IOException
    {
        if (charSequence instanceof String s)
            return parseCompletely(new StringCharIterator(s));
        return parseCompletely(new CharSequenceCharIterator(charSequence));
    }

    /**
     * <p>Parse a CharSequence section completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * @param charSequence the string to parse, completing the parsing after parsing.
     * @param offset the offset in the string to start parsing from.
     * @param length the length of the substring to parse.
     * @return true if there were coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(CharSequence charSequence, int offset, int length) throws IOException
    {
        if (charSequence instanceof String s)
            return parseCompletely(new StringCharIterator(s, offset, length));
        return parseCompletely(new CharSequenceCharIterator(charSequence, offset, length));
    }

    /**
     * <p>Parse a InputStream completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * <p>The InputStream is read until EOF</p>
     *
     * @param input the InputStream to parse, completing the parsing after parsing.
     * @param charset the charset to use when parsing the InputStream.
     * @return true if there were coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(InputStream input, Charset charset) throws IOException
    {
        return parse(new InputStreamReader(input, charset));
    }

    /**
     * <p>Parse a Reader completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * <p>The Reader is read until EOF</p>
     *
     * @param reader the Reader to parse, completing the parsing after parsing.
     * @return true if there were coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(Reader reader) throws IOException
    {
        return parseCompletely(new ReaderCharIterator(reader));
    }

    private boolean parseCompletely(CharIterator iter) throws IOException
    {
        int i;
        while ((i = iter.next()) >= 0)
        {
            char c = (char)i;
            if (maxLength >= 0 && ++charCount > maxLength)
                throw new IllegalStateException("Form is larger than max length " + maxLength);

            if (!parseChar(c, iter))
                break;
        }

        complete();
        return builder.hasCodingErrors();
    }

    /**
     * Parse the read character.
     *
     * @param c the read character
     * @param iter the character iterator to pull more characters from
     * @return true if parsing should continue, false otherwise
     * @throws IOException if unable to parse for a fundamental reason
     */
    private boolean parseChar(char c, CharIterator iter) throws IOException
    {
        switch (c)
        {
            case '&' ->
            {
                String str = takeBuiltString();
                if (name == null)
                {
                    onNewField(str, "");
                }
                else
                {
                    onNewField(name, str);
                    name = null;
                }
            }
            case '=' ->
            {
                if (name == null)
                    name = takeBuiltString();
                else
                    builder.append(c);
            }
            case '+' -> builder.append(' ');
            case '%' ->
            {
                int hi = iter.next();
                if (hi == -1)
                {
                    // we have a sequence ending in '%'
                    return handleIncompletePctEncoding(hi);
                }
                int lo = iter.next();
                if (lo == -1)
                {
                    // we have a sequence ending in `%?` (incomplete pct-encoding)
                    return handleIncompletePctEncoding(hi);
                }

                try
                {
                    decodeHexByteTo(builder, (char)hi, (char)lo);
                }
                catch (NumberFormatException e)
                {
                    boolean replaced = builder.replaceIncomplete();
                    if (replaced && !allowBadEncoding || !allowBadPercent)
                        throw new IllegalArgumentException(notValidPctEncoding((char)hi, (char)lo));

                    if (hi == '&' || name == null && hi == '=')
                    {
                        if (!replaced)
                            builder.append('%');
                        parseChar((char)hi, iter);
                        parseChar((char)lo, iter);
                    }
                    else if (lo == '&' || name == null && lo == '=')
                    {
                        if (!replaced)
                        {
                            builder.append('%');
                            builder.append((char)hi);
                        }
                        parseChar((char)lo, iter);
                    }
                    else
                    {
                        if (!replaced)
                        {
                            builder.append('%');
                            builder.append((char)hi);
                            builder.append((char)lo);
                        }
                    }
                }
            }
            default ->
            {
                builder.append(c);
            }
        }
        return true;
    }

    /**
     * Handle an incomplete pct-encoding such as {@code %} (ending in a percent symbol),
     * or {@code %A} (ending in only 1 hex digit).
     *
     * @param hi the hi char in the possible pct-encoding hex sequence (-1 for undefined)
     * @return true if parsing should continue, false otherwise
     */
    private boolean handleIncompletePctEncoding(int hi)
    {
        if (builder.replaceIncomplete())
        {
            if (!allowBadEncoding || !allowBadPercent)
                throw new IllegalArgumentException(notValidPctEncoding((char)hi, (char)0));
            return false;
        }
        else if (allowBadPercent)
        {
            builder.append('%');
            if (hi != -1)
                builder.append((char)hi);
            return true;
        }
        else
        {
            throw new IllegalArgumentException(notValidPctEncoding((char)hi, (char)0));
        }
    }

    private static void decodeHexByteTo(CharsetStringBuilder buffer, char hi, char lo)
    {
        buffer.append((byte)((convertHexDigit(hi) << 4) + convertHexDigit(lo)));
    }

    private void complete() throws CharacterCodingException
    {
        if (name != null)
        {
            String value = takeBuiltString();
            onNewField(name, value);
        }
        else if (builder.length() > 0)
        {
            name = takeBuiltString();
            onNewField(name, "");
        }
    }

    private String notValidPctEncoding(char hi, char lo)
    {
        return "Not valid encoding '%%%c%c'".formatted(hi != 0 ? hi : '?', lo != 0 ? lo : '?');
    }

    private String takeBuiltString() throws CharacterCodingException
    {
        if (!allowBadEncoding && !allowBadPercent && !allowTruncatedEncoding)
        {
            String result = builder.build(false);
            builder.reset();
            return result;
        }

        boolean codingError = builder.hasCodingErrors();
        if (codingError && !allowBadEncoding)
        {
            return builder.build(false);
        }

        if (builder.replaceIncomplete() && !allowTruncatedEncoding)
        {
            return builder.build(false);
        }

        String result = builder.build(true);
        builder.reset();
        return result;
    }

    private void onNewField(String name, String value)
    {
        if (name == null || name.isEmpty())
            return;
        keyCount++;
        newFieldAdder.accept(name, value);
        if (maxKeys >= 0 && keyCount > maxKeys)
            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", keyCount, maxKeys));
    }

    interface CharIterator
    {
        /**
         * Pull the next single character.
         *
         * <p>
         *     This method might block, depending on implementation.
         * </p>
         *
         * @return The character read, as an integer in the range 0 to 65535
         *         ({@code 0x00-0xffff}), or -1 if the end of the iterator
         *         has been reached (such as the end of a String or a stream EOF)
         */
        int next() throws IOException;
    }

    static class CharSequenceCharIterator implements CharIterator
    {
        private final CharSequence str;
        private final int end;
        private int idx;

        CharSequenceCharIterator(CharSequence str)
        {
            this(str, 0, str.length());
        }

        CharSequenceCharIterator(CharSequence str, int offset, int length)
        {
            this.str = str;
            this.end = offset + length;
            this.idx = offset;
        }

        @Override
        public int next()
        {
            if (idx >= end)
                return -1;
            return str.charAt(idx++);
        }
    }

    static class StringCharIterator implements CharIterator
    {
        private final char[] str;
        private final int end;
        private int idx;

        StringCharIterator(String str)
        {
            this(str, 0, str.length());
        }

        StringCharIterator(String str, int offset, int length)
        {
            this.str = str.toCharArray();
            this.end = offset + length;
            this.idx = offset;
        }

        @Override
        public int next()
        {
            if (idx >= end)
                return -1;
            return str[idx++];
        }
    }

    static class ReaderCharIterator implements CharIterator
    {
        private final Reader reader;
        private final CharBuffer buffer;

        ReaderCharIterator(Reader reader)
        {
            this.reader = reader;
            this.buffer = CharBuffer.allocate(128);
            this.buffer.flip();
        }

        @Override
        public int next() throws IOException
        {
            if (!buffer.hasRemaining())
            {
                buffer.clear();
                if (reader.read(buffer) == -1)
                    return -1;
                buffer.flip();
            }

            return buffer.get();
        }
    }
}
