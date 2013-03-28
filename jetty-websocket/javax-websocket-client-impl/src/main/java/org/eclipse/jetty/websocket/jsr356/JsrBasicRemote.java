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

import javax.websocket.EncodeException;
import javax.websocket.RemoteEndpoint;

import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.common.message.MessageWriter;

public class JsrBasicRemote implements RemoteEndpoint.Basic
{
    private final WebSocketSession jettySession;
    private final org.eclipse.jetty.websocket.api.RemoteEndpoint jettyRemote;
    private boolean batchingAllowed = false;

    protected JsrBasicRemote(WebSocketSession session)
    {
        this.jettySession = session;
        this.jettyRemote = jettySession.getRemote();
    }

    @Override
    public void flushBatch() throws IOException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean getBatchingAllowed()
    {
        return batchingAllowed;
    }

    @Override
    public OutputStream getSendStream() throws IOException
    {
        return new MessageOutputStream(jettySession);
    }

    @Override
    public Writer getSendWriter() throws IOException
    {
        return new MessageWriter(jettySession);
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException
    {
        jettyRemote.sendBytes(data);
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException
    {
        jettyRemote.sendPartialBytes(partialByte,isLast);
    }

    @Override
    public void sendObject(Object o) throws IOException, EncodeException
    {
        // TODO Find appropriate Encoder and encode for output
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
    public void sendText(String text) throws IOException
    {
        jettyRemote.sendString(text);
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException
    {
        jettyRemote.sendPartialString(partialMessage,isLast);
    }

    @Override
    public void setBatchingAllowed(boolean allowed)
    {
        this.batchingAllowed = allowed;
    }
}
