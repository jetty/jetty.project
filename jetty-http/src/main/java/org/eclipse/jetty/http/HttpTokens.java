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

    public static class Token
    {
        private final Type _type;
        private final byte _b;
        private final char _c;
        private final int _x;

        private Token(byte b, Type type)
        {
            _type = type;
            _b = b;
            _c = (char)(0xff & b);
            char lc = (_c >= 'A' & _c <= 'Z') ? ((char)(_c - 'A' + 'a')) : _c;
            _x = (_type == Type.DIGIT || _type == Type.ALPHA && lc >= 'a' && lc <= 'f') ? TypeUtil.convertHexDigit(b) : -1;
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
}

