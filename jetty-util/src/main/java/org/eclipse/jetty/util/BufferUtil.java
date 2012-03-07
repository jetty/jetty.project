// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import javax.swing.text.Position;


/* ------------------------------------------------------------------------------- */
/**
 * Buffer utility methods.
 * 
 * 
 */
public class BufferUtil
{
    static final byte SPACE = 0x20;
    static final byte MINUS = '-';
    static final byte[] DIGIT =
    { (byte)'0', (byte)'1', (byte)'2', (byte)'3', (byte)'4', (byte)'5', (byte)'6', (byte)'7', (byte)'8', (byte)'9', (byte)'A', (byte)'B', (byte)'C', (byte)'D',
            (byte)'E', (byte)'F' };

    
    /* ------------------------------------------------------------ */
    /** Allocate ByteBuffer in output mode.
     * The position and limit will both be zero, indicating that the buffer is 
     * empty and must be flipped before any data is put to it.
     * @param capacity
     * @return Buffer
     */
    public static ByteBuffer allocate(int capacity)
    {
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.limit(0);
        return buf;
    }

    /* ------------------------------------------------------------ */
    /** Allocate ByteBuffer in output mode.
     * The position and limit will both be zero, indicating that the buffer is 
     * empty and must be flipped before any data is put to it.
     * @param capacity
     * @return Buffer
     */
    public static ByteBuffer allocateDirect(int capacity)
    {
        ByteBuffer buf = ByteBuffer.allocateDirect(capacity);
        buf.limit(0);
        return buf;
    }
    


    /* ------------------------------------------------------------ */
    public static void clear(ByteBuffer buffer)
    {
        buffer.position(0);
        buffer.limit(0);
    }

    /* ------------------------------------------------------------ */
    public static void clearToFill(ByteBuffer buffer)
    {
        buffer.position(0);
        buffer.limit(buffer.capacity());
    }
    
    /* ------------------------------------------------------------ */
    public static void flipToFill(ByteBuffer buffer)
    {
        buffer.position(buffer.hasRemaining()?buffer.limit():0);
        buffer.limit(buffer.capacity());
    }


    /* ------------------------------------------------------------ */
    public static void flipToFlush(ByteBuffer buffer,int position)
    {
        buffer.limit(buffer.position());
        buffer.position(position);
    }
    
    
    /* ------------------------------------------------------------ */
    public static byte[] toArray(ByteBuffer buffer)
    {
        byte[] to = new byte[buffer.remaining()];
        if (buffer.hasArray())
        {
            byte[] array = buffer.array();
            System.arraycopy(array,buffer.arrayOffset()+buffer.position(),to,0,to.length);
        }
        else
            buffer.slice().get(to);
        return to;
    }

    /* ------------------------------------------------------------ */
    public static boolean isEmpty(ByteBuffer buf)
    {
        return buf==null || buf.remaining()==0;
    }
    
    /* ------------------------------------------------------------ */
    public static boolean hasContent(ByteBuffer buf)
    {
        return buf!=null && buf.remaining()>0;
    }
    
    /* ------------------------------------------------------------ */
    public static boolean isAtCapacity(ByteBuffer buf)
    {
        return buf!=null && buf.limit()==buf.capacity();
    }

