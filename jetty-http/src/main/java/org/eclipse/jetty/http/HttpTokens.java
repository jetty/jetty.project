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

package org.eclipse.jetty.http;

import org.eclipse.jetty.util.TypeUtil;

/**
 * HTTP constants
 */
public class HttpTokens
{
    static final byte COLON = (byte)':';
    static final byte TAB = 0x09;
    static final byte LINE_FEED = 0x0A;
    static final byte CARRIAGE_RETURN = 0x0D;
    static final byte SPACE = 0x20;
    static final byte[] CRLF = {CARRIAGE_RETURN, LINE_FEED};

    public enum EndOfContent
    {
        UNKNOWN_CONTENT, NO_CONTENT, EOF_CONTENT, CONTENT_LENGTH, CHUNKED_CONTENT
    }

    public enum Type
    {
        CNTL,    // Control characters excluding LF, CR
        HTAB,    // Horizontal tab 
        LF,      // Line feed
        CR,      // Carriage return
        SPACE,   // Space 
        COLON,   // Colon character
        DIGIT,   // Digit
        ALPHA,   // Alpha
        TCHAR,   // token characters excluding COLON,DIGIT,ALPHA, which is equivalent to VCHAR excluding delimiters
        VCHAR,   // Visible characters excluding COLON,DIGIT,ALPHA
        OTEXT    // Obsolete text
    }

    public static Token getToken(byte b)
    {
        return TOKENS[0xFF & b];
    }

    public static Token getToken(char c)
    {
        return c <= 0xFF ? TOKENS[c] : null;
    }

    public static class Token
    {
        private final Type _type;
        private final byte _b;
        private final char _c;
        private final int _x;

        private final boolean _rfc2616Token;

        private final boolean _rfc6265CookieOctet;

        private Token(byte b, Type type)
        {
            _type = type;
            _b = b;
            _c = (char)(0xff & b);
            char lc = (_c >= 'A' & _c <= 'Z') ? ((char)(_c - 'A' + 'a')) : _c;
            _x = (_type == Type.DIGIT || _type == Type.ALPHA && lc >= 'a' && lc <= 'f') ? TypeUtil.convertHexDigit(b) : -1;

            // token          = 1*<any CHAR except CTLs or separators>
            // separators     = "(" | ")" | "<" | ">" | "@"
            //                | "," | ";" | ":" | "\" | <">
            //                | "/" | "[" | "]" | "?" | "="
            //                | "{" | "}" | SP | HT
            // CTL            = <any US-ASCII control character
            //                  (octets 0 - 31) and DEL (127)>
            _rfc2616Token = b >= 32 && b < 127 &&
                b != '(' && b !=  ')' && b !=  '<' && b !=  '>' && b !=  '@' &&
                b !=  ',' && b !=  ';' && b !=  ':' && b !=  '\\' && b !=  '"' &&
                b !=  '/' && b !=  '[' && b !=  ']' && b !=  '?' && b !=  '=' &&
                b !=  '{' && b !=  '}' && b !=  ' ';

            // cookie-octet      = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
            //                     ; US-ASCII characters excluding CTLs,
            //                     ; whitespace DQUOTE, comma, semicolon,
            //                     ; and backslash
            _rfc6265CookieOctet =
                b == 0x21 ||
                b >= 0x23 && b <= 0x2B ||
                b >= 0x2D && b <= 0x3A ||
                b >= 0x3C && b <= 0x5B ||
                b >= 0x5D && b <= 0x7E;
        }

        public Type getType()
        {
            return _type;
        }

        public byte getByte()
        {
            return _b;
        }

        public char getChar()
        {
            return _c;
        }

        public boolean isHexDigit()
        {
            return _x >= 0;
        }

        public boolean isRfc2616Token()
        {
            return _rfc2616Token;
        }

        public boolean isRfc6265CookieOctet()
        {
            return _rfc6265CookieOctet;
        }

        public int getHexDigit()
        {
            return _x;
        }

