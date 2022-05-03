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

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fast String Utilities.
 *
 * These string utilities provide both convenience methods and
 * performance improvements over most standard library versions. The
 * main aim of the optimizations is to avoid object creation unless
 * absolutely required.
 */
public class StringUtil
{
    public static final String ALL_INTERFACES = "0.0.0.0";
    public static final String CRLF = "\r\n";
    public static final String DEFAULT_DELIMS = ",;";

    public static final String __ISO_8859_1 = "iso-8859-1";
    public static final String __UTF8 = "utf-8";
    public static final String __UTF16 = "utf-16";

    private static final Index<String> CHARSETS = new Index.Builder<String>()
        .caseSensitive(false)
        .with("utf-8", __UTF8)
        .with("utf8", __UTF8)
        .with("utf-16", __UTF16)
        .with("utf16", __UTF16)
        .with("iso-8859-1", __ISO_8859_1)
        .with("iso_8859_1", __ISO_8859_1)
        .build();

    /**
     * Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     *
     * @param s the charset to normalize
     * @return the normalized charset (or null if normalized version not found)
     */
    public static String normalizeCharset(String s)
    {
        String n = CHARSETS.get(s);
        return (n == null) ? s : n;
    }

    /**
     * Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     *
     * @param s the charset to normalize
     * @param offset the offset in the charset
     * @param length the length of the charset in the input param
     * @return the normalized charset (or null if not found)
     */
    public static String normalizeCharset(String s, int offset, int length)
    {
        String n = CHARSETS.get(s, offset, length);
        return (n == null) ? s.substring(offset, offset + length) : n;
    }

    // @checkstyle-disable-check : IllegalTokenTextCheck
    private static final char[] LOWERCASES =
    {
        '\000', '\001', '\002', '\003', '\004', '\005', '\006', '\007',
        '\010', '\011', '\012', '\013', '\014', '\015', '\016', '\017',
        '\020', '\021', '\022', '\023', '\024', '\025', '\026', '\027',
        '\030', '\031', '\032', '\033', '\034', '\035', '\036', '\037',
        '\040', '\041', '\042', '\043', '\044', '\045', '\046', '\047',
        '\050', '\051', '\052', '\053', '\054', '\055', '\056', '\057',
        '\060', '\061', '\062', '\063', '\064', '\065', '\066', '\067',
        '\070', '\071', '\072', '\073', '\074', '\075', '\076', '\077',
        '\100', '\141', '\142', '\143', '\144', '\145', '\146', '\147',
        '\150', '\151', '\152', '\153', '\154', '\155', '\156', '\157',
        '\160', '\161', '\162', '\163', '\164', '\165', '\166', '\167',
        '\170', '\171', '\172', '\133', '\134', '\135', '\136', '\137',
        '\140', '\141', '\142', '\143', '\144', '\145', '\146', '\147',
        '\150', '\151', '\152', '\153', '\154', '\155', '\156', '\157',
        '\160', '\161', '\162', '\163', '\164', '\165', '\166', '\167',
        '\170', '\171', '\172', '\173', '\174', '\175', '\176', '\177'
    };

    // @checkstyle-disable-check : IllegalTokenTextCheck
    private static final char[] UPPERCASES =
    {
        '\000', '\001', '\002', '\003', '\004', '\005', '\006', '\007',
        '\010', '\011', '\012', '\013', '\014', '\015', '\016', '\017',
        '\020', '\021', '\022', '\023', '\024', '\025', '\026', '\027',
        '\030', '\031', '\032', '\033', '\034', '\035', '\036', '\037',
        '\040', '\041', '\042', '\043', '\044', '\045', '\046', '\047',
        '\050', '\051', '\052', '\053', '\054', '\055', '\056', '\057',
        '\060', '\061', '\062', '\063', '\064', '\065', '\066', '\067',
        '\070', '\071', '\072', '\073', '\074', '\075', '\076', '\077',
        '\100', '\101', '\102', '\103', '\104', '\105', '\106', '\107',
        '\110', '\111', '\112', '\113', '\114', '\115', '\116', '\117',
        '\120', '\121', '\122', '\123', '\124', '\125', '\126', '\127',
        '\130', '\131', '\132', '\133', '\134', '\135', '\136', '\137',
        '\140', '\101', '\102', '\103', '\104', '\105', '\106', '\107',
        '\110', '\111', '\112', '\113', '\114', '\115', '\116', '\117',
        '\120', '\121', '\122', '\123', '\124', '\125', '\126', '\127',
        '\130', '\131', '\132', '\173', '\174', '\175', '\176', '\177'
    };

