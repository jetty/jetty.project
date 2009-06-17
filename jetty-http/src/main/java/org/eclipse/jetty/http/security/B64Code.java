// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.http.security;

import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.util.StringUtil;

/* ------------------------------------------------------------ */

/** Fast B64 Encoder/Decoder as described in RFC 1421.
 * <p>Does not insert or interpret whitespace as described in RFC
 * 1521. If you require this you must pre/post process your data.
 * <p> Note that in a web context the usual case is to not want
 * linebreaks or other white space in the encoded output.
 *
 * 
 * 
 */
public class B64Code
{
    // ------------------------------------------------------------------
    static final char pad='=';
    static final char[] nibble2code=
            {
                'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P',
                'Q','R','S','T','U','V','W','X','Y','Z','a','b','c','d','e','f',
                'g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v',
                'w','x','y','z','0','1','2','3','4','5','6','7','8','9','+','/'
            };

    static final byte[] code2nibble;

    static
    {
        code2nibble=new byte[256];
        for (int i=0;i<256;i++)
            code2nibble[i]=-1;
        for (byte b=0;b<64;b++)
            code2nibble[(byte)nibble2code[b]]=b;
        code2nibble[(byte)pad]=0;
    }

    // ------------------------------------------------------------------
    /**
     * Base 64 encode as described in RFC 1421.
     * <p>Does not insert whitespace as described in RFC 1521.
     * @param s String to encode.
     * @return String containing the encoded form of the input.
     */
    static public String encode(String s)
    {
        try
        {
            return encode(s,null);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new IllegalArgumentException(e.toString());
        }
    }

    // ------------------------------------------------------------------
    /**
     * Base 64 encode as described in RFC 1421.
     * <p>Does not insert whitespace as described in RFC 1521.
     * @param s String to encode.
     * @param charEncoding String representing the name of
     *        the character encoding of the provided input String.
     * @return String containing the encoded form of the input.
     */
    static public String encode(String s,String charEncoding)
            throws UnsupportedEncodingException
    {
        byte[] bytes;
        if (charEncoding==null)
            bytes=s.getBytes(StringUtil.__ISO_8859_1);
        else
            bytes=s.getBytes(charEncoding);

        return new String(encode(bytes));
    }

    // ------------------------------------------------------------------
    /**
     * Fast Base 64 encode as described in RFC 1421.
     * <p>Does not insert whitespace as described in RFC 1521.
     * <p> Avoids creating extra copies of the input/output.
     * @param b byte array to encode.
     * @return char array containing the encoded form of the input.
     */
    static public char[] encode(byte[] b)
    {
        if (b==null)
            return null;

        int bLen=b.length;
        char r[]=new char[((bLen+2)/3)*4];
        int ri=0;
        int bi=0;
        byte b0, b1, b2;
        int stop=(bLen/3)*3;
        while (bi<stop)
        {
            b0=b[bi++];
            b1=b[bi++];
            b2=b[bi++];
            r[ri++]=nibble2code[(b0>>>2)&0x3f];
            r[ri++]=nibble2code[(b0<<4)&0x3f|(b1>>>4)&0x0f];
            r[ri++]=nibble2code[(b1<<2)&0x3f|(b2>>>6)&0x03];
            r[ri++]=nibble2code[b2&077];
        }

        if (bLen!=bi)
        {
            switch (bLen%3)
            {
                case 2:
                    b0=b[bi++];
                    b1=b[bi++];
                    r[ri++]=nibble2code[(b0>>>2)&0x3f];
                    r[ri++]=nibble2code[(b0<<4)&0x3f|(b1>>>4)&0x0f];
                    r[ri++]=nibble2code[(b1<<2)&0x3f];
                    r[ri++]=pad;
                    break;

                case 1:
                    b0=b[bi++];
                    r[ri++]=nibble2code[(b0>>>2)&0x3f];
                    r[ri++]=nibble2code[(b0<<4)&0x3f];
                    r[ri++]=pad;
                    r[ri++]=pad;
                    break;

                default:
                    break;
            }
        }

        return r;
    }

    // ------------------------------------------------------------------
    /**
     * Base 64 decode as described in RFC 1421.
     * <p>Does not attempt to cope with extra whitespace
     * as described in RFC 1521.
     * @param s String to decode
     * @return String decoded byte array.
     */
    static public String decode(String s)
    {
        try
        {
            return decode(s,StringUtil.__ISO_8859_1);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new IllegalArgumentException(e.toString());
        }
    }

    // ------------------------------------------------------------------
    /**
     * Base 64 decode as described in RFC 1421.
     * <p>Does not attempt to cope with extra whitespace
     * as described in RFC 1521.
     * @param s String to decode
     * @param charEncoding String representing the character encoding
     *        used to map the decoded bytes into a String.
     * @return String decoded byte array.
     */
    static public String decode(String s,String charEncoding)
            throws UnsupportedEncodingException
    {
        byte[] decoded=decode(s.toCharArray());

        if (charEncoding==null)
            return new String(decoded);
        return new String(decoded,charEncoding);
    }

    /* ------------------------------------------------------------ */
    /**
     * Fast Base 64 decode as described in RFC 1421.
     * <p>Does not attempt to cope with extra whitespace
     * as described in RFC 1521.
     * <p> Avoids creating extra copies of the input/output.
     * <p> Note this code has been flattened for performance.
     * @param b char array to decode.
     * @return byte array containing the decoded form of the input.
     * @throws IllegalArgumentException if the input is not a valid
     *         B64 encoding.
     */
    static public byte[] decode(char[] b)
    {
        if (b==null)
            return null;

        int bLen=b.length;
        if (bLen%4!=0)
            throw new IllegalArgumentException("Input block size is not 4");

        int li=bLen-1;
        while (li>=0 && b[li]==(byte)pad)
            li--;

        if (li<0)
            return new byte[0];

        // Create result array of exact required size.
        int rLen=((li+1)*3)/4;
        byte r[]=new byte[rLen];
        int ri=0;
        int bi=0;
        int stop=(rLen/3)*3;
        byte b0,b1,b2,b3;
        try
        {
            while (ri<stop)
            {
                b0=code2nibble[b[bi++]];
                b1=code2nibble[b[bi++]];
                b2=code2nibble[b[bi++]];
                b3=code2nibble[b[bi++]];
                if (b0<0 || b1<0 || b2<0 || b3<0)
                    throw new IllegalArgumentException("Not B64 encoded");

                r[ri++]=(byte)(b0<<2|b1>>>4);
                r[ri++]=(byte)(b1<<4|b2>>>2);
                r[ri++]=(byte)(b2<<6|b3);
            }

            if (rLen!=ri)
            {
                switch (rLen%3)
                {
                    case 2:
                        b0=code2nibble[b[bi++]];
                        b1=code2nibble[b[bi++]];
                        b2=code2nibble[b[bi++]];
                        if (b0<0 || b1<0 || b2<0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        r[ri++]=(byte)(b0<<2|b1>>>4);
                        r[ri++]=(byte)(b1<<4|b2>>>2);
                        break;

                    case 1:
                        b0=code2nibble[b[bi++]];
                        b1=code2nibble[b[bi++]];
                        if (b0<0 || b1<0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        r[ri++]=(byte)(b0<<2|b1>>>4);
                        break;

                    default:
                        break;
                }
            }
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new IllegalArgumentException("char "+bi
                    +" was not B64 encoded");
        }

        return r;
    }
}
