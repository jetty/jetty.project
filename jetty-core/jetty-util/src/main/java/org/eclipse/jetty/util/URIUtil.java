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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * URI Utility methods.
 * <p>
 * This class assists with the decoding and encoding or HTTP URI's.
 * It differs from the java.net.URL class as it does not provide
 * communications ability, but it does assist with query string
 * formatting.
 * </p>
 *
 * @see UrlEncoded
 */
public class URIUtil
    implements Cloneable
{
    private static final Logger LOG = LoggerFactory.getLogger(URIUtil.class);
    public static final String SLASH = "/";
    public static final String HTTP = "http";
    public static final String HTTPS = "https";

    // Use UTF-8 as per http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
    public static final Charset __CHARSET = StandardCharsets.UTF_8;

    /**
     * The characters that are supported by the URI class and that can be decoded by {@link #normalizePath(String)}
     */
    public static final boolean[] __uriSupportedCharacters = new boolean[]
    {
        false, // 0x00 is illegal
        false, // 0x01 is illegal
        false, // 0x02 is illegal
        false, // 0x03 is illegal
        false, // 0x04 is illegal
        false, // 0x05 is illegal
        false, // 0x06 is illegal
        false, // 0x07 is illegal
        false, // 0x08 is illegal
        false, // 0x09 is illegal
        false, // 0x0a is illegal
        false, // 0x0b is illegal
        false, // 0x0c is illegal
        false, // 0x0d is illegal
        false, // 0x0e is illegal
        false, // 0x0f is illegal
        false, // 0x10 is illegal
        false, // 0x11 is illegal
        false, // 0x12 is illegal
        false, // 0x13 is illegal
        false, // 0x14 is illegal
        false, // 0x15 is illegal
        false, // 0x16 is illegal
        false, // 0x17 is illegal
        false, // 0x18 is illegal
        false, // 0x19 is illegal
        false, // 0x1a is illegal
        false, // 0x1b is illegal
        false, // 0x1c is illegal
        false, // 0x1d is illegal
        false, // 0x1e is illegal
        false, // 0x1f is illegal
        false, // 0x20 is illegal
        true,  // 0x21
        false, // 0x22 is illegal
        false, // # is special
        true,  // 0x24
        false, // % must remain encoded
        true,  // 0x26
        true,  // 0x27
        true,  // 0x28
        true,  // 0x29
        true,  // 0x2a
        true,  // 0x2b
        true,  // 0x2c
        true,  // 0x2d
        true,  // 0x2e
        false, // / is a delimiter
        true,  // 0x30
        true,  // 0x31
        true,  // 0x32
        true,  // 0x33
        true,  // 0x34
        true,  // 0x35
        true,  // 0x36
        true,  // 0x37
        true,  // 0x38
        true,  // 0x39
        true,  // 0x3a
        false, // ; is path parameter
        false, // 0x3c is illegal
        true,  // 0x3d
        false, // 0x3e is illegal
        false, // ? is special
        true,  // 0x40
        true,  // 0x41
        true,  // 0x42
        true,  // 0x43
        true,  // 0x44
        true,  // 0x45
        true,  // 0x46
        true,  // 0x47
        true,  // 0x48
        true,  // 0x49
        true,  // 0x4a
        true,  // 0x4b
        true,  // 0x4c
        true,  // 0x4d
        true,  // 0x4e
        true,  // 0x4f
        true,  // 0x50
        true,  // 0x51
        true,  // 0x52
        true,  // 0x53
        true,  // 0x54
        true,  // 0x55
        true,  // 0x56
        true,  // 0x57
        true,  // 0x58
        true,  // 0x59
        true,  // 0x5a
        false, // 0x5b is illegal
        false, // 0x5c is illegal
        false, // 0x5d is illegal
        false, // 0x5e is illegal
        true,  // 0x5f
        false, // 0x60 is illegal
        true,  // 0x61
        true,  // 0x62
        true,  // 0x63
        true,  // 0x64
        true,  // 0x65
        true,  // 0x66
        true,  // 0x67
        true,  // 0x68
        true,  // 0x69
        true,  // 0x6a
        true,  // 0x6b
        true,  // 0x6c
        true,  // 0x6d
        true,  // 0x6e
        true,  // 0x6f
        true,  // 0x70
        true,  // 0x71
        true,  // 0x72
        true,  // 0x73
        true,  // 0x74
        true,  // 0x75
        true,  // 0x76
        true,  // 0x77
        true,  // 0x78
        true,  // 0x79
        true,  // 0x7a
        false, // 0x7b is illegal
        false, // 0x7c is illegal
        false, // 0x7d is illegal
        true,  // 0x7e
        false, // 0x7f is illegal
    };

    private URIUtil()
    {
    }

    /**
     * Encode a URI path.
     * This is the same encoding offered by URLEncoder, except that
     * the '/' character is not encoded.
     *
     * @param path The path the encode
     * @return The encoded path
     */
    public static String encodePath(String path)
    {
        if (path == null || path.length() == 0)
            return path;

        StringBuilder buf = encodePath(null, path, 0);
        return buf == null ? path : buf.toString();
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodePath(StringBuilder buf, String path)
    {
        return encodePath(buf, path, 0);
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    private static StringBuilder encodePath(StringBuilder buf, String path, int offset)
    {
        byte[] bytes = null;
        if (buf == null)
        {
            loop:
            for (int i = offset; i < path.length(); i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                    case '?':
                    case ';':
                    case '#':
                    case '"':
                    case '\'':
                    case '<':
                    case '>':
                    case ' ':
                    case '[':
                    case '\\':
                    case ']':
                    case '^':
                    case '`':
                    case '{':
                    case '|':
                    case '}':
                        buf = new StringBuilder(path.length() * 2);
                        break loop;
                    default:
                        if (c < 0x20 || c >= 0x7f)
                        {
                            bytes = path.getBytes(URIUtil.__CHARSET);
                            buf = new StringBuilder(path.length() * 2);
                            break loop;
                        }
                }
            }
            if (buf == null)
                return null;
        }

        int i;

        loop:
        for (i = offset; i < path.length(); i++)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '%':
                    buf.append("%25");
                    continue;
                case '?':
                    buf.append("%3F");
                    continue;
                case ';':
                    buf.append("%3B");
                    continue;
                case '#':
                    buf.append("%23");
                    continue;
                case '"':
                    buf.append("%22");
                    continue;
                case '\'':
                    buf.append("%27");
                    continue;
                case '<':
                    buf.append("%3C");
                    continue;
                case '>':
                    buf.append("%3E");
                    continue;
                case ' ':
                    buf.append("%20");
                    continue;
                case '[':
                    buf.append("%5B");
                    continue;
                case '\\':
                    buf.append("%5C");
                    continue;
                case ']':
                    buf.append("%5D");
                    continue;
                case '^':
                    buf.append("%5E");
                    continue;
                case '`':
                    buf.append("%60");
                    continue;
                case '{':
                    buf.append("%7B");
                    continue;
                case '|':
                    buf.append("%7C");
                    continue;
                case '}':
                    buf.append("%7D");
                    continue;

                default:
                    if (c < 0x20 || c >= 0x7f)
                    {
                        bytes = path.getBytes(URIUtil.__CHARSET);
                        break loop;
                    }
                    buf.append(c);
            }
        }

        if (bytes != null)
        {
            for (; i < bytes.length; i++)
            {
                byte c = bytes[i];
                switch (c)
                {
                    case '%':
                        buf.append("%25");
                        continue;
                    case '?':
                        buf.append("%3F");
                        continue;
                    case ';':
                        buf.append("%3B");
                        continue;
                    case '#':
                        buf.append("%23");
                        continue;
                    case '"':
                        buf.append("%22");
                        continue;
                    case '\'':
                        buf.append("%27");
                        continue;
                    case '<':
                        buf.append("%3C");
                        continue;
                    case '>':
                        buf.append("%3E");
                        continue;
                    case ' ':
                        buf.append("%20");
                        continue;
                    case '[':
                        buf.append("%5B");
                        continue;
                    case '\\':
                        buf.append("%5C");
                        continue;
                    case ']':
                        buf.append("%5D");
                        continue;
                    case '^':
                        buf.append("%5E");
                        continue;
                    case '`':
                        buf.append("%60");
                        continue;
                    case '{':
                        buf.append("%7B");
                        continue;
                    case '|':
                        buf.append("%7C");
                        continue;
                    case '}':
                        buf.append("%7D");
                        continue;
                    default:
                        if (c < 0x20 || c >= 0x7f)
                        {
                            buf.append('%');
                            TypeUtil.toHex(c, buf);
                        }
                        else
                            buf.append((char)c);
                }
            }
        }

        return buf;
    }

    /**
     * Encode a raw URI String and convert any raw spaces to
     * their "%20" equivalent.
     *
     * @param str input raw string
     * @return output with spaces converted to "%20"
     */
    public static String encodeSpaces(String str)
    {
        return StringUtil.replace(str, " ", "%20");
    }

    /**
     * Encode a raw String and convert any specific characters to their URI encoded equivalent.
     *
     * @param str input raw string
     * @param charsToEncode the list of raw characters that need to be encoded (if encountered)
     * @return output with specified characters encoded.
     */
    @SuppressWarnings("Duplicates")
    public static String encodeSpecific(String str, String charsToEncode)
    {
        if ((str == null) || (str.length() == 0))
            return null;

        if ((charsToEncode == null) || (charsToEncode.length() == 0))
            return str;

        char[] find = charsToEncode.toCharArray();
        int len = str.length();
        StringBuilder ret = new StringBuilder((int)(len * 0.20d));
        for (int i = 0; i < len; i++)
        {
            char c = str.charAt(i);
            boolean escaped = false;
            for (char f : find)
            {
                if (c == f)
                {
                    escaped = true;
                    ret.append('%');
                    int d = 0xf & ((0xF0 & c) >> 4);
                    ret.append((char)((d > 9 ? ('A' - 10) : '0') + d));
                    d = 0xf & c;
                    ret.append((char)((d > 9 ? ('A' - 10) : '0') + d));
                    break;
                }
            }
            if (!escaped)
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    /**
     * Decode a raw String and convert any specific URI encoded sequences into characters.
     *
     * @param str input raw string
     * @param charsToDecode the list of raw characters that need to be decoded (if encountered), leaving all other encoded sequences alone.
     * @return output with specified characters decoded.
     */
    @SuppressWarnings("Duplicates")
    public static String decodeSpecific(String str, String charsToDecode)
    {
        if ((str == null) || (str.length() == 0))
            return null;

        if ((charsToDecode == null) || (charsToDecode.length() == 0))
            return str;

        int idx = str.indexOf('%');
        if (idx == -1)
        {
            // no hits
            return str;
        }

        char[] find = charsToDecode.toCharArray();
        int len = str.length();
        Utf8StringBuilder ret = new Utf8StringBuilder(len);
        ret.append(str, 0, idx);

        for (int i = idx; i < len; i++)
        {
            char c = str.charAt(i);
            switch (c)
            {
                case '%':
                    if ((i + 2) < len)
                    {
                        char u = str.charAt(i + 1);
                        char l = str.charAt(i + 2);
                        char result = (char)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(l)));
                        boolean decoded = false;
                        for (char f : find)
                        {
                            if (f == result)
                            {
                                ret.append(result);
                                decoded = true;
                                break;
                            }
                        }
                        if (decoded)
                        {
                            i += 2;
                        }
                        else
                        {
                            ret.append(c);
                        }
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad URI % encoding");
                    }
                    break;
                default:
                    ret.append(c);
                    break;
            }
        }
        return ret.toString();
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @param encode String of characters to encode. % is always encoded.
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodeString(StringBuilder buf,
                                             String path,
                                             String encode)
    {
        if (buf == null)
        {
            for (int i = 0; i < path.length(); i++)
            {
                char c = path.charAt(i);
                if (c == '%' || encode.indexOf(c) >= 0)
                {
                    buf = new StringBuilder(path.length() << 1);
                    break;
                }
            }
            if (buf == null)
                return null;
        }

        for (int i = 0; i < path.length(); i++)
        {
            char c = path.charAt(i);
            if (c == '%' || encode.indexOf(c) >= 0)
            {
                buf.append('%');
                StringUtil.append(buf, (byte)(0xff & c), 16);
            }
            else
                buf.append(c);
        }

        return buf;
    }

    /**
     * Decode a URI path and strip parameters
     * @see #normalizePath(String)
     * @see #canonicalPath(String)
     */
    public static String decodePath(String path)
    {
        return decodePath(path, 0, path.length());
    }

    /**
     * Decode a URI path and strip parameters of UTF-8 path
     * @see #normalizePath(String, int, int)
     * @see #canonicalPath(String)
     */
    public static String decodePath(String path, int offset, int length)
    {
        try
        {
            Utf8StringBuilder builder = null;
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }
                        if ((i + 2) < end)
                        {
                            char u = path.charAt(i + 1);
                            if (u == 'u')
                            {
                                // UTF16 encoding is only supported with UriCompliance.Violation.UTF16_ENCODINGS.
                                // This is wrong. This is a codepoint not a char
                                builder.append((char)(0xffff & TypeUtil.parseInt(path, i + 2, 4, 16)));
                                i += 5;
                            }
                            else
                            {
                                builder.append((byte)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2)))));
                                i += 2;
                            }
                        }
                        else
                        {
                            throw new IllegalArgumentException("Bad URI % encoding");
                        }

                        break;

                    case ';':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }

                        while (++i < end)
                        {
                            if (path.charAt(i) == '/')
                            {
                                builder.append('/');
                                break;
                            }
                        }

                        break;

                    default:
                        if (builder != null)
                            builder.append(c);
                        break;
                }
            }

            if (builder != null)
                return builder.toString();
            if (offset == 0 && length == path.length())
                return path;
            return path.substring(offset, end);
        }
        catch (NotUtf8Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} {}", path.substring(offset, offset + length), e.toString());
            return decodeISO88591Path(path, offset, length);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("cannot decode URI", e);
        }
    }

    private static boolean safe(Utf8StringBuilder builder, int code)
    {
        if (code >= __uriSupportedCharacters.length || __uriSupportedCharacters[code])
            return true;

        builder.append('%');
        int d = (0xF0 & code) >> 4;
        builder.append((char)('0' + d));
        d = 0xF & code;
        builder.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        return false;
    }

    /**
     * Decode only the safe characters in a URI path and strip parameters of UTF-8 path.
     * Safe characters are ones that are not special delimiters and that can be
     * passed to the JVM {@link URI} class.  For example the <code>%2f</code> will
     * be left encoded in the normalized form <code>%2F</code>.
     * @see #decodePath(String)
     * @see #canonicalPath(String)
     */
    public static String normalizePath(String path)
    {
        return normalizePath(path, 0, path.length());
    }

    /**
     * Decode only the safe characters in a URI path and strip parameters of UTF-8 path.
     * Safe characters are ones that are not special delimiters and that can be
     * passed to the JVM {@link URI} class. For example the <code>%2f</code> will
     * be left encoded in the normalized form <code>%2F</code>.
     * @see #decodePath(String, int, int)
     * @see #canonicalPath(String)
     */
    public static String normalizePath(String path, int offset, int length)
    {
        try
        {
            Utf8StringBuilder builder = null;
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }
                        if ((i + 2) < end)
                        {
                            char u = path.charAt(i + 1);
                            if (u == 'u')
                            {
                                // UTF16 encoding is only supported with UriCompliance.Violation.UTF16_ENCODINGS.
                                int code = TypeUtil.parseInt(path, i + 2, 4, 16);
                                if (safe(builder, code))
                                {
                                    char[] chars = Character.toChars(code);
                                    for (char ch : chars)
                                        builder.append(ch);
                                }
                                i += 5;
                            }
                            else
                            {
                                int code = TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2));
                                if (safe(builder, code))
                                    builder.append((byte)(0xff & code));
                                i += 2;
                            }
                        }
                        else
                        {
                            throw new IllegalArgumentException("Bad URI % encoding");
                        }

                        break;

                    case ';':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }

                        while (++i < end)
                        {
                            if (path.charAt(i) == '/')
                            {
                                builder.append('/');
                                break;
                            }
                        }

                        break;

                    default:
                        if (builder != null)
                            builder.append(c);
                        break;
                }
            }

            if (builder != null)
                return builder.toString();
            if (offset == 0 && length == path.length())
                return path;
            return path.substring(offset, end);
        }
        catch (NotUtf8Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} {}", path.substring(offset, offset + length), e.toString());
            return decodeISO88591Path(path, offset, length);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("cannot decode URI", e);
        }
    }

    /* Decode a URI path and strip parameters of ISO-8859-1 path
     */
    private static String decodeISO88591Path(String path, int offset, int length)
    {
        StringBuilder builder = null;
        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '%':
                    if (builder == null)
                    {
                        builder = new StringBuilder(path.length());
                        builder.append(path, offset, i - offset);
                    }
                    if ((i + 2) < end)
                    {
                        char u = path.charAt(i + 1);
                        if (u == 'u')
                        {
                            // UTF16 encoding is only supported with UriCompliance.Violation.UTF16_ENCODINGS.
                            builder.append((char)(0xffff & TypeUtil.parseInt(path, i + 2, 4, 16)));
                            i += 5;
                        }
                        else
                        {
                            builder.append((char)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2)))));
                            i += 2;
                        }
                    }
                    else
                    {
                        throw new IllegalArgumentException();
                    }

                    break;

                case ';':
                    if (builder == null)
                    {
                        builder = new StringBuilder(path.length());
                        builder.append(path, offset, i - offset);
                    }
                    while (++i < end)
                    {
                        if (path.charAt(i) == '/')
                        {
                            builder.append('/');
                            break;
                        }
                    }
                    break;

                default:
                    if (builder != null)
                        builder.append(c);
                    break;
            }
        }

        if (builder != null)
            return builder.toString();
        if (offset == 0 && length == path.length())
            return path;
        return path.substring(offset, end);
    }

    /**
     * Add two encoded URI path segments.
     * Handles null and empty paths, path and query params
     * (eg ?a=b or ;JSESSIONID=xxx) and avoids duplicate '/'
     *
     * @param p1 URI path segment (should be encoded)
     * @param p2 URI path segment (should be encoded)
     * @return Legally combined path segments.
     */
    public static String addEncodedPaths(String p1, String p2)
    {
        if (p1 == null || p1.length() == 0)
        {
            if (p1 != null && p2 == null)
                return p1;
            return p2;
        }
        if (p2 == null || p2.length() == 0)
            return p1;

        int split = p1.indexOf(';');
        if (split < 0)
            split = p1.indexOf('?');
        if (split == 0)
            return p2 + p1;
        if (split < 0)
            split = p1.length();

        StringBuilder buf = new StringBuilder(p1.length() + p2.length() + 2);
        buf.append(p1);

        if (buf.charAt(split - 1) == '/')
        {
            if (p2.startsWith(URIUtil.SLASH))
            {
                buf.deleteCharAt(split - 1);
                buf.insert(split - 1, p2);
            }
            else
                buf.insert(split, p2);
        }
        else
        {
            if (p2.startsWith(URIUtil.SLASH))
                buf.insert(split, p2);
            else
            {
                buf.insert(split, '/');
                buf.insert(split + 1, p2);
            }
        }

        return buf.toString();
    }

    /**
     * Add two Decoded URI path segments.
     * Handles null and empty paths.  Path and query params (eg ?a=b or
     * ;JSESSIONID=xxx) are not handled
     *
     * @param p1 URI path segment (should be decoded)
     * @param p2 URI path segment (should be decoded)
     * @return Legally combined path segments.
     */
    public static String addPaths(String p1, String p2)
    {
        if (p1 == null || p1.length() == 0)
        {
            if (p1 != null && p2 == null)
                return p1;
            return p2;
        }
        if (p2 == null || p2.length() == 0)
            return p1;

        boolean p1EndsWithSlash = p1.endsWith(SLASH);
        boolean p2StartsWithSlash = p2.startsWith(SLASH);

        if (p1EndsWithSlash && p2StartsWithSlash)
        {
            if (p2.length() == 1)
                return p1;
            if (p1.length() == 1)
                return p2;
        }

        StringBuilder buf = new StringBuilder(p1.length() + p2.length() + 2);
        buf.append(p1);

        if (p1.endsWith(SLASH))
        {
            if (p2.startsWith(SLASH))
                buf.setLength(buf.length() - 1);
        }
        else
        {
            if (!p2.startsWith(SLASH))
                buf.append(SLASH);
        }
        buf.append(p2);

        return buf.toString();
    }

    /** Add a path and a query string
     * @param path The path which may already contain contain a query
     * @param query The query string or null if no query to be added
     * @return The path with any non null query added after a '?' or '&amp;' as appropriate.
     */
    public static String addPathQuery(String path, String query)
    {
        if (query == null)
            return path;
        if (path.indexOf('?') >= 0)
            return path + '&' + query;
        return path + '?' + query;
    }

    /**
     * Given a URI, attempt to get the last segment.
     * <p>
     * If this is a {@code jar:file://} style URI, then
     * the JAR filename is returned (not the deep {@code !/path} location)
     * </p>
     *
     * @param uri the URI to look in
     * @return the last segment.
     */
    public static String getUriLastPathSegment(URI uri)
    {
        String ssp = uri.getSchemeSpecificPart();
        // strip off deep jar:file: reference information
        int idx = ssp.indexOf("!/");
        if (idx != -1)
        {
            ssp = ssp.substring(0, idx);
        }

        // Strip off trailing '/' if present
        if (ssp.endsWith("/"))
        {
            ssp = ssp.substring(0, ssp.length() - 1);
        }

        // Only interested in last segment
        idx = ssp.lastIndexOf('/');
        if (idx != -1)
        {
            ssp = ssp.substring(idx + 1);
        }

        return ssp;
    }

    /**
     * Return the parent Path.
     * Treat a URI like a directory path and return the parent directory.
     *
     * @param p the path to return a parent reference to
     * @return the parent path of the URI
     */
    public static String parentPath(String p)
    {
        if (p == null || URIUtil.SLASH.equals(p))
            return null;
        int slash = p.lastIndexOf('/', p.length() - 2);
        if (slash >= 0)
            return p.substring(0, slash + 1);
        return null;
    }

    /**
     * Convert a partial URI to a canonical form.
     * <p>
     * All segments of "." and ".." are factored out.
     * Null is returned if the path tries to .. above its root.
     * </p>
     *
     * @param uri the encoded URI from the path onwards, which may contain query strings and/or fragments
     * @return the canonical path, or null if path traversal above root.
     * @see #canonicalPath(String)
     */
    public static String canonicalURI(String uri)
    {
        if (uri == null || uri.isEmpty())
            return uri;

        boolean slash = true;
        int end = uri.length();
        int i = 0;

        // Initially just loop looking if we may need to normalize
        loop: while (i < end)
        {
            char c = uri.charAt(i);
            switch (c)
            {
                case '/':
                    slash = true;
                    break;

                case '.':
                    if (slash)
                        break loop;
                    slash = false;
                    break;

                case '?':
                case '#':
                    // Nothing to normalize so return original path
                    return uri;

                default:
                    slash = false;
            }

            i++;
        }

        // Nothing to normalize so return original path
        if (i == end)
            return uri;

        // We probably need to normalize, so copy to path so far into builder
        StringBuilder canonical = new StringBuilder(uri.length());
        canonical.append(uri, 0, i);

        // Loop looking for single and double dot segments
        int dots = 1;
        i++;
        loop : while (i < end)
        {
            char c = uri.charAt(i);
            switch (c)
            {
                case '/':
                    if (doDotsSlash(canonical, dots))
                        return null;
                    slash = true;
                    dots = 0;
                    break;

                case '?':
                case '#':
                    // finish normalization at a query
                    break loop;

                case '.':
                    // Count dots only if they are leading in the segment
                    if (dots > 0)
                        dots++;
                    else if (slash)
                        dots = 1;
                    else
                        canonical.append('.');
                    slash = false;
                    break;

                default:
                    // Add leading dots to the path
                    while (dots-- > 0)
                        canonical.append('.');
                    canonical.append(c);
                    dots = 0;
                    slash = false;
            }
            i++;
        }

        // process any remaining dots
        if (doDots(canonical, dots))
            return null;

        // append any query
        if (i < end)
            canonical.append(uri, i, end);

        return canonical.toString();
    }

    /**
     * @param path the encoded URI from the path onwards, which may contain query strings and/or fragments
     * @return the canonical path, or null if path traversal above root.
     * @deprecated Use {@link #canonicalURI(String)}
     */
    @Deprecated
    public static String canonicalEncodedPath(String path)
    {
        return canonicalURI(path);
    }

    /**
     * Convert a decoded URI path to a canonical form.
     * <p>
     * All segments of "." and ".." are factored out.
     * Null is returned if the path tries to .. above its root.
     * </p>
     *
     * @param path the decoded URI path to convert. Any special characters (e.g. '?', "#") are assumed to be part of
     * the path segments.
     * @return the canonical path, or null if path traversal above root.
     * @see #canonicalURI(String)
     * @see #normalizePath(String)
     * @see #decodePath(String)
     */
    public static String canonicalPath(String path)
    {
        if (path == null || path.isEmpty())
            return path;

        boolean slash = true;
        int end = path.length();
        int i = 0;

        // Initially just loop looking if we may need to normalize
        loop: while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '/':
                    slash = true;
                    break;

                case '.':
                    if (slash)
                        break loop;
                    slash = false;
                    break;

                default:
                    slash = false;
            }

            i++;
        }

        // Nothing to normalize so return original path
        if (i == end)
            return path;

        // We probably need to normalize, so copy to path so far into builder
        StringBuilder canonical = new StringBuilder(path.length());
        canonical.append(path, 0, i);

        // Loop looking for single and double dot segments
        int dots = 1;
        i++;
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '/':
                    if (doDotsSlash(canonical, dots))
                        return null;
                    slash = true;
                    dots = 0;
                    break;

                case '.':
                    // Count dots only if they are leading in the segment
                    if (dots > 0)
                        dots++;
                    else if (slash)
                        dots = 1;
                    else
                        canonical.append('.');
                    slash = false;
                    break;

                default:
                    // Add leading dots to the path
                    while (dots-- > 0)
                        canonical.append('.');
                    canonical.append(c);
                    dots = 0;
                    slash = false;
            }
            i++;
        }

        // process any remaining dots
        if (doDots(canonical, dots))
            return null;

        return canonical.toString();
    }

    private static boolean doDots(StringBuilder canonical, int dots)
    {
        switch (dots)
        {
            case 0:
            case 1:
                break;
            case 2:
                if (canonical.length() < 2)
                    return true;
                canonical.setLength(canonical.length() - 1);
                canonical.setLength(canonical.lastIndexOf("/") + 1);
                break;
            default:
                while (dots-- > 0)
                    canonical.append('.');
        }
        return false;
    }

    private static boolean doDotsSlash(StringBuilder canonical, int dots)
    {
        switch (dots)
        {
            case 0:
                canonical.append('/');
                break;
            case 1:
                break;
            case 2:
                if (canonical.length() < 2)
                    return true;
                canonical.setLength(canonical.length() - 1);
                canonical.setLength(canonical.lastIndexOf("/") + 1);
                break;
            default:
                while (dots-- > 0)
                    canonical.append('.');
                canonical.append('/');
        }
        return false;
    }

    /**
     * Convert a path to a compact form.
     * All instances of "//" and "///" etc. are factored out to single "/"
     *
     * @param path the path to compact
     * @return the compacted path
     */
    public static String compactPath(String path)
    {
        if (path == null || path.length() == 0)
            return path;

        int state = 0;
        int end = path.length();
        int i = 0;

        loop:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '?':
                    return path;
                case '/':
                    state++;
                    if (state == 2)
                        break loop;
                    break;
                default:
                    state = 0;
            }
            i++;
        }

        if (state < 2)
            return path;

        StringBuilder buf = new StringBuilder(path.length());
        buf.append(path, 0, i);

        loop2:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '?':
                    buf.append(path, i, end);
                    break loop2;
                case '/':
                    if (state++ == 0)
                        buf.append(c);
                    break;
                default:
                    state = 0;
                    buf.append(c);
            }
            i++;
        }

        return buf.toString();
    }

    /**
     * @param uri URI
     * @return True if the uri has a scheme
     */
    public static boolean hasScheme(String uri)
    {
        for (int i = 0; i < uri.length(); i++)
        {
            char c = uri.charAt(i);
            if (c == ':')
                return true;
            if (!(c >= 'a' && c <= 'z' ||
                c >= 'A' && c <= 'Z' ||
                (i > 0 && (c >= '0' && c <= '9' ||
                    c == '.' ||
                    c == '+' ||
                    c == '-'))))
            {
                break;
            }
        }
        return false;
    }

    /**
     * Create a new URI from the arguments, handling IPv6 host encoding and default ports
     *
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @param path the URI path
     * @param query the URI query
     * @return A String URI
     */
    public static String newURI(String scheme, String server, int port, String path, String query)
    {
        StringBuilder builder = newURIBuilder(scheme, server, port);
        builder.append(path);
        if (query != null && query.length() > 0)
            builder.append('?').append(query);
        return builder.toString();
    }

    /**
     * Create a new URI StringBuilder from the arguments, handling IPv6 host encoding and default ports
     *
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @return a StringBuilder containing URI prefix
     */
    public static StringBuilder newURIBuilder(String scheme, String server, int port)
    {
        StringBuilder builder = new StringBuilder();
        appendSchemeHostPort(builder, scheme, server, port);
        return builder;
    }

    /**
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     *
     * @param url StringBuilder to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuilder url, String scheme, String server, int port)
    {
        url.append(scheme).append("://").append(HostPort.normalizeHost(server));

        if (port > 0)
        {
            switch (scheme)
            {
                case "http":
                    if (port != 80)
                        url.append(':').append(port);
                    break;

                case "https":
                    if (port != 443)
                        url.append(':').append(port);
                    break;

                default:
                    url.append(':').append(port);
            }
        }
    }

    /**
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     *
     * @param url StringBuffer to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuffer url, String scheme, String server, int port)
    {
        synchronized (url)
        {
            url.append(scheme).append("://").append(HostPort.normalizeHost(server));

            if (port > 0)
            {
                switch (scheme)
                {
                    case "http":
                        if (port != 80)
                            url.append(':').append(port);
                        break;

                    case "https":
                        if (port != 443)
                            url.append(':').append(port);
                        break;

                    default:
                        url.append(':').append(port);
                }
            }
        }
    }

    public static boolean equalsIgnoreEncodings(String uriA, String uriB)
    {
        int lenA = uriA.length();
        int lenB = uriB.length();
        int a = 0;
        int b = 0;

        while (a < lenA && b < lenB)
        {
            int oa = uriA.charAt(a++);
            int ca = oa;
            if (ca == '%')
            {
                ca = lenientPercentDecode(uriA, a);
                if (ca == (-1))
                {
                    ca = '%';
                }
                else
                {
                    a += 2;
                }
            }

            int ob = uriB.charAt(b++);
            int cb = ob;
            if (cb == '%')
            {
                cb = lenientPercentDecode(uriB, b);
                if (cb == (-1))
                {
                    cb = '%';
                }
                else
                {
                    b += 2;
                }
            }

            // Don't match on encoded slash
            if (ca == '/' && oa != ob)
                return false;

            if (ca != cb)
                return false;
        }
        return a == lenA && b == lenB;
    }

    private static int lenientPercentDecode(String str, int offset)
    {
        if (offset >= str.length())
            return -1;

        if (StringUtil.isHex(str, offset, 2))
        {
            return TypeUtil.parseInt(str, offset, 2, 16);
        }
        else
        {
            return -1;
        }
    }

    public static boolean equalsIgnoreEncodings(URI uriA, URI uriB)
    {
        if (uriA.equals(uriB))
            return true;

        if (uriA.getScheme() == null)
        {
            if (uriB.getScheme() != null)
                return false;
        }
        else if (!uriA.getScheme().equalsIgnoreCase(uriB.getScheme()))
            return false;

        if ("jar".equalsIgnoreCase(uriA.getScheme()))
        {
            // at this point we know that both uri's are "jar:"
            URI uriAssp = URI.create(uriA.getSchemeSpecificPart());
            URI uriBssp = URI.create(uriB.getSchemeSpecificPart());
            return equalsIgnoreEncodings(uriAssp, uriBssp);
        }

        if (uriA.getAuthority() == null)
        {
            if (uriB.getAuthority() != null)
                return false;
        }
        else if (!uriA.getAuthority().equals(uriB.getAuthority()))
            return false;

        return equalsIgnoreEncodings(uriA.getPath(), uriB.getPath());
    }

    /**
     * @param uri A URI to add the path to
     * @param path A decoded path element
     * @return URI with path added.
     */
    public static URI addPath(URI uri, String path)
    {
        String base = uri.toASCIIString();
        StringBuilder buf = new StringBuilder(base.length() + path.length() * 3);
        buf.append(base);
        if (buf.charAt(base.length() - 1) != '/')
            buf.append('/');

        int offset = path.charAt(0) == '/' ? 1 : 0;
        encodePath(buf, path, offset);

        return URI.create(buf.toString());
    }

    /**
     * Combine two query strings into one. Each query string should not contain the beginning '?' character, but
     * may contain multiple parameters separated by the '{@literal &}' character.
     * @param query1 the first query string.
     * @param query2 the second query string.
     * @return the combination of the two query strings.
     */
    public static String addQueries(String query1, String query2)
    {
        if (StringUtil.isEmpty(query1))
            return query2;
        if (StringUtil.isEmpty(query2))
            return query1;
        return query1 + '&' + query2;
    }

    public static URI getJarSource(URI uri)
    {
        try
        {
            if (!"jar".equals(uri.getScheme()))
                return uri;
            // Get SSP (retaining encoded form)
            String s = uri.getRawSchemeSpecificPart();
            int bangSlash = s.indexOf("!/");
            if (bangSlash >= 0)
                s = s.substring(0, bangSlash);
            return new URI(s);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    public static String getJarSource(String uri)
    {
        if (!uri.startsWith("jar:"))
            return uri;
        int bangSlash = uri.indexOf("!/");
        return (bangSlash >= 0) ? uri.substring(4, bangSlash) : uri.substring(4);
    }
}
