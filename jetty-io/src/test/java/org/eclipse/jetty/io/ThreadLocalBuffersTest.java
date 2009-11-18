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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.TestCase;

public class ThreadLocalBuffersTest
    extends TestCase
{
    public boolean _stress = Boolean.getBoolean("STRESS");
    private int _headerBufferSize = 6 * 1024;

    InnerBuffers httpBuffers;

    List<Thread> threadList = new ArrayList<Thread>();

    int numThreads = _stress?100:10;

    int runTestLength = _stress?5000:1000;

    boolean runTest = false;

    AtomicLong buffersRetrieved;

    @Override
    protected void setUp()
        throws Exception
    {
        super.setUp();
    }

    @Override
    protected void tearDown()
        throws Exception
    {
        super.tearDown();
    }

    public void execAbstractBuffer()
        throws Exception
    {
        threadList.clear();
        buffersRetrieved = new AtomicLong( 0 );
        httpBuffers = new InnerBuffers();

        for ( int i = 0; i < numThreads; ++i )
        {
            threadList.add( new BufferPeeper( "BufferPeeper: " + i ) );
        }

        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        long mem0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        runTest = true;

        Thread.sleep( runTestLength );

        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        long mem1 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        runTest = false;

        long totalBuffersRetrieved = buffersRetrieved.get();

        System.out.println( "Buffers Retrieved: " + totalBuffersRetrieved );
        System.out.println( "Memory Used: " + ( mem1 - mem0 ) );

        for ( Iterator<Thread> i = threadList.iterator(); i.hasNext(); )
        {
            Thread t = i.next();
            t.stop();
        }
    }

    public void testAbstractBuffers()
        throws Exception
    {
        execAbstractBuffer( );
    }

    public void testDifferentSizes()
    throws Exception
    {
        InnerBuffers buffers = new InnerBuffers();
        buffers.setHeaderSize(128);
        buffers.setBufferSize(256);
        
        Buffer h1 = buffers.getHeader();
        Buffer h2 = buffers.getHeader();
        Buffer b1 = buffers.getBuffer();
        Buffer b2 = buffers.getBuffer();
        Buffer b3 = buffers.getBuffer(512);
        
        buffers.returnBuffer(h1);
        buffers.returnBuffer(h2);
        buffers.returnBuffer(b1);
        buffers.returnBuffer(b2);
        buffers.returnBuffer(b3);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        assertTrue(h2!=buffers.getHeader()); // b2 replaced h2 in other slot
        assertTrue(b1==buffers.getBuffer()); // pooled buffer
        assertTrue(b2!=buffers.getBuffer()); // b3 replaced b2 in other slot
        assertTrue(b3==buffers.getBuffer(512)); // b2 from other slot
        
        buffers.returnBuffer(h1);
        buffers.returnBuffer(h2);
        buffers.returnBuffer(b1);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        assertTrue(h2==buffers.getHeader()); // h2 in other slot
        assertTrue(b1==buffers.getBuffer()); // pooled buffer
        assertTrue(b2!=buffers.getBuffer()); // new buffer
        assertTrue(b3!=buffers.getBuffer(512)); // new buffer
        

        // check that sizes are respected
        buffers.returnBuffer(b3);
        buffers.returnBuffer(b1);
        buffers.returnBuffer(b2);
        buffers.returnBuffer(h1);
        buffers.returnBuffer(h2);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        assertTrue(h2==buffers.getHeader()); // h2 in other slot
        assertTrue(b1==buffers.getBuffer()); // pooled buffer
        assertTrue(b2!=buffers.getBuffer()); // new buffer
        assertTrue(b3!=buffers.getBuffer(512)); // new buffer
    }
    
    public void testSameSizes()
    throws Exception
    {
        Buffer buffer=null;
        InnerBuffers buffers = new InnerBuffers();
        buffers.setHeaderSize(128);
        buffers.setBufferSize(128);
        
        Buffer h1 = buffers.getHeader();
        Buffer h2 = buffers.getHeader();
        Buffer b1 = buffers.getBuffer();
        Buffer b2 = buffers.getBuffer();
        Buffer b3 = buffers.getBuffer(128);
        List<Buffer> known = new ArrayList<Buffer>();
        known.add(h1);
        known.add(h2);
        known.add(b1);
        known.add(b2);
        known.add(h1);
        
        buffers.returnBuffer(h1);
        buffers.returnBuffer(h2);
        buffers.returnBuffer(b1);
        buffers.returnBuffer(b2);
        buffers.returnBuffer(b3);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        buffer=buffers.getHeader();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        assertTrue(h2==buffers.getBuffer()); // h2 used from buffer slot
        assertTrue(b3==buffers.getBuffer()); // b1 from other slot
        buffer=buffers.getBuffer(128);
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        

        buffers.returnBuffer(h1);
        buffers.returnBuffer(h2);
        buffers.returnBuffer(b1);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        buffer=buffers.getHeader();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        assertTrue(h2==buffers.getBuffer()); // h2 used from buffer slot
        assertTrue(b1==buffers.getBuffer()); // h2 used from other slot

        buffers.returnBuffer(h1);
        buffers.returnBuffer(b1);
        buffers.returnBuffer(h2);
        
        assertTrue(h1==buffers.getHeader()); // pooled header
        assertTrue(h2==buffers.getHeader()); // h2 from other slot
        buffer=buffers.getHeader();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        assertTrue(b1==buffers.getBuffer()); // b1 used from buffer slot
        buffer=buffers.getBuffer();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        
        
    }

    static class HeaderBuffer extends ByteArrayBuffer
    {
        public HeaderBuffer(int size)
        {
            super(size);
        }
    }
    
    static class InnerBuffers extends ThreadLocalBuffers
    {
        @Override
        protected Buffer newBuffer(int size)
        {
            return new ByteArrayBuffer( size );
        }
        @Override
        protected Buffer newHeader(int size)
        {
            return new HeaderBuffer( size );
        }
        @Override
        protected boolean isHeader(Buffer buffer)
        {
            return buffer instanceof HeaderBuffer;
        }
    }


    /**
     * generic buffer peeper
     * 
     * 
     */
    class BufferPeeper
        extends Thread
    {
        private String _bufferName;

        public BufferPeeper( String bufferName )
        {
            _bufferName = bufferName;

            start();
        }

        @Override
        public void run()
        {
            while ( true )
            {
                try
                {

                    if ( runTest )
                    {
                        Buffer buf = httpBuffers.getHeader();

                        buffersRetrieved.getAndIncrement();
                        

                        buf.put( new Byte( "2" ).byteValue() );

                        // sleep( threadWaitTime );

                        httpBuffers.returnBuffer(buf);
                    }
                    else
                    {
                        sleep( 1 );
                    }
                }
                catch ( Exception e )
                {
                    e.printStackTrace();
                    break;
                }
            }
        }
    }

}