        @Override
        public String toString()
        {
            switch (_type)
            {
                case SPACE:
                case COLON:
                case ALPHA:
                case DIGIT:
                case TCHAR:
                case VCHAR:
                    return _type + "='" + _c + "'";

                case CR:
                    return "CR=\\r";

                case LF:
                    return "LF=\\n";

                default:
                    return String.format("%s=0x%x", _type, _b);
            }
        }
    }

    public static final Token[] TOKENS = new Token[256];

    static
    {
        for (int b = 0; b < 256; b++)
        {
            // token          = 1*tchar
            // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
            //                / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
            //                / DIGIT / ALPHA
            //                ; any VCHAR, except delimiters
            // quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
            // qdtext         = HTAB / SP /%x21 / %x23-5B / %x5D-7E / obs-text
            // obs-text       = %x80-FF
            // comment        = "(" *( ctext / quoted-pair / comment ) ")"
            // ctext          = HTAB / SP / %x21-27 / %x2A-5B / %x5D-7E / obs-text
            // quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )

            switch (b)
            {
                case LINE_FEED:
                    TOKENS[b] = new Token((byte)b, Type.LF);
                    break;
                case CARRIAGE_RETURN:
                    TOKENS[b] = new Token((byte)b, Type.CR);
                    break;
                case SPACE:
                    TOKENS[b] = new Token((byte)b, Type.SPACE);
                    break;
                case TAB:
                    TOKENS[b] = new Token((byte)b, Type.HTAB);
                    break;
                case COLON:
                    TOKENS[b] = new Token((byte)b, Type.COLON);
                    break;

                case '!':
                case '#':
                case '$':
                case '%':
                case '&':
                case '\'':
                case '*':
                case '+':
                case '-':
                case '.':
                case '^':
                case '_':
                case '`':
                case '|':
                case '~':
                    TOKENS[b] = new Token((byte)b, Type.TCHAR);
                    break;

                default:
                    if (b >= 0x30 && b <= 0x39) // DIGIT
                        TOKENS[b] = new Token((byte)b, Type.DIGIT);
                    else if (b >= 0x41 && b <= 0x5A) // ALPHA (uppercase)
                        TOKENS[b] = new Token((byte)b, Type.ALPHA);
                    else if (b >= 0x61 && b <= 0x7A) // ALPHA (lowercase)
                        TOKENS[b] = new Token((byte)b, Type.ALPHA);
                    else if (b >= 0x21 && b <= 0x7E) // Visible
                        TOKENS[b] = new Token((byte)b, Type.VCHAR);
                    else if (b >= 0x80) // OBS
                        TOKENS[b] = new Token((byte)b, Type.OTEXT);
                    else
                        TOKENS[b] = new Token((byte)b, Type.CNTL);
            }
        }
    }

    /**
     * This is used when decoding to not decode illegal characters based on RFC9110.
     * CR, LF, or NUL are replaced with ' ', all other control and multibyte characters
     * are replaced with '?'. If this is given a legal character the same value will be returned.
     * <pre>
     * field-vchar = VCHAR / obs-text
     * obs-text    = %x80-FF
     * VCHAR       = %x21-7E
     * </pre>
     * @param c the character to test.
     * @return the original character or the replacement character ' ' or '?',
     * the return value is guaranteed to be a valid ISO-8859-1 character.
     */
    public static char sanitizeFieldVchar(char c)
    {
        switch (c)
        {
            // A recipient of CR, LF, or NUL within a field value MUST either reject the message
            // or replace each of those characters with SP before further processing
            case '\r':
            case '\n':
            case 0x00:
                return ' ';

            default:
                if (isIllegalFieldVchar(c))
                    return '?';
        }
        return c;
    }

    /**
     * Checks whether this is an invalid VCHAR based on RFC9110.
     * If this not a valid ISO-8859-1 character or a control character
     * we say that it is illegal.
     *
     * @param c the character to test.
     * @return true if this is invalid VCHAR.
     */
    public static boolean isIllegalFieldVchar(char c)
    {
        return (c >= 256 || c < ' ');
    }
}

