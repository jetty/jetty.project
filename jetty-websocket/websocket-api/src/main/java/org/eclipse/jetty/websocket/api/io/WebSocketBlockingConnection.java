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

package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;

/**
 * For working with the {@link WebSocketConnection} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private final WebSocketConnection conn;

    public WebSocketBlockingConnection(WebSocketConnection conn)
    {
        this.conn = conn;
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
            Future<Void> blocker = conn.write(data,offset,length);
            blocker.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new WebSocketException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            throw new IOException(e.getCause());
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
            Future<Void> blocker = conn.write(message);
            blocker.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new WebSocketException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            throw new IOException(e.getCause());
        }
    }
}