    // @checkstyle-enable-check : IllegalTokenTextCheck

    /**
     * fast lower case conversion. Only works on ascii (not unicode)
     *
     * @param c the char to convert
     * @return a lower case version of c
     */
    public static char asciiToLowerCase(char c)
    {
        return (c < 0x80) ? LOWERCASES[c] : c;
    }

    /**
     * fast lower case conversion. Only works on ascii (not unicode)
     *
     * @param c the byte to convert
     * @return a lower case version of c
     */
    public static byte asciiToLowerCase(byte c)
    {
        return (c > 0) ? (byte)LOWERCASES[c] : c;
    }

    /**
     * fast upper case conversion. Only works on ascii (not unicode)
     *
     * @param c the char to convert
     * @return a upper case version of c
     */
    public static char asciiToUpperCase(char c)
    {
        return (c < 0x80) ? UPPERCASES[c] : c;
    }

    /**
     * fast upper case conversion. Only works on ascii (not unicode)
     *
     * @param c the byte to convert
     * @return a upper case version of c
     */
    public static byte asciiToUpperCase(byte c)
    {
        return (c > 0) ? (byte)UPPERCASES[c] : c;
    }

    /**
     * fast lower case conversion. Only works on ascii (not unicode)
     *
     * @param s the string to convert
     * @return a lower case version of s
     */
    public static String asciiToLowerCase(String s)
    {
        if (s == null)
            return null;

        char[] c = null;
        int i = s.length();
        // look for first conversion
        while (i-- > 0)
        {
            char c1 = s.charAt(i);
            if (c1 <= 127)
            {
                char c2 = LOWERCASES[c1];
                if (c1 != c2)
                {
                    c = s.toCharArray();
                    c[i] = c2;
                    break;
                }
            }
        }
        while (i-- > 0)
        {
            if (c[i] <= 127)
                c[i] = LOWERCASES[c[i]];
        }

        return c == null ? s : new String(c);
    }

    /**
     * fast upper case conversion. Only works on ascii (not unicode)
     *
     * @param s the string to convert
     * @return a lower case version of s
     */
    public static String asciiToUpperCase(String s)
    {
        if (s == null)
            return null;

        char[] c = null;
        int i = s.length();
        // look for first conversion
        while (i-- > 0)
        {
            char c1 = s.charAt(i);
            if (c1 <= 127)
            {
                char c2 = UPPERCASES[c1];
                if (c1 != c2)
                {
                    c = s.toCharArray();
                    c[i] = c2;
                    break;
                }
            }
        }
        while (i-- > 0)
        {
            if (c[i] <= 127)
                c[i] = UPPERCASES[c[i]];
        }
        return c == null ? s : new String(c);
    }

    /**
     * Replace all characters from input string that are known to have
     * special meaning in various filesystems.
     *
     * <p>
     * This will replace all of the following characters
     * with a "{@code _}" (underscore).
     * </p>
     * <ul>
     * <li>Control Characters</li>
     * <li>Anything not 7-bit printable ASCII</li>
     * <li>Special characters: pipe, redirect, combine, slash, equivalence, bang, glob, selection, etc...</li>
     * <li>Space</li>
     * </ul>
     *
     * @param str the raw input string
     * @return the sanitized output string. or null if {@code str} is null.
     */
    public static String sanitizeFileSystemName(String str)
    {
        if (str == null)
            return null;

        char[] chars = str.toCharArray();
        int len = chars.length;
        for (int i = 0; i < len; i++)
        {
            char c = chars[i];
            if ((c <= 0x1F) || // control characters
                (c >= 0x7F) || // over 7-bit printable ASCII
                // piping : special meaning on unix / osx / windows
                (c == '|') || (c == '>') || (c == '<') || (c == '/') || (c == '&') ||
                // special characters on windows
                (c == '\\') || (c == '.') || (c == ':') ||
                // special characters on osx
                (c == '=') || (c == '"') || (c == ',') ||
                // glob / selection characters on most OS's
                (c == '*') || (c == '?') ||
                // bang execution on unix / osx
                (c == '!') ||
                // spaces are just generally difficult to work with
                (c == ' '))
            {
                chars[i] = '_';
            }
        }
        return String.valueOf(chars);
    }

