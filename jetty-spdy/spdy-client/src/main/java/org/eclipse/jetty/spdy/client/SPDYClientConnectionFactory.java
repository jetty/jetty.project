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

package org.eclipse.jetty.spdy.client;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.FlowControlStrategy;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.client.SPDYClient.Factory;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;

public class SPDYClientConnectionFactory
{
    public Connection newConnection(EndPoint endPoint, Object attachment)
    {
        SPDYClient.SessionContext context = (SPDYClient.SessionContext)attachment;
        SPDYClient client = context.getSPDYClient();
        Factory factory = client.getFactory();
        ByteBufferPool bufferPool = factory.getByteBufferPool();

        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(bufferPool, compressionFactory.newCompressor());

        SPDYConnection connection = new ClientSPDYConnection(endPoint, bufferPool, parser, factory, client.isDispatchIO());

        FlowControlStrategy flowControlStrategy = client.newFlowControlStrategy();

        StandardSession session = new StandardSession(client.getVersion(), bufferPool, factory.getExecutor(),
                factory.getScheduler(), connection, endPoint, connection, 1, context.getSessionFrameListener(),
                generator, flowControlStrategy);
        session.setWindowSize(client.getInitialWindowSize());
        parser.addListener(session);
        connection.setSession(session);

        /*connection = context.getConnectionProvider().decorateConnection(endPoint, connection);*/

        context.succeeded(session);

        return connection;
    }

    private class ClientSPDYConnection extends SPDYConnection
    {
        private final Factory factory;

        public ClientSPDYConnection(EndPoint endPoint, ByteBufferPool bufferPool, Parser parser, Factory factory, boolean dispatchIO)
        {
            super(endPoint, bufferPool, parser, factory.getExecutor(), dispatchIO);
            this.factory = factory;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            factory.sessionOpened(getSession());
        }

        @Override
        public void onClose()
        {
            super.onClose();
            factory.sessionClosed(getSession());
        }
    }
}
