//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.util.StringUtil;

/* ------------------------------------------------------------------------------- */
/** Buffer utility methods.
 * 
 * 
 */
public class BufferUtil
{
    static final byte SPACE= 0x20;
    static final byte MINUS= '-';
    static final byte[] DIGIT=
    {(byte)'0',(byte)'1',(byte)'2',(byte)'3',(byte)'4',(byte)'5',(byte)'6',(byte)'7',(byte)'8',(byte)'9',(byte)'A',(byte)'B',(byte)'C',(byte)'D',(byte)'E',(byte)'F'};

    /**
     * Convert buffer to an integer.
     * Parses up to the first non-numeric character. If no number is found an
     * IllegalArgumentException is thrown
     * @param buffer A buffer containing an integer. The position is not changed.
     * @return an int 
     */
    public static int toInt(Buffer buffer)
    {
        int val= 0;
        boolean started= false;
        boolean minus= false;
        for (int i= buffer.getIndex(); i < buffer.putIndex(); i++)
        {
            byte b= buffer.peek(i);
            if (b <= SPACE)
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val= val * 10 + (b - '0');
                started= true;
            }
            else if (b == MINUS && !started)
            {
                minus= true;
            }
            else
                break;
        }

        if (started)
            return minus ? (-val) : val;
        throw new NumberFormatException(buffer.toString());
    }
    
    /**
     * Convert buffer to an long.
     * Parses up to the first non-numeric character. If no number is found an
     * IllegalArgumentException is thrown
     * @param buffer A buffer containing an integer. The position is not changed.
     * @return an int 
     */
    public static long toLong(Buffer buffer)
    {
        long val= 0;
        boolean started= false;
        boolean minus= false;
        for (int i= buffer.getIndex(); i < buffer.putIndex(); i++)
        {
            byte b= buffer.peek(i);
            if (b <= SPACE)
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val= val * 10L + (b - '0');
                started= true;
            }
            else if (b == MINUS && !started)
            {
                minus= true;
            }
            else
                break;
        }

        if (started)
            return minus ? (-val) : val;
        throw new NumberFormatException(buffer.toString());
    }

    public static void putHexInt(Buffer buffer, int n)
    {

        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Integer.MIN_VALUE)
            {
                buffer.put((byte)(0x7f&'8'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                buffer.put((byte)(0x7f&'0'));
                
                return;
            }
            n= -n;
        }

        if (n < 0x10)
        {
            buffer.put(DIGIT[n]);
        }
        else
        {
            boolean started= false;
            // This assumes constant time int arithmatic
            for (int i= 0; i < hexDivisors.length; i++)
            {
                if (n < hexDivisors[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started= true;
                int d= n / hexDivisors[i];
                buffer.put(DIGIT[d]);
                n= n - d * hexDivisors[i];
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Add hex integer BEFORE current getIndex.
     * @param buffer
     * @param n
     */
    public static void prependHexInt(Buffer buffer, int n)
    {
        if (n==0)
        {
            int gi=buffer.getIndex();
            buffer.poke(--gi,(byte)'0');
            buffer.setGetIndex(gi);
        }
        else
        {
            boolean minus=false;
            if (n<0)
            {
                minus=true;
                n=-n;
            }

            int gi=buffer.getIndex();
            while(n>0)
            {
                int d = 0xf&n;
                n=n>>4;
                buffer.poke(--gi,DIGIT[d]);
            }
            
            if (minus)
                buffer.poke(--gi,(byte)'-');
            buffer.setGetIndex(gi);
        }
    }
    

    /* ------------------------------------------------------------ */
    public static void putDecInt(Buffer buffer, int n)
    {
        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Integer.MIN_VALUE)
            {
                buffer.put((byte)'2');
                n= 147483648;
            }
            else
                n= -n;
        }

        if (n < 10)
        {
            buffer.put(DIGIT[n]);
        }
        else
        {
            boolean started= false;
            // This assumes constant time int arithmatic
            for (int i= 0; i < decDivisors.length; i++)
            {
                if (n < decDivisors[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started= true;
                int d= n / decDivisors[i];
                buffer.put(DIGIT[d]);
                n= n - d * decDivisors[i];
            }
        }
    }
    
    public static void putDecLong(Buffer buffer, long n)
    {
        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Long.MIN_VALUE)
            {
                buffer.put((byte)'9');
                n= 223372036854775808L;
            }
            else
                n= -n;
        }

        if (n < 10)
        {
            buffer.put(DIGIT[(int)n]);
        }
        else
        {
            boolean started= false;
            // This assumes constant time int arithmatic
            for (int i= 0; i < decDivisorsL.length; i++)
            {
                if (n < decDivisorsL[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started= true;
                long d= n / decDivisorsL[i];
                buffer.put(DIGIT[(int)d]);
                n= n - d * decDivisorsL[i];
            }
        }
    }
    
    public static Buffer toBuffer(long value)
    {
        ByteArrayBuffer buf=new ByteArrayBuffer(32);
        putDecLong(buf, value);
        return buf;
    }

    private final static int[] decDivisors=
    { 
        1000000000,
        100000000,
        10000000,
        1000000,
        100000,
        10000,
        1000,
        100,
        10,
        1 
    };

    private final static int[] hexDivisors=
    {
        0x10000000,
        0x1000000, 
        0x100000, 
        0x10000, 
        0x1000, 
        0x100, 
        0x10, 
        0x1 
    };

    private final static long[] decDivisorsL=
    { 
        1000000000000000000L,
        100000000000000000L,
        10000000000000000L,
        1000000000000000L,
        100000000000000L,
        10000000000000L,
        1000000000000L,
        100000000000L,
        10000000000L,
        1000000000L,
        100000000L,
        10000000L,
        1000000L,
        100000L,
        10000L,
        1000L,
        100L,
        10L,
        1L 
    };


    public static void putCRLF(Buffer buffer)
    {
        buffer.put((byte)13);
        buffer.put((byte)10);
    }
    
    public static boolean isPrefix(Buffer prefix,Buffer buffer)
    {
        if (prefix.length()>buffer.length())
            return false;
        int bi=buffer.getIndex();
        for (int i=prefix.getIndex(); i<prefix.putIndex();i++)
            if (prefix.peek(i)!=buffer.peek(bi++))
                return false;
        return true;
    }

    public static String to8859_1_String(Buffer buffer)
    {
        if (buffer instanceof CachedBuffer)
            return buffer.toString();
        return buffer.toString(StringUtil.__ISO_8859_1_CHARSET);
    }
}
