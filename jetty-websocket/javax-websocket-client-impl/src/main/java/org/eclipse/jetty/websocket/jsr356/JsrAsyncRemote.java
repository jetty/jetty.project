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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;

public class JsrAsyncRemote implements RemoteEndpoint.Async
{
    private final org.eclipse.jetty.websocket.api.RemoteEndpoint jettyRemote;

    protected JsrAsyncRemote(JsrSession session)
    {
        this.jettyRemote = session.getRemote();
    }

    @Override
    public void flushBatch() throws IOException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean getBatchingAllowed()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public long getSendTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Future<Void> sendBinary(ByteBuffer data)
    {
        return jettyRemote.sendBytesByFuture(data);
    }

    @Override
    public void sendBinary(ByteBuffer data, SendHandler handler)
    {
        // TODO: wrap the send handler?
        jettyRemote.sendBytesByFuture(data);
    }

    @Override
    public Future<Void> sendObject(Object data)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void sendObject(Object data, SendHandler handler)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException
    {
        jettyRemote.sendPing(applicationData);

    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException
    {
        jettyRemote.sendPong(applicationData);
    }

    @Override
    public Future<Void> sendText(String text)
    {
        return jettyRemote.sendStringByFuture(text);
    }

    @Override
    public void sendText(String text, SendHandler handler)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void setSendTimeout(long timeoutmillis)
    {
        // TODO Auto-generated method stub
    }
}
