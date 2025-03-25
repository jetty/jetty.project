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
import java.io.StringWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.TypeUtil.convertHexDigit;

/**
 * Handles coding of MIME  "x-www-form-urlencoded".
 * <p>
 * This class handles the encoding and decoding for either
 * the query string of a URL or the _content of a POST HTTP request.
 * </p>
 * <b>Notes</b>
 * <p>
 * The UTF-8 charset is assumed, unless otherwise defined by either
 * passing a parameter or setting the "org.eclipse.jetty.util.UrlEncoding.charset"
 * System property.
 * </p>
 *
 * @see java.net.URLEncoder
 */
@SuppressWarnings("serial")
public class UrlEncoded
{
    static final Logger LOG = LoggerFactory.getLogger(UrlEncoded.class);

    public static final Charset ENCODING;

    static
    {
        Charset encoding;
        String charset = null;
        try
        {
            charset = System.getProperty("org.eclipse.jetty.util.UrlEncoding.charset");
            if (charset == null)
            {
                encoding = StandardCharsets.UTF_8;
            }
            else
            {
                encoding = Charset.forName(charset);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to set default UrlEncoding charset: {}", charset, e);
            encoding = StandardCharsets.UTF_8;
        }
        ENCODING = encoding;
    }

    private UrlEncoded()
    {
    }

    /**
     * Encode MultiMap with % encoding.
     *
     * @param map the map to encode
     * @param charset the charset to use for encoding (uses default encoding if null)
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings.
     */
    public static String encode(MultiMap<String> map, Charset charset, boolean equalsForNullValue)
    {
        if (charset == null)
            charset = ENCODING;

        StringBuilder result = new StringBuilder(128);

        boolean delim = false;
        for (Map.Entry<String, List<String>> entry : map.entrySet())
        {
            String key = entry.getKey();
            List<String> list = entry.getValue();
            int s = list.size();

            if (delim)
            {
                result.append('&');
            }

            if (s == 0)
            {
                result.append(encodeString(key, charset));
                if (equalsForNullValue)
                    result.append('=');
            }
            else
            {
                for (int i = 0; i < s; i++)
                {
                    if (i > 0)
                        result.append('&');
                    String val = list.get(i);
                    result.append(encodeString(key, charset));

                    if (val != null)
                    {
                        if (!val.isEmpty())
                        {
                            result.append('=');
                            result.append(encodeString(val, charset));
                        }
                        else if (equalsForNullValue)
                            result.append('=');
                    }
                    else if (equalsForNullValue)
                        result.append('=');
                }
            }
            delim = true;
        }
        return result.toString();
    }

    public static MultiMap<String> decodeQuery(String query)
    {
        MultiMap<String> map = new MultiMap<>();
        if (StringUtil.isNotBlank(query))
            decodeUtf8To(query, 0, query.length(), map);
        return map;
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     * @deprecated use {@link #decodeTo(String, MultiMap, Charset)} instead
     */
    @Deprecated(since = "10", forRemoval = true)
    public static void decodeTo(String content, MultiMap<String> map, String charset)
    {
        decodeTo(content, map, charset == null ? null : Charset.forName(charset));
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, Charset charset)
    {
        decodeTo(content, map::add, charset);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, Charset charset, int maxKeys)
    {
        decodeTo(content, (key, val) ->
        {
            map.add(key, val);
            checkMaxKeys(map, maxKeys);
        }, charset);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param adder Function to add parameter
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, BiConsumer<String, String> adder, Charset charset, int maxKeys)
    {
        AtomicInteger keys = new AtomicInteger(0);
        decodeTo(content, (key, val) ->
        {
            adder.accept(key, val);
            checkMaxKeys(keys.incrementAndGet(), maxKeys);
        }, charset);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param adder a {@link BiConsumer} to accept the name/value pairs.
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, BiConsumer<String, String> adder, Charset charset)
    {
        if (charset == null)
            charset = ENCODING;

        if (StandardCharsets.UTF_8.equals(charset))
        {
            decodeUtf8To(content, 0, content.length(), adder);
            return;
        }

        String key = null;
        String value;
        int mark = -1;
        boolean encoded = false;
        for (int i = 0; i < content.length(); i++)
        {
            char c = content.charAt(i);
            switch (c)
            {
                case '&':
                    int l = i - mark - 1;
                    value = l == 0 ? "" : (encoded ? decodeString(content, mark + 1, l, charset) : content.substring(mark + 1, i));
                    mark = i;
                    encoded = false;
                    if (key != null)
                    {
                        adder.accept(key, value);
                    }
                    else if (value != null && !value.isEmpty())
                    {
                        adder.accept(value, "");
                    }
                    key = null;
                    break;
                case '=':
                    if (key != null)
                        break;
                    key = encoded ? decodeString(content, mark + 1, i - mark - 1, charset) : content.substring(mark + 1, i);
                    mark = i;
                    encoded = false;
                    break;
                case '+':
                case '%':
                    encoded = true;
                    break;
            }
        }

        if (key != null)
        {
            int l = content.length() - mark - 1;
            value = l == 0 ? "" : (encoded ? decodeString(content, mark + 1, l, charset) : content.substring(mark + 1));
            adder.accept(key, value);
        }
        else if (mark < content.length())
        {
            key = encoded
                ? decodeString(content, mark + 1, content.length() - mark - 1, charset)
                : content.substring(mark + 1);
            if (key != null && !key.isEmpty())
            {
                adder.accept(key, "");
            }
        }
    }

    /**
     * @param query the URI query string.
     * @param map the {@code MultiMap} to store the fields.
     * @deprecated use {@link #decodeUtf8To(String, Fields)} instead.
     */
    @Deprecated
    public static void decodeUtf8To(String query, MultiMap<String> map)
    {
        decodeUtf8To(query, 0, query.length(), map);
    }

    /**
     * <p>Decodes URI query parameters into a {@link Fields} instance.</p>
     *
     * @param query the URI query string.
     * @param fields the Fields to store the parameters.
     */
    public static void decodeUtf8To(String query, Fields fields)
    {
        decodeUtf8To(query, 0, query.length(), fields);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param query the string containing the encoded parameters
     * @param offset the offset within raw to decode from
     * @param length the length of the section to decode
     * @param map the {@link MultiMap} to populate
     * @deprecated use {@link #decodeUtf8To(String, int, int, Fields)} instead.
     */
    @Deprecated
    public static void decodeUtf8To(String query, int offset, int length, MultiMap<String> map)
    {
        decodeUtf8To(query, offset, length, map::add);
    }

    /**
     * <p>Decodes URI query parameters into a {@link Fields} instance.</p>
     *
     * @param uri the URI string.
     * @param offset the offset at which query parameters start.
     * @param length the length of query parameters string to parse.
     * @param fields the Fields to store the parameters.
     */
    public static void decodeUtf8To(String uri, int offset, int length, Fields fields)
    {
        decodeUtf8To(uri, offset, length, fields::add);
    }

    /**
     * <p>Decodes URI query parameters as UTF8 string</p>
     *
     * @param query the URI string.
     * @param offset the offset at which query parameters start.
     * @param length the length of query parameters string to parse.
     * @param adder the method to call to add decoded parameters.
     * @return {@code true} if the string was decoded without any bad UTF-8
     * @throws org.eclipse.jetty.util.Utf8StringBuilder.Utf8IllegalArgumentException if there is illegal UTF-8 and `allowsBadUtf8` is {@code false}
     */
    public static boolean decodeUtf8To(String query, int offset, int length, BiConsumer<String, String> adder)
        throws Utf8StringBuilder.Utf8IllegalArgumentException
    {
        return decodeUtf8To(query, offset, length, adder, false, false, false);
    }

    /**
     * <p>Decodes URI query parameters as UTF8 string</p>
     *
     * @param query the URI string.
     * @param offset the offset at which query parameters start.
     * @param length the length of query parameters string to parse.
     * @param adder the method to call to add decoded parameters.
     * @param allowBadPercent if {@code true} allow bad percent encoding.
     * @param allowBadUtf8 if {@code true} allow bad UTF-8 and insert the replacement character.
     * @return {@code true} if the string was decoded without any bad UTF-8
     * @throws org.eclipse.jetty.util.Utf8StringBuilder.Utf8IllegalArgumentException if there is illegal UTF-8 and `allowsBadUtf8` is {@code false}
     */
    public static boolean decodeUtf8To(String query, int offset, int length, BiConsumer<String, String> adder, boolean allowBadPercent, boolean allowBadUtf8, boolean allowTruncatedUtf8)
        throws Utf8StringBuilder.Utf8IllegalArgumentException
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        String key = null;
        String value;

        AtomicBoolean badUtf8;
        Supplier<Utf8StringBuilder.Utf8IllegalArgumentException> onCodingError;

        if (allowBadUtf8)
        {
            badUtf8 = new AtomicBoolean(false);
            onCodingError = () ->
            {
                badUtf8.set(true);
                return null;
            };
        }
        else
        {
            badUtf8 = null;
            onCodingError = Utf8StringBuilder.Utf8IllegalArgumentException::new;
        }

        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            char c = query.charAt(i);
            switch (c)
            {
                case '&':
                    value = take(allowBadUtf8, allowTruncatedUtf8, buffer, badUtf8, onCodingError);

                    if (key != null)
                    {
                        adder.accept(key, value);
                    }
                    else if (value != null && !value.isEmpty())
                    {
                        adder.accept(value, "");
                    }
                    key = null;
                    break;

                case '=':
                    if (key != null)
                    {
                        buffer.append(c);
                        break;
                    }

                    key = take(allowBadUtf8, allowTruncatedUtf8, buffer, badUtf8, onCodingError);
                    break;

                case '+':
                    buffer.append((byte)' ');
                    break;

                case '%':
                    if (i + 2 < end)
                    {
                        char hi = query.charAt(++i);
                        char lo = query.charAt(++i);
                        try
                        {
                            decodeHexByteTo(buffer, hi, lo);
                        }
                        catch (NumberFormatException e)
                        {
                            boolean replaced = buffer.replaceIncomplete();
                            if (replaced && !allowBadUtf8 || !allowBadPercent)
                                throw e;

                            if (hi == '&' || key == null && hi == '=')
                            {
                                if (!replaced)
                                    buffer.append('%');
                                i = i - 2;
                            }
                            else if (lo == '&' || key == null && lo == '=')
                            {
                                if (!replaced)
                                {
                                    buffer.append('%');
                                    buffer.append(hi);
                                }
                                i = i - 1;
                            }
                            else
                            {
                                if (!replaced)
                                {
                                    buffer.append('%');
                                    buffer.append(hi);
                                    buffer.append(lo);
                                }
                            }
                        }
                    }
                    else if (buffer.replaceIncomplete())
                    {
                        if (!allowBadUtf8 || !allowBadPercent)
                            throw new Utf8StringBuilder.Utf8IllegalArgumentException();
                        i = end;
                    }
                    else if (allowBadPercent)
                    {
                        buffer.append('%');
                    }
                    else
                    {
                        throw new Utf8StringBuilder.Utf8IllegalArgumentException();
                    }
                    break;

                default:
                    buffer.append(c);
                    break;
            }
        }

        if (key != null)
        {
            value = take(allowBadUtf8, allowTruncatedUtf8, buffer, badUtf8, onCodingError);
            adder.accept(key, value);
        }
        else if (buffer.length() > 0)
        {
            key = take(allowBadUtf8, allowTruncatedUtf8, buffer, badUtf8, onCodingError);
            adder.accept(key, "");
        }

        return badUtf8 == null || !badUtf8.get();
    }

    private static <X extends Throwable> String take(boolean allowBadUtf8, Boolean allowTruncatedUtf8, Utf8StringBuilder buffer, AtomicBoolean badUtf8, Supplier<X> onCodingError) throws X
    {
        if (!allowBadUtf8 && !allowTruncatedUtf8)
            return buffer.takeCompleteString(onCodingError);

        boolean codingError = buffer.hasCodingErrors();
        if (codingError && !allowBadUtf8)
            return buffer.takeCompleteString(onCodingError);

        if (buffer.replaceIncomplete() && !allowTruncatedUtf8)
            return buffer.takeCompleteString(onCodingError);

        String result = buffer.takeCompleteString(null);
        buffer.reset();
        if (badUtf8 != null)
            badUtf8.set(true);
        return result;
    }

    /**
     * Decoded parameters to MultiMap, using ISO8859-1 encodings.
     *
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum length of form to read or -1 for no limit
     * @param maxKeys maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the InputStream as ISO8859-1
     */
    public static void decode88591To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
        throws IOException
    {
        decode88591To(in, map::add, maxLength, maxKeys);
    }

    /**
     * Decoded parameters to MultiMap, using ISO8859-1 encodings.
     *
     * @param in InputSteam to read
     * @param adder Function to add parameter
     * @param maxLength maximum length of form to read or -1 for no limit
     * @param maxKeys maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the InputStream as ISO8859-1
     */
    public static void decode88591To(InputStream in, BiConsumer<String, String> adder, int maxLength, int maxKeys)
        throws IOException
    {
        StringBuilder buffer = new StringBuilder();
        String key = null;
        String value;

        int b;

        int totalLength = 0;
        int keys = 0;
        while ((b = in.read()) >= 0)
        {
            switch ((char)b)
            {
                case '&':
                    value = buffer.isEmpty() ? "" : buffer.toString();
                    buffer.setLength(0);
                    if (key != null)
                    {
                        adder.accept(key, value);
                        keys++;
                    }
                    else if (!value.isEmpty())
                    {
                        adder.accept(value, "");
                        keys++;
                    }
                    key = null;
                    checkMaxKeys(keys, maxKeys);
                    break;

                case '=':
                    if (key != null)
                    {
                        buffer.append((char)b);
                        break;
                    }
                    key = buffer.toString();
                    buffer.setLength(0);
                    break;

                case '+':
                    buffer.append(' ');
                    break;

                case '%':
                    int code0 = in.read();
                    int code1 = in.read();
                    buffer.append(decodeHexChar(code0, code1));
                    break;

                default:
                    buffer.append((char)b);
                    break;
            }
            checkMaxLength(++totalLength, maxLength);
        }

        if (key != null)
        {
            value = buffer.isEmpty() ? "" : buffer.toString();
            buffer.setLength(0);
            adder.accept(key, value);
            keys++;
        }
        else if (!buffer.isEmpty())
        {
            adder.accept(buffer.toString(), "");
            keys++;
        }
        checkMaxKeys(keys, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in InputSteam to read
     * @param fields the Fields to store the parameters
     * @param maxLength maximum form length to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the input stream
     */
    public static void decodeUtf8To(InputStream in, Fields fields, int maxLength, int maxKeys)
        throws IOException
    {
        decodeUtf8To(in, fields::add, maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum form length to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the input stream
     * @deprecated use {@link #decodeUtf8To(InputStream, Fields, int, int)} instead.
     */
    @Deprecated(since = "12.0.17", forRemoval = true)
    public static void decodeUtf8To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
        throws IOException
    {
        decodeUtf8To(in, map::add, maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in InputSteam to read
     * @param adder Function to add parameters to
     * @param maxLength maximum form length to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the input stream
     */
    public static void decodeUtf8To(InputStream in, BiConsumer<String, String> adder, int maxLength, int maxKeys)
        throws IOException
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        String key = null;
        String value;

        int b;

        int totalLength = 0;
        int keys = 0;
        while ((b = in.read()) >= 0)
        {
            switch ((char)b)
            {
                case '&':
                    value = buffer.toCompleteString();
                    buffer.reset();
                    if (key != null)
                    {
                        adder.accept(key, value);
                        keys++;
                    }
                    else if (value != null && !value.isEmpty())
                    {
                        adder.accept(value, "");
                        keys++;
                    }
                    key = null;
                    checkMaxKeys(keys, maxKeys);
                    break;

                case '=':
                    if (key != null)
                    {
                        buffer.append((byte)b);
                        break;
                    }
                    key = buffer.toCompleteString();
                    buffer.reset();
                    break;

                case '+':
                    buffer.append((byte)' ');
                    break;

                case '%':
                    char code0 = (char)in.read();
                    char code1 = (char)in.read();
                    buffer.append(decodeHexByte(code0, code1));
                    break;

                default:
                    buffer.append((byte)b);
                    break;
            }
            checkMaxLength(++totalLength, maxLength);
        }

        if (key != null)
        {
            value = buffer.toCompleteString();
            buffer.reset();
            adder.accept(key, value);
            keys++;
        }
        else if (buffer.length() > 0)
        {
            adder.accept(buffer.toCompleteString(), "");
            keys++;
        }
        checkMaxKeys(keys, maxKeys);
    }

    public static void decodeUtf16To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys) throws IOException
    {
        decodeUtf16To(in, map::add, maxLength, maxKeys);
    }

    public static void decodeUtf16To(InputStream in, BiConsumer<String, String> adder, int maxLength, int maxKeys) throws IOException
    {
        InputStreamReader input = new InputStreamReader(in, StandardCharsets.UTF_16);
        StringWriter buf = new StringWriter(8192);
        IO.copy(input, buf, maxLength);

        decodeTo(buf.getBuffer().toString(), adder, StandardCharsets.UTF_16, maxKeys);
    }

    /**
     * @param charsetName The charset name for decoding or null for the default
     * @return A Charset to use for decoding.
     */
    public static Charset decodeCharset(String charsetName)
    {
        if (charsetName == null)
            return ENCODING;
        return Charset.forName(charsetName);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, Charset charset, int maxLength, int maxKeys)
        throws IOException
    {
        decodeTo(in, map::add, charset, maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param adder Function to add a parameter
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, BiConsumer<String, String> adder, Charset charset, int maxLength, int maxKeys)
        throws IOException
    {
        //no charset present, use the configured default
        if (charset == null)
            charset = ENCODING;

        if (StandardCharsets.UTF_8.equals(charset))
        {
            decodeUtf8To(in, adder, maxLength, maxKeys);
            return;
        }

        if (StandardCharsets.ISO_8859_1.equals(charset))
        {
            decode88591To(in, adder, maxLength, maxKeys);
            return;
        }

        if (StandardCharsets.UTF_16.equals(charset)) // Should be all 2 byte encodings
        {
            decodeUtf16To(in, adder, maxLength, maxKeys);
            return;
        }

        String key = null;
        String value;

        int c;

        int totalLength = 0;

        try (ByteArrayOutputStream2 output = new ByteArrayOutputStream2())
        {
            int keys = 0;
            int size;

            while ((c = in.read()) > 0)
            {
                switch ((char)c)
                {
                    case '&':
                        size = output.size();
                        value = size == 0 ? "" : output.toString(charset);
                        output.setCount(0);
                        if (key != null)
                        {
                            adder.accept(key, value);
                            keys++;
                        }
                        else if (value != null && !value.isEmpty())
                        {
                            adder.accept(value, "");
                            keys++;
                        }
                        key = null;
                        checkMaxKeys(keys, maxKeys);
                        break;
                    case '=':
                        if (key != null)
                        {
                            output.write(c);
                            break;
                        }
                        size = output.size();
                        key = size == 0 ? "" : output.toString(charset);
                        output.setCount(0);
                        break;
                    case '+':
                        output.write(' ');
                        break;
                    case '%':
                        int code0 = in.read();
                        int code1 = in.read();
                        output.write(decodeHexChar(code0, code1));
                        break;
                    default:
                        output.write(c);
                        break;
                }
                checkMaxLength(++totalLength, maxLength);
            }

            size = output.size();
            if (key != null)
            {
                value = size == 0 ? "" : output.toString(charset);
                output.setCount(0);
                adder.accept(key, value);
                keys++;
            }
            else if (size > 0)
            {
                adder.accept(output.toString(charset), "");
                keys++;
            }
            checkMaxKeys(keys, maxKeys);
        }
    }

    private static void checkMaxKeys(int size, int maxKeys)
    {
        if (maxKeys >= 0 && size > maxKeys)
            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", size, maxKeys));
    }

    private static void checkMaxKeys(MultiMap<String> map, int maxKeys)
    {
        int size = map.size();
        if (maxKeys >= 0 && size > maxKeys)
            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", size, maxKeys));
    }

    private static void checkMaxLength(int length, int maxLength)
    {
        if (maxLength >= 0 && length > maxLength)
            throw new IllegalStateException("Form is larger than max length " + maxLength);
    }

    /**
     * Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     *
     * @param encoded the encoded string to decode
     * @return the decoded string
     */
    public static String decodeString(String encoded)
    {
        return decodeString(encoded, 0, encoded.length(), ENCODING);
    }

    /**
     * Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     *
     * @param encoded the encoded string to decode
     * @param offset the offset in the encoded string to decode from
     * @param length the length of characters in the encoded string to decode
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public static String decodeString(String encoded, int offset, int length, Charset charset)
    {
        if (charset == null || StandardCharsets.UTF_8.equals(charset))
        {
            Utf8StringBuilder buffer = null;

            for (int i = 0; i < length; i++)
            {
                char c = encoded.charAt(offset + i);
                if (c > 0xff)
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuilder(length);
                        buffer.append(encoded, offset, i + 1);
                    }
                    else
                        buffer.append(c);
                }
                else if (c == '+')
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuilder(length);
                        buffer.append(encoded, offset, i);
                    }

                    buffer.append(' ');
                }
                else if (c == '%')
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuilder(length);
                        buffer.append(encoded, offset, i);
                    }

                    if ((i + 2) < length)
                    {
                        int o = offset + i + 1;
                        i += 2;
                        byte b = (byte)TypeUtil.parseInt(encoded, o, 2, 16);
                        buffer.append(b);
                    }
                    else
                    {
                        buffer.append(Utf8StringBuilder.REPLACEMENT);
                        i = length;
                    }
                }
                else if (buffer != null)
                    buffer.append(c);
            }

            if (buffer == null)
            {
                if (offset == 0 && encoded.length() == length)
                    return encoded;
                return encoded.substring(offset, offset + length);
            }

            return buffer.toCompleteString();
        }
        else
        {
            CharsetStringBuilder buffer = null;

            for (int i = 0; i < length; i++)
            {
                char c = encoded.charAt(offset + i);
                if (c > 0xff)
                {
                    if (buffer == null)
                    {
                        buffer = CharsetStringBuilder.forCharset(charset);
                        buffer.append(encoded, offset, i + 1);
                    }
                    else
                        buffer.append(c);
                }
                else if (c == '+')
                {
                    if (buffer == null)
                    {
                        buffer = CharsetStringBuilder.forCharset(charset);
                        buffer.append(encoded, offset, i);
                    }

                    buffer.append(' ');
                }
                else if (c == '%')
                {
                    if (buffer == null)
                    {
                        buffer = CharsetStringBuilder.forCharset(charset);
                        buffer.append(encoded, offset, i);
                    }

                    byte[] ba = new byte[length];
                    int n = 0;
                    while (c <= 0xff)
                    {
                        if (c == '%')
                        {
                            if (i + 2 < length)
                            {
                                int o = offset + i + 1;
                                i += 3;
                                ba[n] = (byte)TypeUtil.parseInt(encoded, o, 2, 16);
                                n++;
                            }
                            else
                            {
                                ba[n++] = (byte)'?';
                                i = length;
                            }
                        }
                        else if (c == '+')
                        {
                            ba[n++] = (byte)' ';
                            i++;
                        }
                        else
                        {
                            ba[n++] = (byte)c;
                            i++;
                        }

                        if (i >= length)
                            break;
                        c = encoded.charAt(offset + i);
                    }

                    i--;
                    String s = new String(ba, 0, n, charset);
                    buffer.append(s, 0, s.length());
                }
                else if (buffer != null)
                    buffer.append(c);
            }

            if (buffer == null)
            {
                if (offset == 0 && encoded.length() == length)
                    return encoded;
                return encoded.substring(offset, offset + length);
            }

            try
            {
                return buffer.build();
            }
            catch (CharacterCodingException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static char decodeHexChar(int hi, int lo)
    {
        try
        {
            return (char)((convertHexDigit(hi) << 4) + convertHexDigit(lo));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Not valid encoding '%" + (char)hi + (char)lo + "'");
        }
    }

    public static byte decodeHexByte(char hi, char lo)
    {
        try
        {
            return (byte)((convertHexDigit(hi) << 4) + convertHexDigit(lo));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Not valid encoding '%" + hi + lo + "'");
        }
    }

    private static void decodeHexByteTo(Utf8StringBuilder buffer, char hi, char lo)
    {
        buffer.append((byte)((convertHexDigit(hi) << 4) + convertHexDigit(lo)));
    }

    /**
     * Perform URL encoding.
     *
     * @param string the string to encode
     * @return encoded string.
     */
    public static String encodeString(String string)
    {
        return encodeString(string, ENCODING);
    }

    /**
     * Perform URL encoding.
     *
     * @param string the string to encode
     * @param charset the charset to use for encoding
     * @return encoded string.
     */
    public static String encodeString(String string, Charset charset)
    {
        if (charset == null)
            charset = ENCODING;
        byte[] bytes;
        bytes = string.getBytes(charset);

        byte[] encoded = new byte[bytes.length * 3];
        int n = 0;
        boolean noEncode = true;

        for (byte b : bytes)
        {
            if (b == ' ')
            {
                noEncode = false;
                encoded[n++] = (byte)'+';
            }
            else if (b >= 'a' && b <= 'z' ||
                b >= 'A' && b <= 'Z' ||
                b >= '0' && b <= '9' ||
                b == '-' || b == '.' || b == '_' || b == '~')
            {
                encoded[n++] = b;
            }
            else
            {
                noEncode = false;
                encoded[n++] = (byte)'%';
                byte nibble = (byte)((b & 0xf0) >> 4);
                if (nibble >= 10)
                    encoded[n++] = (byte)('A' + nibble - 10);
                else
                    encoded[n++] = (byte)('0' + nibble);
                nibble = (byte)(b & 0xf);
                if (nibble >= 10)
                    encoded[n++] = (byte)('A' + nibble - 10);
                else
                    encoded[n++] = (byte)('0' + nibble);
            }
        }

        if (noEncode)
            return string;

        return new String(encoded, 0, n, charset);
    }
}
