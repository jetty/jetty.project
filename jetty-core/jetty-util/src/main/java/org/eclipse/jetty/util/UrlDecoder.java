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
import java.nio.charset.CharacterCodingException;
import java.util.function.BiConsumer;

import static org.eclipse.jetty.util.TypeUtil.convertHexDigit;

class UrlDecoder
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
    private byte percentCode = 0;
    private String name;
    private int keyCount;
    private int charCount;

    public UrlDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder)
    {
        this(charsetStringBuilder, newFieldAdder, -1, -1);
    }

    public UrlDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder, int maxLength, int maxKeys)
    {
        this(charsetStringBuilder, newFieldAdder, maxLength, maxKeys, false, false, false);
    }

    public UrlDecoder(CharsetStringBuilder charsetStringBuilder, BiConsumer<String, String> newFieldAdder, int maxLength, int maxKeys,
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

    public void parse(String str) throws CharacterCodingException
    {
        parse(str, 0, str.length());
    }

    public void parse(String str, int offset, int length) throws CharacterCodingException
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            parse((byte)str.charAt(i));
        }
        complete();
    }

    public void parse(InputStream input) throws IOException
    {
        int b;
        while ((b = input.read()) != -1)
        {
            parse((byte)b);
        }
        complete();
    }

    private void parse(byte c) throws CharacterCodingException
    {
        if (maxLength >= 0 && charCount++ > maxLength)
            throw new IllegalStateException("Form is larger than max length " + maxLength);

        // only run percent code if not a delimiter
        boolean isDelim = (c == '&' || c == '=');
        switch (percent)
        {
            case 1 ->
            {
                if (isDelim)
                {
                    builder.append('%');
                    percent = 0;
                    break;
                }
                percentCode = c;
                percent++;
                return;
            }
            case 2 ->
            {
                percent = 0;
                char hi = (char)percentCode;
                char lo = (char)c;
                try
                {
                    appendHexByte(hi, lo);
                }
                catch (NumberFormatException e)
                {
                    boolean replaced = builder.replaceIncomplete();
                    if (replaced && !allowBadEncoding || !allowBadPercent)
                        throw new IllegalArgumentException("Invalid pct-encoded sequence %%%c%c".formatted(hi, lo));

                    if (hi == '&' || name == null && hi == '=')
                    {
                        if (!replaced)
                            builder.append('%');
                        builder.append(hi);
                        builder.append(lo);
                    }
                    else if (lo == '&' || name == null && lo == '=')
                    {
                        if (!replaced)
                        {
                            builder.append('%');
                            builder.append(hi);
                        }
                        builder.append(lo);
                    }
                    else
                    {
                        if (!replaced)
                        {
                            builder.append('%');
                            builder.append(hi);
                            builder.append(lo);
                        }
                    }
                }
                return;
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

    public void complete() throws CharacterCodingException
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
        if (!allowBadEncoding && !allowTruncatedEncoding)
        {
            return builder.build(allowPartialBufferString);
        }

        boolean codingError = builder.hasCodingErrors();
        if (codingError && !allowBadEncoding)
        {
            return builder.build(allowPartialBufferString);
        }

        if (builder.replaceIncomplete() && !allowTruncatedEncoding)
        {
            return builder.build(allowPartialBufferString);
        }

        String result = builder.build(false);
        builder.reset();
        return result;
    }

    private void onNewField(String name, String value)
    {
        keyCount++;
        newFieldAdder.accept(name, value);
        if (maxKeys >= 0 && keyCount > maxKeys)
            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", keyCount, maxKeys));
    }
}
