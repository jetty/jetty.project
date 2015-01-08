//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2Connection extends AbstractConnection
{
    protected static final Logger LOG = Log.getLogger(HTTP2Connection.class);

    private final ByteBufferPool byteBufferPool;
    private final Parser parser;
    private final ISession session;
    private final int bufferSize;

    public HTTP2Connection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, Parser parser, ISession session, int bufferSize, boolean dispatchIO)
    {
        super(endPoint, executor, dispatchIO);
        this.byteBufferPool = byteBufferPool;
        this.parser = parser;
        this.session = session;
        this.bufferSize = bufferSize;
    }

    protected ISession getSession()
    {
        return session;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Open {} ", this);
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Close {} ", this);
        super.onClose();
    }

    @Override
    public void onFillable()
    {
        ByteBuffer buffer = byteBufferPool.acquire(bufferSize, false);
        boolean readMore = read(buffer) == 0;
        byteBufferPool.release(buffer);
        if (readMore)
            fillInterested();
    }

    protected int read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        while (true)
        {
            int filled = fill(endPoint, buffer);
            if (filled == 0)
            {
                return 0;
            }
            else if (filled < 0)
            {
                session.onShutdown();
                return -1;
            }
            else
            {
                parser.parse(buffer);
            }
        }
    }

    private int fill(EndPoint endPoint, ByteBuffer buffer)
    {
        try
        {
            if (endPoint.isInputShutdown())
                return -1;
            return endPoint.fill(buffer);
        }
        catch (IOException x)
        {
            LOG.debug("Could not read from " + endPoint, x);
            return -1;
        }
    }

    @Override
    protected boolean onReadTimeout()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout {}ms expired on {}", getEndPoint().getIdleTimeout(), this);
        session.onIdleTimeout();
        return false;
    }
}