    public static boolean startsWithIgnoreCase(String s, String w)
    {
        if (w == null)
            return true;

        if (s == null || s.length() < w.length())
            return false;

        for (int i = 0; i < w.length(); i++)
        {
            char c1 = s.charAt(i);
            char c2 = w.charAt(i);
            if (c1 != c2)
            {
                if (c1 <= 127)
                    c1 = LOWERCASES[c1];
                if (c2 <= 127)
                    c2 = LOWERCASES[c2];
                if (c1 != c2)
                    return false;
            }
        }
        return true;
    }

    public static boolean endsWithIgnoreCase(String s, String w)
    {
        if (w == null)
            return true;
        if (s == null)
            return false;

        int sl = s.length();
        int wl = w.length();

        if (sl < wl)
            return false;

        for (int i = wl; i-- > 0; )
        {
            char c1 = s.charAt(--sl);
            char c2 = w.charAt(i);
            if (c1 != c2)
            {
                if (c1 <= 127)
                    c1 = LOWERCASES[c1];
                if (c2 <= 127)
                    c2 = LOWERCASES[c2];
                if (c1 != c2)
                    return false;
            }
        }
        return true;
    }

    /**
     * returns the next index of a character from the chars string
     *
     * @param s the input string to search
     * @param chars the chars to look for
     * @return the index of the character in the input stream found.
     */
    public static int indexFrom(String s, String chars)
    {
        for (int i = 0; i < s.length(); i++)
        {
            if (chars.indexOf(s.charAt(i)) >= 0)
                return i;
        }
        return -1;
    }

    /**
     * Replace chars within string.
     * <p>
     * Fast replacement for {@code java.lang.String#}{@link String#replace(char, char)}
     * </p>
     *
     * @param str the input string
     * @param find the char to look for
     * @param with the char to replace with
     * @return the now replaced string
     */
    public static String replace(String str, char find, char with)
    {
        if (str == null)
            return null;

        if (find == with)
            return str;

        int c = 0;
        int idx = str.indexOf(find, c);
        if (idx == -1)
        {
            return str;
        }
        char[] chars = str.toCharArray();
        int len = chars.length;
        for (int i = idx; i < len; i++)
        {
            if (chars[i] == find)
                chars[i] = with;
        }
        return String.valueOf(chars);
    }

    /**
     * Replace substrings within string.
     * <p>
     * Fast replacement for {@code java.lang.String#}{@link String#replace(CharSequence, CharSequence)}
     * </p>
     *
     * @param s the input string
     * @param sub the string to look for
     * @param with the string to replace with
     * @return the now replaced string
     */
    public static String replace(String s, String sub, String with)
    {
        if (s == null)
            return null;

        int c = 0;
        int i = s.indexOf(sub, c);
        if (i == -1)
        {
            return s;
        }
        StringBuilder buf = new StringBuilder(s.length() + with.length());
        do
        {
            buf.append(s, c, i);
            buf.append(with);
            c = i + sub.length();
        }
        while ((i = s.indexOf(sub, c)) != -1);
        if (c < s.length())
        {
            buf.append(s.substring(c));
        }
        return buf.toString();
    }

    /**
     * Replace first substrings within string.
     * <p>
     * Fast replacement for {@code java.lang.String#}{@link String#replaceFirst(String, String)}, but without
     * Regex support.
     * </p>
     *
     * @param original the original string
     * @param target the target string to look for
     * @param replacement the replacement string to use
     * @return the replaced string
     */
    public static String replaceFirst(String original, String target, String replacement)
    {
        int idx = original.indexOf(target);
        if (idx == -1)
            return original;

        int offset = 0;
        int originalLen = original.length();
        StringBuilder buf = new StringBuilder(originalLen + replacement.length());
        buf.append(original, offset, idx);
        offset += idx + target.length();
        buf.append(replacement);
        buf.append(original, offset, originalLen);

        return buf.toString();
    }

