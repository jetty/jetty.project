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
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import javax.websocket.EncodeException;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;

public class JsrRemoteEndpoint implements RemoteEndpoint
{
    private final org.eclipse.jetty.websocket.api.RemoteEndpoint jettyRemote;
    
    protected JsrRemoteEndpoint(org.eclipse.jetty.websocket.api.RemoteEndpoint endpoint)
    {
        this.jettyRemote = endpoint;
    }

    @Override
    public void flushBatch() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public long getAsyncSendTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean getBatchingAllowed()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public OutputStream getSendStream() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Writer getSendWriter() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        jettyRemote.sendBytes(data);
    }

    @Override
    public void sendBytesByCompletion(ByteBuffer data, SendHandler completion)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public Future<SendResult> sendBytesByFuture(ByteBuffer data)
    {
        Future<Void> jettyFuture = jettyRemote.sendBytesByFuture(data);
        return new JsrSendResultFuture(jettyFuture);
    }

    @Override
    public void sendObject(Object o) throws IOException, EncodeException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendObjectByCompletion(Object o, SendHandler handler)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public Future<SendResult> sendObjectByFuture(Object o)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void sendPartialBytes(ByteBuffer partialByte, boolean isLast) throws IOException
    {
        jettyRemote.sendPartialBytes(partialByte,isLast);
    }

    @Override
    public void sendPartialString(String partialMessage, boolean isLast) throws IOException
    {
        jettyRemote.sendPartialString(partialMessage,isLast);
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
    public void sendString(String text) throws IOException
    {
        jettyRemote.sendString(text);
    }

    @Override
    public void sendStringByCompletion(String text, SendHandler completion)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public Future<SendResult> sendStringByFuture(String text)
    {
        Future<Void> jettyFuture = jettyRemote.sendStringByFuture(text);
        return new JsrSendResultFuture(jettyFuture);
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setBatchingAllowed(boolean allowed)
    {
        // TODO Auto-generated method stub

    }
}
