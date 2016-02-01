//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * Echo back the incoming text or binary as 2 frames of (roughly) equal size.
 */
@WebSocket
public class EchoFragmentSocket
{
    @OnWebSocketFrame
    public void onFrame(Session session, Frame frame)
    {
        if (!frame.getType().isData())
        {
            // Don't process non-data frames
            return;
        }

        ByteBuffer data = frame.getPayload();

        int half = data.remaining() / 2;

        ByteBuffer buf1 = data.slice();
        ByteBuffer buf2 = data.slice();

        buf1.limit(half);
        buf2.position(half);

        RemoteEndpoint remote = session.getRemote();
        try
        {
            switch (frame.getType())
            {
                case BINARY:
                    remote.sendBytes(buf1,null);
                    remote.sendBytes(buf2,null);
                    break;
                case TEXT:
                    // NOTE: This impl is not smart enough to split on a UTF8 boundary
                    remote.sendString(BufferUtil.toUTF8String(buf1),null);
                    remote.sendString(BufferUtil.toUTF8String(buf2),null);
                    break;
                default:
                    throw new IOException("Unexpected frame type: " + frame.getType());
            }
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}
