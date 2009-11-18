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

import junit.framework.TestCase;

/* ------------------------------------------------------------------------------- */
/**
 * 
 * 
 */
public class BufferCacheTest extends TestCase
{
    final static String[] S=
    { "S0", "S1", "s2", "s3" };

    BufferCache cache;

    public BufferCacheTest(String arg0)
    {
        super(arg0);
    }

    public static void main(String[] args)
    {
        junit.textui.TestRunner.run(BufferCacheTest.class);
    }

    /**
     * @see TestCase#setUp()
     */
    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        cache=new BufferCache();
        cache.add(S[1],1);
        cache.add(S[2],2);
        cache.add(S[3],3);
    }

    /**
     * @see TestCase#tearDown()
     */
    @Override
    protected void tearDown() throws Exception
    {
        super.tearDown();
    }

    public void testLookupIndex()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3";
            ByteArrayBuffer buf=new ByteArrayBuffer(s.getBytes(),i*2,2);
            BufferCache.CachedBuffer b=cache.get(buf);
            int index=b==null?-1:b.getOrdinal();

            if (i>0)
                assertEquals(i,index);
            else
                assertEquals(-1,index);
        }
    }

    public void testGetBuffer()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3";
            ByteArrayBuffer buf=new ByteArrayBuffer(s.getBytes(),i*2,2);
            Buffer b=cache.get(buf);

            if (i>0)
                assertEquals(i,b.peek(1)-'0');
            else
                assertEquals(null,b);
        }
    }

    public void testLookupBuffer()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3";
            ByteArrayBuffer buf=new ByteArrayBuffer(s.getBytes(),i*2,2);
            Buffer b=cache.lookup(buf);

            assertEquals(S[i],b.toString());
            if (i>0)
                assertTrue(""+i,S[i]==b.toString());
            else
            {
                assertTrue(""+i,S[i]!=b.toString());
                assertEquals(""+i,S[i],b.toString());
            }
        }
    }

    public void testLookupPartialBuffer()
    {
        cache.add("44444",4);
        
        ByteArrayBuffer buf=new ByteArrayBuffer("44444");
        Buffer b=cache.lookup(buf);
        assertEquals("44444",b.toString());
        assertEquals(4,cache.getOrdinal(b));
        
        buf=new ByteArrayBuffer("4444");
        b=cache.lookup(buf);
        assertEquals(-1,cache.getOrdinal(b));
        
        buf=new ByteArrayBuffer("44444x");
        b=cache.lookup(buf);
        assertEquals(-1,cache.getOrdinal(b));
        

    }

    public void testInsensitiveLookupBuffer()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="s0s1S2S3";
            ByteArrayBuffer buf=new ByteArrayBuffer(s.getBytes(),i*2,2);
            Buffer b=cache.lookup(buf);

            assertTrue("test"+i,S[i].equalsIgnoreCase(b.toString()));
            if (i>0)
                assertTrue("test"+i,S[i]==b.toString());
            else
                assertTrue("test"+i,S[i]!=b.toString());
        }
    }

    public void testToString()
    {
        for (int i=0; i<S.length; i++)
        {
            String s="S0S1s2s3";
            ByteArrayBuffer buf=new ByteArrayBuffer(s.getBytes(),i*2,2);
            String b=cache.toString(buf);

            assertEquals(S[i],b);
            if (i>0)
                assertTrue(S[i]==b);
            else
                assertTrue(S[i]!=b);
        }
    }

}