    /**
     * Append substring to StringBuilder
     *
     * @param buf StringBuilder to append to
     * @param s String to append from
     * @param offset The offset of the substring
     * @param length The length of the substring
     */
    public static void append(StringBuilder buf,
                              String s,
                              int offset,
                              int length)
    {
        synchronized (buf)
        {
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                if (i >= s.length())
                    break;
                buf.append(s.charAt(i));
            }
        }
    }

    /**
     * append hex digit
     *
     * @param buf the buffer to append to
     * @param b the byte to append
     * @param base the base of the hex output (almost always 16).
     */
    public static void append(StringBuilder buf, byte b, int base)
    {
        int bi = 0xff & b;
        int c = '0' + (bi / base) % base;
        if (c > '9')
            c = 'a' + (c - '0' - 10);
        buf.append((char)c);
        c = '0' + bi % base;
        if (c > '9')
            c = 'a' + (c - '0' - 10);
        buf.append((char)c);
    }

    /**
     * Append 2 digits (zero padded) to the StringBuffer
     *
     * @param buf the buffer to append to
     * @param i the value to append
     */
    public static void append2digits(StringBuffer buf, int i)
    {
        if (i < 100)
        {
            buf.append((char)(i / 10 + '0'));
            buf.append((char)(i % 10 + '0'));
        }
    }

    /**
     * Append 2 digits (zero padded) to the StringBuilder
     *
     * @param buf the buffer to append to
     * @param i the value to append
     */
    public static void append2digits(StringBuilder buf, int i)
    {
        if (i < 100)
        {
            buf.append((char)(i / 10 + '0'));
            buf.append((char)(i % 10 + '0'));
        }
    }

    /**
     * Generate a string from another string repeated n times.
     *
     * @param s the string to use
     * @param n the number of times this string should be appended
     */
    public static String stringFrom(String s, int n)
    {
        StringBuilder stringBuilder = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++)
        {
            stringBuilder.append(s);
        }
        return stringBuilder.toString();
    }

    /**
     * Return a non null string.
     *
     * @param s String
     * @return The string passed in or empty string if it is null.
     */
    public static String nonNull(String s)
    {
        if (s == null)
            return "";
        return s;
    }

    public static boolean equals(String s, char[] buf, int offset, int length)
    {
        if (s.length() != length)
            return false;
        for (int i = 0; i < length; i++)
        {
            if (buf[offset + i] != s.charAt(i))
                return false;
        }
        return true;
    }

    public static String toUTF8String(byte[] b, int offset, int length)
    {
        return new String(b, offset, length, StandardCharsets.UTF_8);
    }

    /**
     * @deprecated use {@link String#String(byte[], int, int, Charset)} instead
     */
    @Deprecated(since = "10", forRemoval = true)
    public static String toString(byte[] b, int offset, int length, String charset)
    {
        try
        {
            return new String(b, offset, length, charset);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Find the index of a control characters in String
     * <p>
     * This will return a result on the first occurrence of a control character, regardless if
     * there are more than one.
     * </p>
     * <p>
     * Note: uses codepoint version of {@link Character#isISOControl(int)} to support Unicode better.
     * </p>
     *
     * <pre>
     *   indexOfControlChars(null)      == -1
     *   indexOfControlChars("")        == -1
     *   indexOfControlChars("\r\n")    == 0
     *   indexOfControlChars("\t")      == 0
     *   indexOfControlChars("   ")     == -1
     *   indexOfControlChars("a")       == -1
     *   indexOfControlChars(".")       == -1
     *   indexOfControlChars(";\n")     == 1
     *   indexOfControlChars("abc\f")   == 3
     *   indexOfControlChars("z\010")   == 1
     *   indexOfControlChars(":\u001c") == 1
     * </pre>
     *
     * @param str the string to test.
     * @return the index of first control character in string, -1 if no control characters encountered
     */
    public static int indexOfControlChars(String str)
    {
        if (str == null)
        {
            return -1;
        }
        int len = str.length();
        for (int i = 0; i < len; i++)
        {
            if (Character.isISOControl(str.codePointAt(i)))
            {
                // found a control character, we can stop searching  now
                return i;
            }
        }
        // no control characters
        return -1;
    }

    /**
     * Test if a string is null or only has whitespace characters in it.
     * <p>
     * Note: uses codepoint version of {@link Character#isWhitespace(int)} to support Unicode better.
     *
     * <pre>
     *   isBlank(null)   == true
     *   isBlank("")     == true
     *   isBlank("\r\n") == true
     *   isBlank("\t")   == true
     *   isBlank("   ")  == true
     *   isBlank("a")    == false
     *   isBlank(".")    == false
     *   isBlank(";\n")  == false
     * </pre>
     *
     * @param str the string to test.
     * @return true if string is null or only whitespace characters, false if non-whitespace characters encountered.
     */
    public static boolean isBlank(String str)
    {
        if (str == null)
        {
            return true;
        }
        int len = str.length();
        for (int i = 0; i < len; i++)
        {
            if (!Character.isWhitespace(str.codePointAt(i)))
            {
                // found a non-whitespace, we can stop searching  now
                return false;
            }
        }
        // only whitespace
        return true;
    }

    /**
     * <p>Checks if a String is empty ("") or null.</p>
     *
     * <pre>
     *   isEmpty(null)   == true
     *   isEmpty("")     == true
     *   isEmpty("\r\n") == false
     *   isEmpty("\t")   == false
     *   isEmpty("   ")  == false
     *   isEmpty("a")    == false
     *   isEmpty(".")    == false
     *   isEmpty(";\n")  == false
     * </pre>
     *
     * @param str the string to test.
     * @return true if string is null or empty.
     */
    public static boolean isEmpty(String str)
    {
        return str == null || str.isEmpty();
    }

    /**
     * Get the length of a string where a null string is length 0.
     * @param s the string.
     * @return the length of the string.
     */
    public static int getLength(String s)
    {
        return (s == null) ? 0 : s.length();
    }

    /**
     * Test if a string is not null and contains at least 1 non-whitespace characters in it.
     * <p>
     * Note: uses codepoint version of {@link Character#isWhitespace(int)} to support Unicode better.
     *
     * <pre>
     *   isNotBlank(null)   == false
     *   isNotBlank("")     == false
     *   isNotBlank("\r\n") == false
     *   isNotBlank("\t")   == false
     *   isNotBlank("   ")  == false
     *   isNotBlank("a")    == true
     *   isNotBlank(".")    == true
     *   isNotBlank(";\n")  == true
     * </pre>
     *
     * @param str the string to test.
     * @return true if string is not null and has at least 1 non-whitespace character, false if null or all-whitespace characters.
     */
    public static boolean isNotBlank(String str)
    {
        if (str == null)
        {
            return false;
        }
        int len = str.length();
        for (int i = 0; i < len; i++)
        {
            if (!Character.isWhitespace(str.codePointAt(i)))
            {
                // found a non-whitespace, we can stop searching  now
                return true;
            }
        }
        // only whitespace
        return false;
    }

    public static boolean isUTF8(String charset)
    {
        return __UTF8.equalsIgnoreCase(charset) || __UTF8.equalsIgnoreCase(normalizeCharset(charset));
    }

    public static boolean isHex(String str, int offset, int length)
    {
        if (offset + length > str.length())
        {
            return false;
        }

        for (int i = offset; i < (offset + length); i++)
        {
            char c = str.charAt(i);
            if (!(((c >= 'a') && (c <= 'f')) ||
                ((c >= 'A') && (c <= 'F')) ||
                ((c >= '0') && (c <= '9'))))
            {
                return false;
            }
        }
        return true;
    }

    public static byte[] fromHexString(String s)
    {
        if (s.length() % 2 != 0)
            throw new IllegalArgumentException(s);
        byte[] array = new byte[s.length() / 2];
        for (int i = 0; i < array.length; i++)
        {
            int b = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            array[i] = (byte)(0xff & b);
        }
        return array;
    }

    public static String toHexString(byte b)
    {
        return toHexString(new byte[]{b}, 0, 1);
    }

    public static String toHexString(byte[] b)
    {
        return toHexString(Objects.requireNonNull(b, "ByteBuffer cannot be null"), 0, b.length);
    }

    public static String toHexString(byte[] b, int offset, int length)
    {
        StringBuilder buf = new StringBuilder();
        for (int i = offset; i < offset + length; i++)
        {
            int bi = 0xff & b[i];
            int c = '0' + (bi / 16) % 16;
            if (c > '9')
                c = 'A' + (c - '0' - 10);
            buf.append((char)c);
            c = '0' + bi % 16;
            if (c > '9')
                c = 'a' + (c - '0' - 10);
            buf.append((char)c);
        }
        return buf.toString();
    }

    public static String printable(String name)
    {
        if (name == null)
            return null;
        StringBuilder buf = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isISOControl(c))
                buf.append(c);
        }
        return buf.toString();
    }

    public static String printable(byte[] b)
    {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < b.length; i++)
        {
            char c = (char)b[i];
            if (Character.isWhitespace(c) || c > ' ' && c < 0x7f)
                buf.append(c);
            else
            {
                buf.append("0x");
                TypeUtil.toHex(b[i], buf);
            }
        }
        return buf.toString();
    }

    public static byte[] getBytes(String s)
    {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] getBytes(String s, String charset)
    {
        try
        {
            return s.getBytes(charset);
        }
        catch (Exception e)
        {
            return s.getBytes();
        }
    }

    public static byte[] getUtf8Bytes(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Convert String to an integer. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     *
     * @param string A String containing an integer.
     * @param from The index to start parsing from
     * @return an int
     */
    public static int toInt(String string, int from)
    {
        int val = 0;
        boolean started = false;
        boolean minus = false;
        for (int i = from; i < string.length(); i++)
        {
            char b = string.charAt(i);
            if (b <= ' ')
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10 + (b - '0');
                started = true;
            }
            else if (b == '-' && !started)
            {
                minus = true;
            }
            else
                break;
        }
        if (started)
            return minus ? (-val) : val;
        throw new NumberFormatException(string);
    }

    /**
     * Convert String to an long. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     *
     * @param string A String containing an integer.
     * @return an int
     */
    public static long toLong(String string)
    {
        long val = 0;
        boolean started = false;
        boolean minus = false;
        for (int i = 0; i < string.length(); i++)
        {
            char b = string.charAt(i);
            if (b <= ' ')
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10L + (b - '0');
                started = true;
            }
            else if (b == '-' && !started)
            {
                minus = true;
            }
            else
                break;
        }
        if (started)
            return minus ? (-val) : val;
        throw new NumberFormatException(string);
    }

    /**
     * Truncate a string to a max size.
     *
     * @param str the string to possibly truncate
     * @param maxSize the maximum size of the string
     * @return the truncated string.  if <code>str</code> param is null, then the returned string will also be null.
     */
    public static String truncate(String str, int maxSize)
    {
        if (str == null)
        {
            return null;
        }
        if (str.length() <= maxSize)
        {
            return str;
        }
        return str.substring(0, maxSize);
    }

    /**
     * Parse the string representation of a list using {@link #csvSplit(List, String, int, int)}
     *
     * @param s The string to parse, expected to be enclosed as '[...]'
     * @return An array of parsed values.
     */
    public static String[] arrayFromString(String s)
    {
        if (s == null)
            return new String[]{};
        if (!s.startsWith("[") || !s.endsWith("]"))
            throw new IllegalArgumentException();
        if (s.length() == 2)
            return new String[]{};
        return csvSplit(s, 1, s.length() - 2);
    }

    /**
     * Parse a CSV string using {@link #csvSplit(List, String, int, int)}
     *
     * @param s The string to parse
     * @return An array of parsed values.
     */
    public static String[] csvSplit(String s)
    {
        if (s == null)
            return null;
        return csvSplit(s, 0, s.length());
    }

    /**
     * Parse a CSV string using {@link #csvSplit(List, String, int, int)}
     *
     * @param s The string to parse
     * @param off The offset into the string to start parsing
     * @param len The len in characters to parse
     * @return An array of parsed values.
     */
    public static String[] csvSplit(String s, int off, int len)
    {
        if (s == null)
            return null;
        if (off < 0 || len < 0 || off > s.length())
            throw new IllegalArgumentException();
        List<String> list = new ArrayList<>();
        csvSplit(list, s, off, len);
        return list.toArray(new String[list.size()]);
    }

    enum CsvSplitState
    {
        PRE_DATA, QUOTE, SLOSH, DATA, WHITE, POST_DATA
    }

    /**
     * Split a quoted comma separated string to a list
     * <p>Handle <a href="https://www.ietf.org/rfc/rfc4180.txt">rfc4180</a>-like
     * CSV strings, with the exceptions:<ul>
     * <li>quoted values may contain double quotes escaped with back-slash
     * <li>Non-quoted values are trimmed of leading trailing white space
     * <li>trailing commas are ignored
     * <li>double commas result in a empty string value
     * </ul>
     *
     * @param list The Collection to split to (or null to get a new list)
     * @param s The string to parse
     * @param off The offset into the string to start parsing
     * @param len The len in characters to parse
     * @return list containing the parsed list values
     */
    public static List<String> csvSplit(List<String> list, String s, int off, int len)
    {
        if (list == null)
            list = new ArrayList<>();
        CsvSplitState state = CsvSplitState.PRE_DATA;
        StringBuilder out = new StringBuilder();
        int last = -1;
        while (len > 0)
        {
            char ch = s.charAt(off++);
            len--;

            switch (state)
            {
                case PRE_DATA:
                    if (Character.isWhitespace(ch))
                        continue;
                    if ('"' == ch)
                    {
                        state = CsvSplitState.QUOTE;
                        continue;
                    }

                    if (',' == ch)
                    {
                        list.add("");
                        continue;
                    }
                    state = CsvSplitState.DATA;
                    out.append(ch);
                    continue;

                case DATA:
                    if (Character.isWhitespace(ch))
                    {
                        last = out.length();
                        out.append(ch);
                        state = CsvSplitState.WHITE;
                        continue;
                    }

                    if (',' == ch)
                    {
                        list.add(out.toString());
                        out.setLength(0);
                        state = CsvSplitState.PRE_DATA;
                        continue;
                    }
                    out.append(ch);
                    continue;

                case WHITE:
                    if (Character.isWhitespace(ch))
                    {
                        out.append(ch);
                        continue;
                    }

                    if (',' == ch)
                    {
                        out.setLength(last);
                        list.add(out.toString());
                        out.setLength(0);
                        state = CsvSplitState.PRE_DATA;
                        continue;
                    }

                    state = CsvSplitState.DATA;
                    out.append(ch);
                    last = -1;
                    continue;

                case QUOTE:
                    if ('\\' == ch)
                    {
                        state = CsvSplitState.SLOSH;
                        continue;
                    }
                    if ('"' == ch)
                    {
                        list.add(out.toString());
                        out.setLength(0);
                        state = CsvSplitState.POST_DATA;
                        continue;
                    }
                    out.append(ch);
                    continue;

                case SLOSH:
                    out.append(ch);
                    state = CsvSplitState.QUOTE;
                    continue;

                case POST_DATA:
                    if (',' == ch)
                    {
                        state = CsvSplitState.PRE_DATA;
                        continue;
                    }
                    continue;

                default:
                    throw new IllegalStateException(state.toString());
            }
        }
        switch (state)
        {
            case PRE_DATA:
            case POST_DATA:
                break;

            case DATA:
            case QUOTE:
            case SLOSH:
                list.add(out.toString());
                break;

            case WHITE:
                out.setLength(last);
                list.add(out.toString());
                break;

            default:
                throw new IllegalStateException(state.toString());
        }

        return list;
    }

    public static String sanitizeXmlString(String html)
    {
        if (html == null)
            return null;

        int i = 0;

        // Are there any characters that need sanitizing?
        loop:
        for (; i < html.length(); i++)
        {
            char c = html.charAt(i);
            switch (c)
            {
                case '&':
                case '<':
                case '>':
                case '\'':
                case '"':
                    break loop;
                default:
                    if (Character.isISOControl(c) && !Character.isWhitespace(c))
                        break loop;
            }
        }
        // No characters need sanitizing, so return original string
        if (i == html.length())
            return html;

        // Create builder with OK content so far 
        StringBuilder out = new StringBuilder(html.length() * 4 / 3);
        out.append(html, 0, i);

        // sanitize remaining content
        for (; i < html.length(); i++)
        {
            char c = html.charAt(i);
            switch (c)
            {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                default:
                    if (Character.isISOControl(c) && !Character.isWhitespace(c))
                        out.append('?');
                    else
                        out.append(c);
            }
        }
        return out.toString();
    }

    public static String strip(String str, String find)
    {
        return StringUtil.replace(str, find, "");
    }

    /**
     * The String value of an Object
     * <p>This method calls {@link String#valueOf(Object)} unless the object is null,
     * in which case null is returned</p>
     *
     * @param object The object
     * @return String value or null
     */
    public static String valueOf(Object object)
    {
        return object == null ? null : String.valueOf(object);
    }
}
