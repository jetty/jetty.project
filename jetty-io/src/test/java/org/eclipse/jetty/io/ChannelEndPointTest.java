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

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.junit.AfterClass;
import org.junit.BeforeClass;

public class ChannelEndPointTest extends EndPointTest<ChannelEndPoint>
{
    static ServerSocketChannel connector;

    @BeforeClass
    public static void open() throws Exception
    {
        connector = ServerSocketChannel.open();
        connector.socket().bind(null);
    }

    @AfterClass
    public static void close() throws Exception
    {
        connector.close();
        connector=null;
    }

    @Override
    protected EndPointPair<ChannelEndPoint> newConnection() throws Exception
    {
        EndPointPair<ChannelEndPoint> c = new EndPointPair<>();

        c.client=new ChannelEndPoint(null,SocketChannel.open(connector.socket().getLocalSocketAddress()));
        c.server=new ChannelEndPoint(null,connector.accept());
        return c;
    }

    @Override
    public void testClientServerExchange() throws Exception
    {
        super.testClientServerExchange();
    }
}
