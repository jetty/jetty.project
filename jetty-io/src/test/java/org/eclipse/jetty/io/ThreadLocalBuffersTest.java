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

    protected void setUp()
        throws Exception
    {
        super.setUp();
    }

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
            return new ByteArrayBuffer( size );
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
