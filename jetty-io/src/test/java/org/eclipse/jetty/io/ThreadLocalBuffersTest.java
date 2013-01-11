//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.toolchain.test.Stress;
import org.junit.Test;

public class ThreadLocalBuffersTest
{
    private Buffers httpBuffers;
    private List<Thread> threadList = new ArrayList<Thread>();
    private int numThreads = Stress.isEnabled()?100:10;
    private int runTestLength = Stress.isEnabled()?5000:1000;
    private boolean runTest = false;
    private AtomicLong buffersRetrieved;

    private void execAbstractBuffer() throws Exception
    {
        threadList.clear();
        buffersRetrieved = new AtomicLong( 0 );
        httpBuffers = new InnerBuffers(1024,4096);

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

        for (Thread t : threadList)
            t.stop();
    }

    @Test
    public void testAbstractBuffers() throws Exception
    {
        execAbstractBuffer( );
    }

    @Test
    public void testDifferentSizes() throws Exception
    {
        InnerBuffers buffers = new InnerBuffers(128,256);

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

    @Test
    public void testSameSizes() throws Exception
    {
        InnerBuffers buffers = new InnerBuffers(128,128);

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
        known.add(b3);

        buffers.returnBuffer(h1); // header slot  *
        buffers.returnBuffer(h2); // other slot
        buffers.returnBuffer(b1); // buffer slot  *
        buffers.returnBuffer(b2); // other slot
        buffers.returnBuffer(b3); // other slot   *

        assertTrue(h1==buffers.getHeader()); // pooled header
        Buffer buffer = buffers.getHeader();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        assertTrue(b1==buffers.getBuffer()); // b1 used from buffer slot
        buffer = buffers.getBuffer();
        for (Buffer b:known) assertTrue(b!=buffer); // new buffer
        
        assertTrue(b3==buffers.getBuffer(128)); // b3 from other slot

    }

    private static class HeaderBuffer extends ByteArrayBuffer
    {
        public HeaderBuffer(int size)
        {
            super(size);
        }
    }

    private static class InnerBuffers extends ThreadLocalBuffers
    {
        InnerBuffers(int headerSize,int bufferSize)
        {
            super(Type.DIRECT,headerSize,Type.BYTE_ARRAY,bufferSize,Type.INDIRECT);
        }
    }

    private class BufferPeeper extends Thread
    {
        private final String _bufferName;

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

                        buf.put(new Byte("2"));

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
