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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;

public class RawHTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private final ServerSessionListener listener;

    public RawHTTP2ServerConnectionFactory(ServerSessionListener listener)
    {
        this.listener = listener;
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }

    @Override
    protected ServerParser newServerParser(ByteBufferPool byteBufferPool, ServerParser.Listener listener)
    {
        // TODO: make maxHeaderSize configurable.
        return new ServerParser(byteBufferPool, listener, getMaxDynamicTableSize(), 8192);
    }
}
