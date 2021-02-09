//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.server.examples.echo;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;

public class LogSocket implements WebSocketListener
{
    private boolean verbose = false;

    public boolean isVerbose()
    {
        return verbose;
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketBinary(byte[%d] payload, %d, %d)%n", payload.length, offset, len);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketClose(%d, %s)%n", statusCode, quote(reason));
        }
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketConnect(%s)%n", session);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketError((%s) %s)%n", cause.getClass().getName(), cause.getMessage());
            cause.printStackTrace(System.err);
        }
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketText(%s)%n", quote(message));
        }
    }

    private String quote(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }

    public void setVerbose(boolean verbose)
    {
        this.verbose = verbose;
    }
}
