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
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;

/**
 * For working with the {@link Session} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private final RemoteEndpoint remote;

    public WebSocketBlockingConnection(Session session)
    {
        this.remote = session.getRemote();
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {
        ByteBuffer buf = ByteBuffer.wrap(data,offset,length);
        remote.sendBytes(buf);
    }

    /**
     * Send text message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(String message) throws IOException
    {
        remote.sendString(message);
    }
}
