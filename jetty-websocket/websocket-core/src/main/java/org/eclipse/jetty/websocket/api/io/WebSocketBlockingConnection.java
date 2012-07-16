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
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.io.RawConnection;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * For working with the {@link WebSocketConnection} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private final RawConnection conn;

    public WebSocketBlockingConnection(WebSocketConnection conn)
    {
        if (conn instanceof RawConnection)
        {
            this.conn = (RawConnection)conn;
        }
        else
        {
            throw new IllegalArgumentException("WebSocketConnection must implement internal RawConnection interface");
        }
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data,offset,length);
        try
        {
            FutureCallback<Void> blocking = new FutureCallback<>();
            conn.output(null,blocking,frame);
            blocking.get(); // block till finished
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
        WebSocketFrame frame = WebSocketFrame.text(message);
        try
        {
            FutureCallback<Void> blocking = new FutureCallback<>();
            conn.output(null,blocking,frame);
            blocking.get(); // block till finished
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
