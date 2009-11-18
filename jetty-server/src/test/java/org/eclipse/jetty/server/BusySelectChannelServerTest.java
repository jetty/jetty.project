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

package org.eclipse.jetty.server;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * HttpServer Tester.
 */
public class BusySelectChannelServerTest extends HttpServerTestBase
{
   
    public BusySelectChannelServerTest()
    {
        super(new SelectChannelConnector()
        {
            /* ------------------------------------------------------------ */
            /* (non-Javadoc)
             * @see org.eclipse.jetty.server.server.nio.SelectChannelConnector#newEndPoint(java.nio.channels.SocketChannel, org.eclipse.io.nio.SelectorManager.SelectSet, java.nio.channels.SelectionKey)
             */
            @Override
            protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
            {
                return new SelectChannelEndPoint(channel,selectSet,key)
                {
                    int write;
                    int read;
                    
                    /* ------------------------------------------------------------ */
                    /* (non-Javadoc)
                     * @see org.eclipse.io.nio.SelectChannelEndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
                     */
                    @Override
                    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
                    {
                        int x=write++&0xff;
                        if (x<8)
                            return 0;
                        if (x<32)
                            return flush(header);
                        return super.flush(header,buffer,trailer);
                    }

                    /* ------------------------------------------------------------ */
                    /* (non-Javadoc)
                     * @see org.eclipse.io.nio.SelectChannelEndPoint#flush(org.eclipse.io.Buffer)
                     */
                    @Override
                    public int flush(Buffer buffer) throws IOException
                    {
                        int x=write++&0xff;
                        if (x<8)
                            return 0;
                        if (x<32)
                        {
                            View v = new View(buffer);
                            v.setPutIndex(v.getIndex()+1);
                            int l=super.flush(v);
                            if (l>0)
                                buffer.skip(l);
                            return l;
                        }
                        return super.flush(buffer);
                    }

                    /* ------------------------------------------------------------ */
                    /* (non-Javadoc)
                     * @see org.eclipse.io.nio.ChannelEndPoint#fill(org.eclipse.io.Buffer)
                     */
                    @Override
                    public int fill(Buffer buffer) throws IOException
                    {
                        int x=read++&0xff;
                        if (x<8)
                            return 0;

                        if (x<16 && buffer.space()>=1)
                        {
                            NIOBuffer one = new IndirectNIOBuffer(1);
                            int l=super.fill(one);
                            if (l>0)
                                buffer.put(one.peek(0));
                            return l;
                        }
                        
                        if (x<24 && buffer.space()>=2)
                        {
                            NIOBuffer two = new IndirectNIOBuffer(2);
                            int l=super.fill(two);
                            if (l>0)
                                buffer.put(two.peek(0));
                            if (l>1)
                                buffer.put(two.peek(1));
                            return l;
                        }
                        
                        if (x<64 && buffer.space()>=3)
                        {
                            NIOBuffer three = new IndirectNIOBuffer(3);
                            int l=super.fill(three);
                            if (l>0)
                                buffer.put(three.peek(0));
                            if (l>1)
                                buffer.put(three.peek(1));
                            if (l>2)
                                buffer.put(three.peek(2));
                            return l;
                        }
                        
                        return super.fill(buffer);
                    }
                };
            }  
        }); 
    }  
    

    @Override
    protected void configServer(Server server)
    {
        server.setThreadPool(new QueuedThreadPool());
    }
}