    /* ------------------------------------------------------------ */
    public static long remaining(ByteBuffer buffer)
    {
        return buffer==null?0:buffer.remaining();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Put data from one buffer into another, avoiding over/under flows
     * @param from Buffer to take bytes from
     * @param to Buffer to put bytes to. The buffer is flipped before and after the put.
     * @return number of bytes moved
     */
    public static int put(ByteBuffer from, ByteBuffer to, long maxBytes)
    {
        return put(from,to,maxBytes>=Integer.MAX_VALUE?Integer.MAX_VALUE:(int)maxBytes);
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Put data from one buffer into another, avoiding over/under flows
     * @param from Buffer to take bytes from
     * @param to Buffer to put bytes to. The buffer is flipped before and after the put.
     * @return number of bytes moved
     */
    public static int put(ByteBuffer from, ByteBuffer to, int maxBytes)
    {
        int put;
        int pos=to.position();
        try
        {
            flipToFill(to);

            maxBytes=Math.min(maxBytes,to.remaining());
            int remaining=from.remaining();
            if (remaining>0)
            {
                if (remaining<=maxBytes)  
                {
                    to.put(from);
                    put=remaining;
                }
                else if (from.hasArray())
                {
                    put=maxBytes;
                    to.put(from.array(),from.arrayOffset()+from.position(),put);
                    from.position(from.position()+put);
                }
                else
                {
                    put=maxBytes;
                    ByteBuffer slice=from.slice();
                    slice.limit(put);
                    to.put(slice);
                    from.position(from.position()+put);
                }
            }
            else
                put=0;

        }
        finally
        {
            flipToFlush(to,pos);
        }
        return put;

    }
    /* ------------------------------------------------------------ */
    /**
     * Put data from one buffer into another, avoiding over/under flows
     * @param from Buffer to take bytes from
     * @param to Buffer to put bytes to. The buffer is flipped before and after the put.
     * @return number of bytes moved
     */
    public static int put(ByteBuffer from, ByteBuffer to)
    {
        int put;
        int pos=to.position();
        try
        {
            flipToFill(to);
            
            int remaining=from.remaining();
            if (remaining>0)
            {
                if (remaining<=to.remaining())  
                {
                    to.put(from);
                    put=remaining;
                }
                else if (from.hasArray())
                {
                    put=to.remaining();
                    to.put(from.array(),from.arrayOffset()+from.position(),put);
                    from.position(from.position()+put);
                }
                else
                {
                    put=to.remaining();
                    ByteBuffer slice=from.slice();
                    slice.limit(put);
                    to.put(slice);
                    from.position(from.position()+put);
                }
            }
            else
                put=0;

        }
        finally
        {
            flipToFlush(to,pos);
        }
        return put;

    }
    
    /* ------------------------------------------------------------ */
    public static String toString(ByteBuffer buffer)
    {
        return toString(buffer,StringUtil.__ISO_8859_1_CHARSET);
    }

    /* ------------------------------------------------------------ */
    public static String toUTF8String(ByteBuffer buffer)
    {
        return toString(buffer,StringUtil.__UTF8_CHARSET);
    }

    /* ------------------------------------------------------------ */
    public static String toString(ByteBuffer buffer, Charset charset)
    {
        if (buffer == null)
            return null;
        byte[] array = buffer.hasArray()?buffer.array():null;
        if (array == null)
        {
            byte[] to = new byte[buffer.remaining()];
            buffer.slice().get(to);
            return new String(to,0,to.length,charset);
        }
        return new String(array,buffer.arrayOffset()+buffer.position(),buffer.remaining(),charset);
    }

    /* ------------------------------------------------------------ */
    public static String toString(ByteBuffer buffer, int position, int length, Charset charset)
    {
        if (buffer == null)
            return null;
        byte[] array = buffer.hasArray()?buffer.array():null;
        if (array == null)
        {
            ByteBuffer ro=buffer.asReadOnlyBuffer();
            ro.position(position);
            ro.limit(position+length);
            byte[] to = new byte[length];
            ro.get(to);
            return new String(to,0,to.length,charset);
        }
        return new String(array,buffer.arrayOffset()+position,length,charset);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Convert buffer to an integer. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     * 
     * @param buffer
     *            A buffer containing an integer. The position is not changed.
     * @return an int
     */
    public static int toInt(ByteBuffer buffer)
    {
        int val = 0;
        boolean started = false;
        boolean minus = false;

        // TODO add version that operates on array

        for (int i = buffer.position(); i < buffer.limit(); i++)
        {
            byte b = buffer.get(i);
            if (b <= SPACE)
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10 + (b - '0');
                started = true;
            }
            else if (b == MINUS && !started)
            {
                minus = true;
            }
            else
                break;
        }

        if (started)
            return minus?(-val):val;
        throw new NumberFormatException(toString(buffer));
    }

    /**
     * Convert buffer to an long. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     * 
     * @param buffer
     *            A buffer containing an integer. The position is not changed.
     * @return an int
     */
    public static long toLong(ByteBuffer buffer)
    {
        long val = 0;
        boolean started = false;
        boolean minus = false;
        // TODO add version that operates on array

        for (int i = buffer.position(); i < buffer.limit(); i++)
        {
            byte b = buffer.get(i);
            if (b <= SPACE)
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10L + (b - '0');
                started = true;
            }
            else if (b == MINUS && !started)
            {
                minus = true;
            }
            else
                break;
        }

        if (started)
            return minus?(-val):val;
        throw new NumberFormatException(toString(buffer));
    }

    public static void putHexInt(ByteBuffer buffer, int n)
    {
        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Integer.MIN_VALUE)
            {
                buffer.put((byte)(0x7f & '8'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));
                buffer.put((byte)(0x7f & '0'));

                return;
            }
            n = -n;
        }

