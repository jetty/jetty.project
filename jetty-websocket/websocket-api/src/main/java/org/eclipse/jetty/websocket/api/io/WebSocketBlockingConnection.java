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

package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WriteResult;

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
            Future<WriteResult> blocker = conn.write(data,offset,length);
            WriteResult result = blocker.get(); // block till finished
            if (result.getException() != null)
            {
                throw new WebSocketException(result.getException());
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            throw new WebSocketException(e.getCause());
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
            Future<WriteResult> blocker = conn.write(message);
            WriteResult result = blocker.get(); // block till finished
            if (result.getException() != null)
            {
                throw new WebSocketException(result.getException());
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            throw new WebSocketException(e.getCause());
        }
    }
}
