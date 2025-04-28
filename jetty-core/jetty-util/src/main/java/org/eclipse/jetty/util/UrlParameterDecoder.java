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
    private final boolean allowPartialBufferString;
    private int percent = 0;
    private char percentCode = 0;
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
        this.allowPartialBufferString = allowBadEncoding;
    }

    /**
     * <p>Parse a String completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * @param str the string to parse, completing the parsing after parsing.
     * @return true if there were no coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(String str) throws CharacterCodingException
    {
        return parse(str, 0, str.length());
    }

    /**
     * <p>Parse a String completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * @param str the string to parse, completing the parsing after parsing.
     * @param offset the offset in the string to start parsing from.
     * @param length the length of the substring to parse.
     * @return true if there were no coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(String str, int offset, int length) throws CharacterCodingException
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            parse(str.charAt(i));
        }
        complete();
        return builder.hasCodingErrors();
    }

    /**
     * <p>Parse a InputStream completely.</p>
     *
     * <p>The {@code newFieldAdder} is called for each encountered {@code key=value} pair.</p>
     *
     * <p>The InputStream is read until EOF</p>
     *
     * @param input the InputStream to parse, completing the parsing after parsing.
     * @return true if there were no coding errors, false otherwise.
     * @throws CharacterCodingException if a coding issue is encountered with the
     * provided {@link CharsetStringBuilder} and the specific condition
     * is not allowed by one of the {@code allow*} parameters on the constructor.
     */
    public boolean parse(InputStream input, Charset charset) throws IOException
    {
        int c;
        InputStreamReader reader = new InputStreamReader(input, charset);
        while ((c = reader.read()) != -1)
        {
            parse((char)c);
        }
        complete();
        return builder.hasCodingErrors();
    }

    private void parse(char c) throws CharacterCodingException
    {
        if (maxLength >= 0 && charCount++ > maxLength)
            throw new IllegalStateException("Form is larger than max length " + maxLength);

        // characters that can break a pct-encoding parse
        boolean isPctSpecial = (c == '&' || c == '=' || c == '%');
        switch (percent)
        {
            case 1 ->
            {
                if (isPctSpecial)
                {
                    boolean replaced = builder.replaceIncomplete();
                    if (replaced && !allowBadEncoding || !allowBadPercent)
                        throw new IllegalArgumentException("Invalid pct-encoded sequence %%%c".formatted(c));
                    if (!replaced)
                        builder.append('%');
                    percent = 0;
                }
                else
                {
                    percentCode = c;
                    percent++;
                    return;
                }
            }
            case 2 ->
            {
                percent = 0;
                char hi = percentCode;
                char lo = c;
                if (isPctSpecial)
                {
                    boolean replaced = builder.replaceIncomplete();
                    if (replaced && !allowBadEncoding || !allowBadPercent)
                        throw new IllegalArgumentException("Invalid pct-encoded sequence %%%c%c".formatted(hi, lo));
                    if (!replaced)
                    {
                        builder.append('%');
                        builder.append(hi);
                    }
                }
                else
                {
                    try
                    {
                        appendHexByte(hi, lo);
                    }
                    catch (NumberFormatException e)
                    {
                        boolean replaced = builder.replaceIncomplete();
                        if (replaced && !allowBadEncoding || !allowBadPercent)
                            throw new IllegalArgumentException("Invalid pct-encoded sequence %%%c%c".formatted(hi, lo));

                        if (!replaced)
                        {
                            builder.append('%');
                            builder.append(hi);
                            builder.append(lo);
                        }
                    }
                    return;
                }
            }
        }

        if (name == null)
        {
            switch (c)
            {
                case '&' ->
                {
                    String name = takeBuiltString();
                    onNewField(name, "");
                }
                case '=' -> name = takeBuiltString();
                case '+' -> builder.append(' ');
                case '%' -> percent++;
                default ->
                {
                    builder.append(c);
                }
            }
        }
        else
        {
            switch (c)
            {
                case '&' ->
                {
                    String value = takeBuiltString();
                    onNewField(name, value);
                    name = null;
                }
                case '+' -> builder.append(' ');
                case '%' -> percent++;
                default -> builder.append(c);
            }
        }
    }

    private void complete() throws CharacterCodingException
    {
        // Deal with any remaining incomplete pct-encoded sequences.
        if (percent > 0)
        {
            if (builder.replaceIncomplete())
            {
                if (!allowBadEncoding || !allowBadPercent)
                    throw new IllegalArgumentException("invalid percent encoding");
            }
            else if (allowBadPercent)
            {
                builder.append('%');
                if (percent > 1)
                    builder.append(percentCode);
            }
            else
            {
                throw new IllegalArgumentException("invalid percent encoding");
            }
        }

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

    private void appendHexByte(char hi, char lo)
    {
        builder.append((byte)((convertHexDigit(hi) << 4) + convertHexDigit(lo)));
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
}
