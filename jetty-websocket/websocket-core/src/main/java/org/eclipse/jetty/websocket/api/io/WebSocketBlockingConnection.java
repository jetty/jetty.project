// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.io.WebSocketSession;

/**
 * For working with the {@link WebSocketConnection} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private static class Blocker extends FutureCallback<String>
    {
        @Override
        public void completed(String context)
        {
            LOG.debug("completed({})",context);
            super.completed(context);
        }

        @Override
        public void failed(String context, Throwable cause)
        {
            LOG.debug("failed({},{})",context,cause);
            super.failed(context,cause);
        }

        @Override
        public String toString()
        {
            return String.format("%s[%s]",Blocker.class.getSimpleName(),super.toString());
        }
    }

    private static final Logger LOG = Log.getLogger(WebSocketBlockingConnection.class);
    private static final String CONTEXT_BINARY = "BLOCKING_BINARY";
    private static final String CONTEXT_TEXT = "BLOCKING_TEXT";
    private final WebSocketSession conn;

    public WebSocketBlockingConnection(WebSocketConnection conn)
    {
        if (conn instanceof WebSocketSession)
        {
            this.conn = (WebSocketSession)conn;
        }
        else
        {
            throw new IllegalArgumentException("WebSocketConnection must implement internal WebSocketSession interface");
        }
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {
        try
        {
            Blocker blocker = new Blocker();
            conn.write(CONTEXT_BINARY,blocker,data,offset,length);
            blocker.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    /**
     * Send text message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(String message) throws IOException
    {
        try
        {
            Blocker blocker = new Blocker();
            conn.write(CONTEXT_TEXT,blocker,message);
            blocker.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }
}
