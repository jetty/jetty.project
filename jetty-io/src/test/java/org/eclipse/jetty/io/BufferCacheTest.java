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

package org.eclipse.jetty.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;

/**
 *
 */
public class BufferCacheTest
{
    private final static String[] S = {"S0", "S1", "s2", "s3" };

    private BufferCache cache;

    @Before
    public void init() throws Exception
    {
        cache=new BufferCache();
        cache.add(S[1],1);
        cache.add(S[2],2);
        cache.add(S[3],3);
    }

    @Test
    public void testLookupIndex()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3s0s1S2S3";
            ByteBuffer buf=ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET),i*2,2);
            BufferCache.CachedBuffer b=cache.get(buf);
            int index=b==null?-1:b.getOrdinal();

            if (i>0)
                assertEquals(i,index);
            else
                assertEquals(-1,index);
        }
    }

    @Test
    public void testGetBuffer()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3s0s1S2S3";
            ByteBuffer buf=ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET),i*2,2);
            ByteBuffer b=cache.getBuffer(buf);

            assertEquals(i,b.get(1)-'0');
        }
    }


    @Test
    public void testGet()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3s0s1S2S3";
            ByteBuffer buf=ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET),i*2,2);
            CachedBuffer b=cache.get(buf);

            if (i>0)
                assertEquals(S[i],b.toString());
            else
                assertEquals(null,b);
        }
    }

    @Test
    public void testLookupBuffer()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3s0s1S2S3";
            ByteBuffer buf=ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET),i*2,2);
            ByteBuffer b=cache.lookup(buf);

            assertEquals(S[i],BufferUtil.toString(b));
            if (i>0)
                assertEquals(""+i, S[i], BufferUtil.toString(b));
            else
            {
                assertNotSame(""+i, S[i], BufferUtil.toString(b));
                assertEquals(""+i, S[i], BufferUtil.toString(b));
            }
        }
    }

    @Test
    public void testLookupPartialBuffer()
    {
        String key="44444";
        cache.add(key,4);

        ByteBuffer buf=BufferUtil.toBuffer("44444");
        ByteBuffer b=cache.get(buf).getBuffer();
        assertEquals("44444",BufferUtil.toString(b));
        assertEquals(4,cache.getOrdinal(b));

        buf=BufferUtil.toBuffer("4444");
        assertEquals(null,cache.get(buf));
        assertSame(buf,cache.getBuffer(buf));
        assertEquals(-1,cache.getOrdinal(buf));

        buf=BufferUtil.toBuffer("44444x");
        assertEquals("44444",cache.get(buf).toString());
        assertEquals(4,cache.getOrdinal(buf));

    }

    @Test
    public void testToString()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3s0s1S2S3";
            ByteBuffer buf=ByteBuffer.wrap(s.getBytes(StringUtil.__ISO_8859_1_CHARSET),i*2,2);
            String str=cache.getString(buf);

            assertEquals(S[i],str);
            if (i>0)
                assertSame(S[i], str);
            else
                assertNotSame(S[i], str);
        }
    }
}
