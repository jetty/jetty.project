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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.stream.Stream;

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
public final class URIUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(URIUtil.class);
    private static final Index<String> KNOWN_SCHEMES = new Index.Builder<String>()
        .caseSensitive(false)
        .with("file:")
        .with("jrt:")
        .with("jar:")
        .build();

    /**
     * The characters that are supported by the URI class and that can be decoded by {@link #canonicalPath(String)}
     */
    private static final boolean[] URI_SUPPORTED_CHARACTERS = new boolean[]
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
        false, // 0x20 space is illegal
        true,  // 0x21
        false, // 0x22 " is illegal
        false, // 0x23 # is special
        true,  // 0x24
        false, // 0x25 % must remain encoded
        true,  // 0x26
        true,  // 0x27
        true,  // 0x28
        true,  // 0x29
        true,  // 0x2a
        true,  // 0x2b
        true,  // 0x2c
        true,  // 0x2d
        true,  // 0x2e
        false, // 0x2f / is a delimiter
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
        false, // 0x3b ; is path parameter
        false, // 0x3c < is illegal
        true,  // 0x3d
        false, // 0x3e > is illegal
        false, // 0x3f ? is special
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
        false, // 0x5b [ is illegal
        false, // 0x5c \ is illegal
        false, // 0x5d ] is illegal
        false, // 0x5e ^ is illegal
        true,  // 0x5f
        false, // 0x60 ` is illegal
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
        false, // 0x7b { is illegal
        false, // 0x7c | is illegal
        false, // 0x7d } is illegal
        true,  // 0x7e
        false, // 0x7f DEL is illegal
    };

    private static final boolean[] ENCODE_PATH_NEEDS_ENCODING;

    private URIUtil()
    {
    }

    static
    {
        ENCODE_PATH_NEEDS_ENCODING = new boolean[128];
        // Special characters
        for (char c: "%?;#\"'<> [\\]^`{|}".toCharArray())
            ENCODE_PATH_NEEDS_ENCODING[c] = true;
        // control characters
        ENCODE_PATH_NEEDS_ENCODING[0x7f] = true;
        for (int i = 0; i < 0x20; i++)
            ENCODE_PATH_NEEDS_ENCODING[i] = true;
    }

    /**
     * Encode a URI path.
     * This is the same encoding offered by URLEncoder, except that
     * the '{@code /}' character is not encoded.
     *
     * @param path The path to encode
     * @return The encoded path
     */
    public static String encodePath(String path)
    {
        if (StringUtil.isEmpty(path))
            return path;

        // byte encoding always wins and, if encountered, should be used.
        boolean needsByteEncoding = false;
        // string (char-by-char) encoding, but it could be followed by a need for byte encoding instead
        boolean needsEncoding = false;
        int length = path.length();
        for (int i = 0; i < length; i++)
        {
            char c = path.charAt(i);
            if (c > 0x7F) // 8-bit +
            {
                needsByteEncoding = true;
                break; // have to encode byte by byte now
            }
            if (ENCODE_PATH_NEEDS_ENCODING[c])
            {
                // could be followed by a byte encoding, so no break
                needsEncoding = true;
            }
        }

        if (needsByteEncoding)
            return encodePathBytes(path);
        else if (needsEncoding)
            return encodePathString(path);
        else
            return path;
    }

    private static String encodePathString(String path)
    {
        StringBuilder buf = new StringBuilder(path.length() * 2);
        int length = path.length();
        for (int i = 0; i < length; i++)
        {
            char c = path.charAt(i);
            if (ENCODE_PATH_NEEDS_ENCODING[c])
            {
                buf.append('%');
                TypeUtil.toHex((byte)c, buf);
            }
            else
            {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    private static String encodePathBytes(String path)
    {
        StringBuilder buf = new StringBuilder(path.length() * 2);
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        for (byte b : pathBytes)
        {
            if (b < 0 || ENCODE_PATH_NEEDS_ENCODING[b])
            {
                buf.append('%');
                TypeUtil.toHex(b, buf);
            }
            else
            {
                buf.append((char)b);
            }
        }
        return buf.toString();
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
     * @param charsToDecode the list of raw characters that need to be decoded (if encountered), leaving all the other encoded sequences alone.
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
            if (c == '%')
            {
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
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    /**
     * Encode a URI path.
     *
     * @param path The path to encode
     * @param buf StringBuilder to encode path into (or null)
     * @param encode String of characters to encode. '{@code %}' is always encoded.
     * @return The StringBuilder or null if no substitutions required.
     */
    // TODO: remove, only used in URIUtilTest?
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
     * @see #canonicalPath(String)
     * @see #normalizePath(String)
     */
    public static String decodePath(String path)
    {
        return decodePath(path, 0, path.length());
    }

    /**
     * Decode a URI path and strip parameters of UTF-8 path
     * @see #canonicalPath(String)
     * @see #normalizePath(String)
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
                                int[] codePoints = {(0xffff & TypeUtil.parseInt(path, i + 2, 4, 16))};
                                String str = new String(codePoints, 0, 1);
                                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                                for (byte b: bytes)
                                    builder.append(b);
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

    /**
     * @param path The path to check for validity
     * @return True if the path does not contain any invalid path characters
     */
    public static boolean isPathValid(String path)
    {
        if (path == null)
            return true;

        int end = path.length();
        for (int i = 0; i < end; i++)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '?' :
                case '#' :
                    return false;
            }
        }
        return true;
    }

    /**
     * Test if codepoint is safe and unambiguous to pass as input to {@link URI}
     *
     * @param code the codepoint code to test
     * @return true if safe to decode, otherwise false;
     */
    private static boolean isSafe(int code)
    {
        // Allow any 8-bit character (as it's likely unicode).
        // or any character labeled with true in __uriSupportedCharacters static
        return (code >= URI_SUPPORTED_CHARACTERS.length || URI_SUPPORTED_CHARACTERS[code]);
    }

    /**
     * If the codepoint is safe, do nothing, else add the UTF-8 URI encoded form of the codepoint.
     *
     * @param code the codepoint to check
     * @param builder The builder to encode into
     * @return true if the decoded value is safe to pass as input to {@link URI}, otherwise false;
     */
    private static boolean isSafeElseEncode(int code, Utf8StringBuilder builder)
    {
        if (isSafe(code))
            return true;

        encodeCodepoint(code, builder);
        return false;
    }

    private static void encodeCodepoint(int code, Utf8StringBuilder builder)
    {
        // Code point is 7-bit, simple encode
        if (code <= 0x7F)
        {
            builder.append('%');
            appendHexValue(builder, (byte)code);
        }
        else
        {
            // Code point is 8-bit, figure out the UTF-8 percent encoding for that codepoint and add it
            int[] codePoints = {code};
            String str = new String(codePoints, 0, 1);
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            for (byte b: bytes)
            {
                builder.append('%');
                appendHexValue(builder, b);
            }
        }
    }

    private static void appendHexValue(Utf8StringBuilder builder, byte value)
    {
        byte d = (byte)((0xF0 & value) >> 4);
        builder.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = (byte)(0xF & value);
        builder.append((char)((d > 9 ? ('A' - 10) : '0') + d));
    }

    /**
     * Canonicalize a URI path to a form that is unambiguous and safe to use with the JVM {@link URI} class.
     * <p>
     * Decode only the safe characters in a URI path and strip parameters of UTF-8 path.
     * Safe characters are ones that are not special delimiters and that can be passed to the JVM {@link URI} class.
     * Unsafe characters, other than '{@code /}' will be encoded.  Encodings will be uppercase hex.
     * Canonical paths are also normalized and may be used in string comparisons with other canonical paths.
     * <p>
     * For example the path {@code /fo %2fo/b%61r} will be normalized to {@code /fo%20%2Fo/bar},
     * whilst {@link #decodePath(String)} would result in the ambiguous and URI illegal {@code /fo /o/bar}.
     * @return the canonical path or null if it is non-normal
     * @see #decodePath(String)
     * @see #normalizePath(String)
     * @see URI
     */
    public static String canonicalPath(String path)
    {
        if (path == null)
            return null;
        try
        {

            Utf8StringBuilder builder = null;
            int end = path.length();
            boolean slash = true;
            boolean normal = true;
            for (int i = 0; i < end; i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, 0, i);
                        }
                        if ((i + 2) < end)
                        {
                            char u = path.charAt(i + 1);
                            if (u == 'u')
                            {
                                // UTF16 encoding is only supported with UriCompliance.Violation.UTF16_ENCODINGS.
                                int code = TypeUtil.parseInt(path, i + 2, 4, 16);
                                if (isSafeElseEncode(code, builder))
                                {
                                    char[] chars = Character.toChars(code);
                                    for (char ch : chars)
                                    {
                                        builder.append(ch);
                                        if (slash && ch == '.')
                                            normal = false;
                                        slash = false;
                                    }
                                }
                                i += 5;
                            }
                            else
                            {
                                int code = TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2));
                                if (isSafeElseEncode(code, builder))
                                {
                                    builder.append((byte)(0xff & code));
                                    if (slash && code == '.')
                                        normal = false;
                                }
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
                            builder.append(path, 0, i);
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

                    case '/':
                        if (builder != null)
                            builder.append(c);
                        break;

                    case '.':
                        if (slash)
                            normal = false;
                        if (builder != null)
                            builder.append(c);
                        break;

                    default:
                        if (builder == null && !isSafe(c))
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, 0, i);
                        }

                        if (builder != null && isSafeElseEncode(c, builder))
                            builder.append(c);
                        break;
                }

                slash = c == '/';
            }

            String canonical = (builder != null) ? builder.toString() : path;
            return normal ? canonical : normalizePath(canonical);
        }
        catch (NotUtf8Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} {}", path, e.toString());
            throw e;
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

    /**
     * Decode a URI path and strip parameters of ISO-8859-1 path
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
     * (e.g. {@code ?a=b} or {@code ;JSESSIONID=xxx}) and avoids duplicate '{@code /}'
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
            if (p2.startsWith("/"))
            {
                buf.deleteCharAt(split - 1);
                buf.insert(split - 1, p2);
            }
            else
                buf.insert(split, p2);
        }
        else
        {
            if (p2.startsWith("/"))
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
     * <p>
     * Handles null and empty paths.
     * Path and query params (e.g. {@code ?a=b} or {@code ;JSESSIONID=xxx}) are not handled
     * </p>
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

        boolean p1EndsWithSlash = p1.endsWith("/");
        boolean p2StartsWithSlash = p2.startsWith("/");

        if (p1EndsWithSlash && p2StartsWithSlash)
        {
            if (p2.length() == 1)
                return p1;
            if (p1.length() == 1)
                return p2;
        }

        StringBuilder buf = new StringBuilder(p1.length() + p2.length() + 2);
        buf.append(p1);

        if (p1.endsWith("/"))
        {
            if (p2.startsWith("/"))
                buf.setLength(buf.length() - 1);
        }
        else
        {
            if (!p2.startsWith("/"))
                buf.append("/");
        }
        buf.append(p2);

        return buf.toString();
    }

    /**
     * Add a path and a query string
     *
     * @param path The path which may already contain a query
     * @param query The query string to add (if blank, no query is added)
     * @return The path with any non-blank query added after a '{@code ?}' or '{@code &amp;}' as appropriate.
     */
    public static String addPathQuery(String path, String query)
    {
        if (StringUtil.isBlank(query))
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
     * <p>
     * Treat a URI like a directory path and return the parent directory.
     * </p>
     *
     * @param p the path to return a parent reference to
     * @return the parent path of the URI
     */
    public static String parentPath(String p)
    {
        if (p == null || "/".equals(p))
            return null;
        int slash = p.lastIndexOf('/', p.length() - 2);
        if (slash >= 0)
            return p.substring(0, slash + 1);
        return null;
    }

    /**
     * <p>
     * Normalize a URI path and query by factoring out all segments of '{@code .}' and '{@code ..}'
     * up until any query or fragment.
     * Null is returned if the path is normalized above its root.
     * </p>
     *
     * @param pathQuery the encoded URI from the path onwards, which may contain query strings and/or fragments
     * @return the normalized path, or null if path traversal above root.
     * @see #normalizePath(String)
     */
    public static String normalizePathQuery(String pathQuery)
    {
        if (pathQuery == null || pathQuery.isEmpty())
            return pathQuery;

        boolean slash = true;
        int end = pathQuery.length();
        int i = 0;

        // Initially just loop looking if we may need to normalize
        loop: while (i < end)
        {
            char c = pathQuery.charAt(i);
            switch (c)
            {
                case '/':
                    slash = true;
                    break;

                case '.':
                    if (slash)
                        break loop;
                    break;

                case '?':
                case '#':
                    // Nothing to normalize so return original path
                    return pathQuery;

                default:
                    slash = false;
            }

            i++;
        }

        // Nothing to normalize so return original path
        if (i == end)
            return pathQuery;

        // We probably need to normalize, so copy to path so far into builder
        StringBuilder canonical = new StringBuilder(pathQuery.length());
        canonical.append(pathQuery, 0, i);

        // Loop looking for single and double dot segments
        int dots = 1;
        i++;
        loop : while (i < end)
        {
            char c = pathQuery.charAt(i);
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
            canonical.append(pathQuery, i, end);

        return canonical.toString();
    }

    /**
     * <p>Check if a path would be normalized within itself. For example,
     * {@code /foo/../../bar} is normalized above its root and would
     * thus return false, whilst {@code /foo/./bar/..} is normal within itself
     * and would return true.
     * @param path The path to check
     * @return True if the normal form of the path is within the root of the path.
     */
    public static boolean isNotNormalWithinSelf(String path)
    {
        // TODO this can be optimized to avoid allocation.
        return normalizePath(path) == null;
    }

    /**
     * <p>Normalize a URI path by factoring out all segments of {@code .} and {@code ..}.
     * Null is returned if the path is normalized above its root.
     * </p>
     *
     * @param path the decoded URI path to convert. Any special characters (e.g. {@code ?}, {@code #}) are assumed to be part of
     * the path segments.
     * @return the normalized path, or null if path traversal above root.
     * @see #normalizePathQuery(String)
     * @see #canonicalPath(String)
     * @see #decodePath(String)
     */
    public static String normalizePath(String path)
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
                case '/' -> slash = true;
                case '.' ->
                {
                    if (slash)
                        break loop;
                }
                default -> slash = false;
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
                case '/' ->
                {
                    if (doDotsSlash(canonical, dots))
                        return null;
                    slash = true;
                    dots = 0;
                }
                case '.' ->
                {
                    // Count dots only if they are leading in the segment
                    if (dots > 0)
                        dots++;
                    else if (slash)
                        dots = 1;
                    else
                        canonical.append('.');
                    slash = false;
                }
                default ->
                {
                    // Add leading dots to the path
                    while (dots-- > 0)
                        canonical.append('.');
                    canonical.append(c);
                    dots = 0;
                    slash = false;
                }
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
     * All instances of {@code //} and {@code ///} etc. are factored out to single {@code /}
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
     * Encode characters in a path to ensure they only contain safe encodings suitable for both
     * {@link URI} and {@link Paths#get(URI)} usage.
     *
     * @param path the path to encode
     * @return the returned path with only safe encodings
     */
    public static String encodePathSafeEncoding(String path)
    {
        if (path == null)
            return null;
        if ("".equals(path) || "/".equals(path))
            return path;

        int offset = 0;
        int length = path.length();

        try
        {
            Utf8StringBuilder builder = null;
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                char c = path.charAt(i);
                if (c == '%')
                {
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
                            if (TypeUtil.isHex(path, i + 2, 4))
                            {
                                // Always decode percent-u encoding to UTF-8
                                int codepoint = (0xffff & TypeUtil.parseInt(path, i + 2, 4, 16));
                                encodeCodepoint(codepoint, builder);
                                i += 5;
                            }
                            else
                            {
                                // not valid percent-u, encode the percent symbol
                                builder.append("%25");
                            }
                        }
                        else
                        {
                            if (TypeUtil.isHex(path, i + 1, 2))
                            {
                                // valid Hex, attempt to decode it
                                byte b = (byte)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2))));
                                if (mustBeEncoded(b) || b == 0x2F)
                                {
                                    // unsafe, keep encoding
                                    encodeCodepoint(b, builder);
                                }
                                else
                                {
                                    // safe to decode
                                    builder.append(b);
                                }
                                i += 2;
                            }
                            else
                            {
                                // not valid percent encoding, encode the percent symbol
                                builder.append("%25");
                            }
                        }
                    }
                    else
                    {
                        // incomplete percent encoding, encode the percent symbol
                        builder.append("%25");
                    }
                }
                else
                {
                    if (mustBeEncoded(c))
                    {
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }
                        encodeCodepoint(c, builder);
                    }
                    else
                    {
                        if (builder != null)
                            builder.append(c);
                    }
                }
            }

            if (builder != null)
                return builder.toString();
            return path;
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

    /**
     * Check codepoint for rules on URI encoding.
     *
     * <p>
     *     This does not allow 8-bit characters, unlike {@link #isSafe(int)}
     * </p>
     *
     * @param codepoint the codepoint to check
     * @return true if the codepoint must be encoded, false otherwise
     */
    private static boolean mustBeEncoded(int codepoint)
    {
        // 8-bit
        if (codepoint > 0x7F)
            return true;

        // control characters
        if ((codepoint <= 0x1F) || (codepoint == 0x7F)) // control characters
            return true;

        // unsafe characters
        if (codepoint == '"' || codepoint == '<' || codepoint == '>' || codepoint == '%' ||
            codepoint == '{' || codepoint == '}' || codepoint == '|' || codepoint == '\\' ||
            codepoint == '^' || codepoint == '`')
            return true;

        // additional raw characters rejected by java.net.URI
        if ((codepoint == ' ') || (codepoint == '[') || (codepoint == ']'))
            return true;

        // additional raw characters rejected by Paths.get(URI)
        return ((codepoint == '?') || (codepoint == '#'));
    }

    /**
     * Add a sub path to an existing URI.
     *
     * @param uri A URI to add the path to
     * @param path A safe path element
     * @return URI with path added.
     * @see #addPaths(String, String)
     */
    public static URI addPath(URI uri, String path)
    {
        Objects.requireNonNull(uri, "URI");

        if (path == null || "".equals(path))
            return uri;

        // collapse any "//" paths in the path portion
        path = compactPath(path);

        int pathLen = path.length();

        if (pathLen == 0)
            return uri;

        // Correct any bad `file:/path` usages, and
        // force encoding of characters that must be encoded (such as unicode)
        // for the base
        String base = correctFileURI(uri).toASCIIString();

        // ensure that the base has a safe encoding suitable for both
        // URI and Paths.get(URI) later usage
        path = encodePathSafeEncoding(path);
        pathLen = path.length();

        if (base.length() == 0)
            return URI.create(path);

        StringBuilder buf = new StringBuilder(base.length() + pathLen * 3);
        buf.append(base);
        if (buf.charAt(base.length() - 1) != '/')
            buf.append('/');

        // collapse any "//" paths in the path portion
        int offset = path.charAt(0) == '/' ? 1 : 0;
        buf.append(path, offset, pathLen);

        return URI.create(buf.toString());
    }

    /**
     * Combine two query strings into one. Each query string should not contain the beginning '{@code ?}' character, but
     * may contain multiple parameters separated by the '{@code &}' character.
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

    /**
     * <p>
     * Corrects any bad {@code file} based URIs (even within a {@code jar:file:} based URIs) from the bad out-of-spec
     * format that various older Java APIs creates (most notably: {@link java.io.File} creates with it's {@link File#toURL()}
     * and {@link File#toURI()}, along with the side effects of using {@link URL#toURI()})
     * </p>
     *
     * <p>
     *     This correction is limited to only the {@code file:/} substring in the URI.
     *     If there is a {@code file:/<not-a-slash>} detected, that substring is corrected to
     *     {@code file:///<not-a-slash>}, all other uses of {@code file:}, and URIs without a {@code file:}
     *     substring are left alone.
     * </p>
     *
     * <p>
     *     Note that Windows UNC based URIs are left alone, along with non-absolute URIs.
     * </p>
     *
     * @param uri the URI to (possibly) correct
     * @return the new URI with the {@code file:/} substring corrected, or the original URI.
     */
    public static URI correctFileURI(URI uri)
    {
        if ((uri == null) || (uri.getScheme() == null))
            return uri;

        if (!uri.getScheme().equalsIgnoreCase("file") && !uri.getScheme().equalsIgnoreCase("jar"))
            return uri; // not a scheme we can fix

        if (uri.getRawAuthority() != null)
            return uri; // already valid (used in Windows UNC uris)

        if (!uri.isAbsolute())
            return uri; // non-absolute URI cannot be fixed

        String rawURI = uri.toASCIIString();
        int colon = rawURI.indexOf(":/");
        if (colon < 0)
            return uri; // path portion not found

        int end = -1;
        if (rawURI.charAt(colon + 2) != '/')
            end = colon + 2;
        if (end >= 0)
            return URI.create(rawURI.substring(0, colon) + ":///" + rawURI.substring(end));
        return uri;
    }

    /**
     * Split a string of references, that may be split with '{@code ,}', or '{@code ;}', or '{@code |}' into URIs.
     * <p>
     *     Each part of the input string could be path references (unix or windows style), or string URI references.
     * </p>
     * <p>
     *     If the result of processing the input segment is a java archive, then its resulting URI will be a mountable URI as {@code jar:file:...!/}
     * </p>
     *
     * @param str the input string of references
     * @see #toJarFileUri(URI)
     */
    public static List<URI> split(String str)
    {
        List<URI> uris = new ArrayList<>();

        StringTokenizer tokenizer = new StringTokenizer(str, ",;|");
        while (tokenizer.hasMoreTokens())
        {
            String reference = tokenizer.nextToken();
            try
            {
                // Is this a glob reference?
                if (reference.endsWith("/*") || reference.endsWith("\\*"))
                {
                    String dir = reference.substring(0, reference.length() - 2);
                    Path pathDir = Paths.get(dir);
                    // Use directory
                    if (Files.exists(pathDir) && Files.isDirectory(pathDir))
                    {
                        // To obtain the list of entries
                        try (Stream<Path> listStream = Files.list(pathDir))
                        {
                            listStream
                                .filter(Files::isRegularFile)
                                .filter(FileID::isLibArchive)
                                .sorted(Comparator.naturalOrder())
                                .forEach(path -> uris.add(toJarFileUri(path.toUri())));
                        }
                        catch (IOException e)
                        {
                            throw new RuntimeException("Unable to process directory glob listing: " + reference, e);
                        }
                    }
                }
                else
                {
                    // Simple reference
                    URI refUri = toURI(reference);
                    // Ensure that a Java Archive that can be mounted
                    uris.add(toJarFileUri(refUri));
                }
            }
            catch (Exception e)
            {
                LOG.warn("Invalid Resource Reference: " + reference);
                throw e;
            }
        }
        return uris;
    }

    /**
     * <p>
     * Take an arbitrary URI and provide a URI that is suitable for mounting the URI as a Java FileSystem.
     * </p>
     * <p>
     * The resulting URI will point to the {@code jar:file://foo.jar!/} said Java Archive (jar, war, or zip)
     * </p>
     *
     * @param uri the URI to mutate to a {@code jar:file:...} URI.
     * @return the {@code jar:${uri_to_java_archive}!/${internal-reference}} URI or the unchanged URI if not a Java Archive.
     * @see FileID#isArchive(URI)
     */
    public static URI toJarFileUri(URI uri)
    {
        Objects.requireNonNull(uri, "URI");
        String scheme = Objects.requireNonNull(uri.getScheme(), "URI scheme");

        if (!FileID.isArchive(uri))
            return uri;

        boolean hasInternalReference = uri.getRawSchemeSpecificPart().indexOf("!/") > 0;

        if (scheme.equalsIgnoreCase("jar"))
        {
            if (uri.getRawSchemeSpecificPart().startsWith("file:"))
            {
                // Looking good as a jar:file: URI
                if (hasInternalReference)
                    return uri; // is all good, no changes needed.
                else
                    // add the internal reference indicator to the root of the archive
                    return URI.create(uri.toASCIIString() + "!/");
            }
        }
        else if (scheme.equalsIgnoreCase("file"))
        {
            String rawUri = uri.toASCIIString();
            if (hasInternalReference)
                return URI.create("jar:" + rawUri);
            else
                return URI.create("jar:" + rawUri + "!/");
        }

        // shouldn't be possible to reach this point
        throw new IllegalArgumentException("Cannot make %s into `jar:file:` URI".formatted(uri));
    }

    /**
     * <p>Convert a String into a URI suitable for use as a Resource.</p>
     *
     * @param resource If the string starts with one of the ALLOWED_SCHEMES, then it is assumed to be a
     * representation of a {@link URI}, otherwise it is treated as a {@link Path}.
     * @return The {@link URI} form of the resource.
     */
    public static URI toURI(String resource)
    {
        Objects.requireNonNull(resource);

        // Only try URI for string for known schemes, otherwise assume it is a Path
        return (KNOWN_SCHEMES.getBest(resource) != null)
            ? correctFileURI(URI.create(resource))
            : Paths.get(resource).toUri();
    }

    /**
     * <p>
     * Unwrap a URI to expose its container path reference.
     * </p>
     *
     * <p>
     * Take out the container archive name URI from a {@code jar:file:${container-name}!/} URI.
     * </p>
     *
     * @param uri the input URI
     * @return the container String if a {@code jar} scheme, or just the URI untouched.
     */
    public static URI unwrapContainer(URI uri)
    {
        Objects.requireNonNull(uri);

        String scheme = uri.getScheme();
        if ((scheme == null) || !scheme.equalsIgnoreCase("jar"))
            return uri;

        String spec = uri.getRawSchemeSpecificPart();
        int sep = spec.indexOf("!/");
        if (sep != -1)
            spec = spec.substring(0, sep);
        return URI.create(spec);
    }

    /**
     * Take a URI and add a deep reference {@code jar:file://foo.jar!/suffix}, replacing
     * any existing deep reference on the input URI.
     *
     * @param uri the input URI (supporting {@code jar} or {@code file} based schemes
     * @param encodedSuffix the suffix to set.  Must start with {@code !/}.  Must be properly URI encoded.
     * @return the {@code jar:file:} based URI with a deep reference
     */
    public static URI uriJarPrefix(URI uri, String encodedSuffix)
    {
        if (uri == null)
            throw new IllegalArgumentException("URI must not be null");
        if (encodedSuffix == null)
            throw new IllegalArgumentException("Encoded Suffix must not be null");
        if (!encodedSuffix.startsWith("!/"))
            throw new IllegalArgumentException("Suffix must start with \"!/\"");

        String uriString = uri.toASCIIString(); // ensure proper encoding

        int bangSlash = uriString.indexOf("!/");
        if (bangSlash >= 0)
           uriString = uriString.substring(0, bangSlash);

        if (uri.getScheme().equalsIgnoreCase("jar"))
        {
            return URI.create(uriString + encodedSuffix);
        }
        else if (uri.getScheme().equalsIgnoreCase("file"))
        {
            return URI.create("jar:" + uriString + encodedSuffix);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    /**
     * Stream the {@link URLClassLoader#getURLs()} as URIs
     *
     * @param urlClassLoader the classloader to load from
     * @return the Stream of {@link URI}
     */
    public static Stream<URI> streamOf(URLClassLoader urlClassLoader)
    {
        URL[] urls = urlClassLoader.getURLs();
        return Stream.of(urls)
            .filter(Objects::nonNull)
            .map(URL::toString)
            .map(URI::create)
            .map(URIUtil::unwrapContainer)
            .map(URIUtil::correctFileURI);
    }
}
