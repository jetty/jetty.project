//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;

public class UpstreamConnection extends ProxyConnection
{
    private ConnectHandler.ConnectContext connectContext;

    public UpstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConnectHandler connectHandler, ConnectHandler.ConnectContext connectContext)
    {
        super(endPoint, executor, bufferPool, connectContext.getContext(), connectHandler);
        this.connectContext = connectContext;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        getConnectHandler().onConnectSuccess(connectContext, this);
        fillInterested();
    }
}