        if (n < 0x10)
        {
            buffer.put(DIGIT[n]);
        }
        else
        {
            boolean started = false;
            // This assumes constant time int arithmatic
            for (int i = 0; i < hexDivisors.length; i++)
            {
                if (n < hexDivisors[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started = true;
                int d = n / hexDivisors[i];
                buffer.put(DIGIT[d]);
                n = n - d * hexDivisors[i];
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Add hex integer BEFORE current getIndex.
     * 
     * @param buffer
     * @param n
     */
    public static void prependHexInt(ByteBuffer buffer, int n)
    {
        if (n == 0)
        {
            int gi = buffer.position();
            buffer.put(--gi,(byte)'0');
            buffer.position(gi);
        }
        else
        {
            boolean minus = false;
            if (n < 0)
            {
                minus = true;
                n = -n;
            }

            int gi = buffer.position();
            while (n > 0)
            {
                int d = 0xf & n;
                n = n >> 4;
                buffer.put(--gi,DIGIT[d]);
            }

            if (minus)
                buffer.put(--gi,(byte)'-');
            buffer.position(gi);
        }
    }

    /* ------------------------------------------------------------ */
    public static void putDecInt(ByteBuffer buffer, int n)
    {
        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Integer.MIN_VALUE)
            {
                buffer.put((byte)'2');
                n = 147483648;
            }
            else
                n = -n;
        }

        if (n < 10)
        {
            buffer.put(DIGIT[n]);
        }
        else
        {
            boolean started = false;
            // This assumes constant time int arithmatic
            for (int i = 0; i < decDivisors.length; i++)
            {
                if (n < decDivisors[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started = true;
                int d = n / decDivisors[i];
                buffer.put(DIGIT[d]);
                n = n - d * decDivisors[i];
            }
        }
    }

    public static void putDecLong(ByteBuffer buffer, long n)
    {
        if (n < 0)
        {
            buffer.put((byte)'-');

            if (n == Long.MIN_VALUE)
            {
                buffer.put((byte)'9');
                n = 223372036854775808L;
            }
            else
                n = -n;
        }

        if (n < 10)
        {
            buffer.put(DIGIT[(int)n]);
        }
        else
        {
            boolean started = false;
            // This assumes constant time int arithmatic
            for (int i = 0; i < decDivisorsL.length; i++)
            {
                if (n < decDivisorsL[i])
                {
                    if (started)
                        buffer.put((byte)'0');
                    continue;
                }

                started = true;
                long d = n / decDivisorsL[i];
                buffer.put(DIGIT[(int)d]);
                n = n - d * decDivisorsL[i];
            }
        }
    }

    public static ByteBuffer toBuffer(int value)
    {
        ByteBuffer buf = ByteBuffer.allocate(32);
        putDecInt(buf,value);
        return buf;
    }

    public static ByteBuffer toBuffer(long value)
    {
        ByteBuffer buf = ByteBuffer.allocate(32);
        putDecLong(buf,value);
        return buf;
    }

    public static ByteBuffer toBuffer(String s)
    {
        return ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET));
    }

    public static ByteBuffer toBuffer(String s, Charset charset)
    {
        return ByteBuffer.wrap(s.getBytes(charset));
    }

    public static String toDetailString(ByteBuffer buffer)
    {
        StringBuilder buf = new StringBuilder();
        buf.append("[p=");
        buf.append(buffer.position());
        buf.append(",l=");
        buf.append(buffer.limit());
        buf.append(",c=");
        buf.append(buffer.capacity());
        buf.append("]={");
        
        for (int i=0;i<buffer.position();i++)
        {
            char c=(char)buffer.get(i);
            if (c>=' ')
                buf.append(c);
            else if (c=='\r'||c=='\n')
                buf.append('|');
            else
                buf.append('?');
            if (i==16&&buffer.position()>32)
            {
                buf.append("...");
                i=buffer.position()-16;
            }
        }
        buf.append("<<<");
        for (int i=buffer.position();i<buffer.limit();i++)
        {
            char c=(char)buffer.get(i);
            if (c>=' ')
                buf.append(c);
            else if (c=='\r'||c=='\n')
                buf.append('|');
            else
                buf.append('?');
            if (i==buffer.position()+16&&buffer.limit()>buffer.position()+32)
            {
                buf.append("...");
                i=buffer.limit()-16;
            }
        }
        buf.append(">>>");
        int limit=buffer.limit();
        buffer.limit(buffer.capacity());
        for (int i=limit;i<buffer.capacity();i++)
        {
            char c=(char)buffer.get(i);
            if (c>=' ')
                buf.append(c);
            else if (c=='\r'||c=='\n')
                buf.append('|');
            else
                buf.append('?');
            if (i==limit+16&&buffer.capacity()>limit+32)
            {
                buf.append("...");
                i=buffer.capacity()-16;
            }
        }
        buffer.limit(limit);
        buf.append("}");
        
        return buf.toString();
    }
    
    
    private final static int[] decDivisors =
    { 1000000000, 100000000, 10000000, 1000000, 100000, 10000, 1000, 100, 10, 1 };

    private final static int[] hexDivisors =
    { 0x10000000, 0x1000000, 0x100000, 0x10000, 0x1000, 0x100, 0x10, 0x1 };

    private final static long[] decDivisorsL =
    { 1000000000000000000L, 100000000000000000L, 10000000000000000L, 1000000000000000L, 100000000000000L, 10000000000000L, 1000000000000L, 100000000000L,
            10000000000L, 1000000000L, 100000000L, 10000000L, 1000000L, 100000L, 10000L, 1000L, 100L, 10L, 1L };

    public static void putCRLF(ByteBuffer buffer)
    {
        buffer.put((byte)13);
        buffer.put((byte)10);
    }

    public static boolean isPrefix(ByteBuffer prefix, ByteBuffer buffer)
    {
        if (prefix.remaining() > buffer.remaining())
            return false;
        int bi = buffer.position();
        for (int i = prefix.position(); i < prefix.limit(); i++)
            if (prefix.get(i) != buffer.get(bi++))
                return false;
        return true;
    }




}
