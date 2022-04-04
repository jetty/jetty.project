//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

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
                charset = StandardCharsets.UTF_8.toString();
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
                        if (val.length() > 0)
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
        decodeTo(content, map, charset, -1);
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
        if (charset == null)
            charset = ENCODING;

        if (StandardCharsets.UTF_8.equals(charset))
        {
            decodeUtf8To(content, 0, content.length(), map);
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
                        map.add(key, value);
                    }
                    else if (value != null && value.length() > 0)
                    {
                        map.add(value, "");
                    }
                    checkMaxKeys(map, maxKeys);
                    key = null;
                    value = null;
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
            map.add(key, value);
            checkMaxKeys(map, maxKeys);
        }
        else if (mark < content.length())
        {
            key = encoded
                ? decodeString(content, mark + 1, content.length() - mark - 1, charset)
                : content.substring(mark + 1);
            if (key != null && key.length() > 0)
            {
                map.add(key, "");
                checkMaxKeys(map, maxKeys);
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

    private static void decodeUtf8To(String query, int offset, int length, BiConsumer<String, String> adder)
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        String key = null;
        String value;

        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            char c = query.charAt(i);
            switch (c)
            {
                case '&':
                    value = buffer.toReplacedString();
                    buffer.reset();
                    if (key != null)
                    {
                        adder.accept(key, value);
                    }
                    else if (value != null && value.length() > 0)
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
                    key = buffer.toReplacedString();
                    buffer.reset();
                    break;

                case '+':
                    buffer.append((byte)' ');
                    break;

                case '%':
                    if (i + 2 < end)
                    {
                        char hi = query.charAt(++i);
                        char lo = query.charAt(++i);
                        buffer.append(decodeHexByte(hi, lo));
                    }
                    else
                    {
                        throw new Utf8Appendable.NotUtf8Exception("Incomplete % encoding");
                    }
                    break;

                default:
                    buffer.append(c);
                    break;
            }
        }

        if (key != null)
        {
            value = buffer.toReplacedString();
            buffer.reset();
            adder.accept(key, value);
        }
        else if (buffer.length() > 0)
        {
            adder.accept(buffer.toReplacedString(), "");
        }
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
        StringBuilder buffer = new StringBuilder();
        String key = null;
        String value;

        int b;

        int totalLength = 0;
        while ((b = in.read()) >= 0)
        {
            switch ((char)b)
            {
                case '&':
                    value = buffer.length() == 0 ? "" : buffer.toString();
                    buffer.setLength(0);
                    if (key != null)
                    {
                        map.add(key, value);
                    }
                    else if (value.length() > 0)
                    {
                        map.add(value, "");
                    }
                    key = null;
                    checkMaxKeys(map, maxKeys);
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
            value = buffer.length() == 0 ? "" : buffer.toString();
            buffer.setLength(0);
            map.add(key, value);
        }
        else if (buffer.length() > 0)
        {
            map.add(buffer.toString(), "");
        }
        checkMaxKeys(map, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum form length to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the input stream
     */
    public static void decodeUtf8To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
        throws IOException
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        String key = null;
        String value;

        int b;

        int totalLength = 0;
        while ((b = in.read()) >= 0)
        {
            switch ((char)b)
            {
                case '&':
                    value = buffer.toReplacedString();
                    buffer.reset();
                    if (key != null)
                    {
                        map.add(key, value);
                    }
                    else if (value != null && value.length() > 0)
                    {
                        map.add(value, "");
                    }
                    key = null;
                    checkMaxKeys(map, maxKeys);
                    break;

                case '=':
                    if (key != null)
                    {
                        buffer.append((byte)b);
                        break;
                    }
                    key = buffer.toReplacedString();
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
            value = buffer.toReplacedString();
            buffer.reset();
            map.add(key, value);
        }
        else if (buffer.length() > 0)
        {
            map.add(buffer.toReplacedString(), "");
        }
        checkMaxKeys(map, maxKeys);
    }

    public static void decodeUtf16To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys) throws IOException
    {
        InputStreamReader input = new InputStreamReader(in, StandardCharsets.UTF_16);
        StringWriter buf = new StringWriter(8192);
        IO.copy(input, buf, maxLength);

        decodeTo(buf.getBuffer().toString(), map, StandardCharsets.UTF_16, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to decode or -1 for no limit
     * @throws IOException if unable to decode the input stream
     * @deprecated use {@link #decodeTo(InputStream, MultiMap, Charset, int, int)} instead
     */
    @Deprecated(since = "10", forRemoval = true)
    public static void decodeTo(InputStream in, MultiMap<String> map, String charset, int maxLength, int maxKeys)
        throws IOException
    {
        if (charset == null)
        {
            if (ENCODING.equals(StandardCharsets.UTF_8))
                decodeUtf8To(in, map, maxLength, maxKeys);
            else
                decodeTo(in, map, ENCODING, maxLength, maxKeys);
        }
        else if (StringUtil.__UTF8.equalsIgnoreCase(charset))
            decodeUtf8To(in, map, maxLength, maxKeys);
        else if (StringUtil.__ISO_8859_1.equalsIgnoreCase(charset))
            decode88591To(in, map, maxLength, maxKeys);
        else if (StringUtil.__UTF16.equalsIgnoreCase(charset))
            decodeUtf16To(in, map, maxLength, maxKeys);
        else
            decodeTo(in, map, Charset.forName(charset), maxLength, maxKeys);
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
        //no charset present, use the configured default
        if (charset == null)
            charset = ENCODING;

        if (StandardCharsets.UTF_8.equals(charset))
        {
            decodeUtf8To(in, map, maxLength, maxKeys);
            return;
        }

        if (StandardCharsets.ISO_8859_1.equals(charset))
        {
            decode88591To(in, map, maxLength, maxKeys);
            return;
        }

        if (StandardCharsets.UTF_16.equals(charset)) // Should be all 2 byte encodings
        {
            decodeUtf16To(in, map, maxLength, maxKeys);
            return;
        }

        String key = null;
        String value;

        int c;

        int totalLength = 0;

        try (ByteArrayOutputStream2 output = new ByteArrayOutputStream2())
        {
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
                            map.add(key, value);
                        }
                        else if (value != null && value.length() > 0)
                        {
                            map.add(value, "");
                        }
                        key = null;
                        checkMaxKeys(map, maxKeys);
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
                map.add(key, value);
            }
            else if (size > 0)
            {
                map.add(output.toString(charset), "");
            }
            checkMaxKeys(map, maxKeys);
        }
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
            Utf8StringBuffer buffer = null;

            for (int i = 0; i < length; i++)
            {
                char c = encoded.charAt(offset + i);
                if (c > 0xff)
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded, offset, offset + i + 1);
                    }
                    else
                        buffer.getStringBuffer().append(c);
                }
                else if (c == '+')
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded, offset, offset + i);
                    }

                    buffer.getStringBuffer().append(' ');
                }
                else if (c == '%')
                {
                    if (buffer == null)
                    {
                        buffer = new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded, offset, offset + i);
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
                        buffer.getStringBuffer().append(Utf8Appendable.REPLACEMENT);
                        i = length;
                    }
                }
                else if (buffer != null)
                    buffer.getStringBuffer().append(c);
            }

            if (buffer == null)
            {
                if (offset == 0 && encoded.length() == length)
                    return encoded;
                return encoded.substring(offset, offset + length);
            }

            return buffer.toReplacedString();
        }
        else
        {
            StringBuffer buffer = null;

            for (int i = 0; i < length; i++)
            {
                char c = encoded.charAt(offset + i);
                if (c > 0xff)
                {
                    if (buffer == null)
                    {
                        buffer = new StringBuffer(length);
                        buffer.append(encoded, offset, offset + i + 1);
                    }
                    else
                        buffer.append(c);
                }
                else if (c == '+')
                {
                    if (buffer == null)
                    {
                        buffer = new StringBuffer(length);
                        buffer.append(encoded, offset, offset + i);
                    }

                    buffer.append(' ');
                }
                else if (c == '%')
                {
                    if (buffer == null)
                    {
                        buffer = new StringBuffer(length);
                        buffer.append(encoded, offset, offset + i);
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
                    buffer.append(new String(ba, 0, n, charset));
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

            return buffer.toString();
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

    private static byte decodeHexByte(char hi, char lo)
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
