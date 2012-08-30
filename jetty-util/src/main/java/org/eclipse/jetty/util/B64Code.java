//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;


/* ------------------------------------------------------------ */
/** Fast B64 Encoder/Decoder as described in RFC 1421.
 * <p>Does not insert or interpret whitespace as described in RFC
 * 1521. If you require this you must pre/post process your data.
 * <p> Note that in a web context the usual case is to not want
 * linebreaks or other white space in the encoded output.
 * 
 */
public class B64Code
{
    // ------------------------------------------------------------------
    static final char pad='=';
    static final char[] rfc1421alphabet=
            {
                'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P',
                'Q','R','S','T','U','V','W','X','Y','Z','a','b','c','d','e','f',
                'g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v',
                'w','x','y','z','0','1','2','3','4','5','6','7','8','9','+','/'
            };

    static final byte[] rfc1421nibbles;

    static
    {
        rfc1421nibbles=new byte[256];
        for (int i=0;i<256;i++)
            rfc1421nibbles[i]=-1;
        for (byte b=0;b<64;b++)
            rfc1421nibbles[(byte)rfc1421alphabet[b]]=b;
        rfc1421nibbles[(byte)pad]=0;
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
        return encode(b,false);
    }
    
    // ------------------------------------------------------------------
    /**
     * Fast Base 64 encode as described in RFC 1421 and RFC2045
     * <p>Does not insert whitespace as described in RFC 1521, unless rfc2045 is passed as true.
     * <p> Avoids creating extra copies of the input/output.
     * @param b byte array to encode.
     * @param rfc2045 If true, break lines at 76 characters with CRLF
     * @return char array containing the encoded form of the input.
     */
    static public char[] encode(byte[] b, boolean rfc2045)
    {
        if (b==null)
            return null;

        int bLen=b.length;
        int cLen=((bLen+2)/3)*4;
        if (rfc2045)
            cLen+=2+2*cLen/76;
        char c[]=new char[cLen];
        int ci=0;
        int bi=0;
        byte b0, b1, b2;
        int stop=(bLen/3)*3;
        int l=0;
        while (bi<stop)
        {
            b0=b[bi++];
            b1=b[bi++];
            b2=b[bi++];
            c[ci++]=rfc1421alphabet[(b0>>>2)&0x3f];
            c[ci++]=rfc1421alphabet[(b0<<4)&0x3f|(b1>>>4)&0x0f];
            c[ci++]=rfc1421alphabet[(b1<<2)&0x3f|(b2>>>6)&0x03];
            c[ci++]=rfc1421alphabet[b2&077];
            l+=4;
            if (rfc2045 && l%76==0)
            {
                c[ci++]=13;
                c[ci++]=10;
            }
        }

        if (bLen!=bi)
        {
            switch (bLen%3)
            {
                case 2:
                    b0=b[bi++];
                    b1=b[bi++];
                    c[ci++]=rfc1421alphabet[(b0>>>2)&0x3f];
                    c[ci++]=rfc1421alphabet[(b0<<4)&0x3f|(b1>>>4)&0x0f];
                    c[ci++]=rfc1421alphabet[(b1<<2)&0x3f];
                    c[ci++]=pad;
                    break;

                case 1:
                    b0=b[bi++];
                    c[ci++]=rfc1421alphabet[(b0>>>2)&0x3f];
                    c[ci++]=rfc1421alphabet[(b0<<4)&0x3f];
                    c[ci++]=pad;
                    c[ci++]=pad;
                    break;

                default:
                    break;
            }
        }

        if (rfc2045)
        {
            c[ci++]=13;
            c[ci++]=10;
        }
        return c;
    }

    // ------------------------------------------------------------------
    /**
     * Base 64 decode as described in RFC 2045.
     * <p>Unlike {@link #decode(char[])}, extra whitespace is ignored.
     * @param encoded String to decode.
     * @param charEncoding String representing the character encoding
     *        used to map the decoded bytes into a String.
     * @return String decoded byte array.
     * @throws UnsupportedEncodingException if the encoding is not supported
     * @throws IllegalArgumentException if the input is not a valid
     *         B64 encoding.
     */
    static public String decode(String encoded,String charEncoding)
            throws UnsupportedEncodingException
    {
        byte[] decoded=decode(encoded);
        if (charEncoding==null)
            return new String(decoded);
        return new String(decoded,charEncoding);
    }

    /* ------------------------------------------------------------ */
    /**
     * Fast Base 64 decode as described in RFC 1421.
     * 
     * <p>Unlike other decode methods, this does not attempt to 
     * cope with extra whitespace as described in RFC 1521/2045.
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
                b0=rfc1421nibbles[b[bi++]];
                b1=rfc1421nibbles[b[bi++]];
                b2=rfc1421nibbles[b[bi++]];
                b3=rfc1421nibbles[b[bi++]];
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
                        b0=rfc1421nibbles[b[bi++]];
                        b1=rfc1421nibbles[b[bi++]];
                        b2=rfc1421nibbles[b[bi++]];
                        if (b0<0 || b1<0 || b2<0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        r[ri++]=(byte)(b0<<2|b1>>>4);
                        r[ri++]=(byte)(b1<<4|b2>>>2);
                        break;

                    case 1:
                        b0=rfc1421nibbles[b[bi++]];
                        b1=rfc1421nibbles[b[bi++]];
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
    
    /* ------------------------------------------------------------ */
    /**
     * Base 64 decode as described in RFC 2045.
     * <p>Unlike {@link #decode(char[])}, extra whitespace is ignored.
     * @param encoded String to decode.
     * @return byte array containing the decoded form of the input.
     * @throws IllegalArgumentException if the input is not a valid
     *         B64 encoding.
     */
    static public byte[] decode(String encoded)
    {
        if (encoded==null)
            return null;
        
        int ci=0;
        byte nibbles[] = new byte[4];
        int s=0;
        ByteArrayOutputStream bout = new ByteArrayOutputStream(4*encoded.length()/3);

        while (ci<encoded.length())
        {
            char c=encoded.charAt(ci++);

            if (c==pad)
                break;
            
            if (Character.isWhitespace(c))
                continue;

            byte nibble=rfc1421nibbles[c];
            if (nibble<0)
                throw new IllegalArgumentException("Not B64 encoded");

            nibbles[s++]=rfc1421nibbles[c];

            switch(s)
            {
                case 1:
                    break;
                case 2:
                    bout.write(nibbles[0]<<2|nibbles[1]>>>4);
                    break;
                case 3:
                    bout.write(nibbles[1]<<4|nibbles[2]>>>2);
                    break;
                case 4:
                    bout.write(nibbles[2]<<6|nibbles[3]);
                    s=0;
                    break;
            }

        }

        return bout.toByteArray();
    }
}
