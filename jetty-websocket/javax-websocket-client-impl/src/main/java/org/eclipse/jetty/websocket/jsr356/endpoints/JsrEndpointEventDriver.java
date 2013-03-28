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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import javax.websocket.Session;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;

public class JsrEndpointEventDriver implements EventDriver, IJsrSession
{
    @Override
    public Session getJsrSession()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public WebSocketSession getSession()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void incomingError(WebSocketException e)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void incomingFrame(Frame frame)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onClose(CloseInfo close)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onConnect()
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onError(Throwable t)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onFrame(Frame frame)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onInputStream(InputStream stream)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onPong(ByteBuffer buffer)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onReader(Reader reader)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onTextMessage(String message)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void openSession(WebSocketSession session)
    {
        // TODO Auto-generated method stub

    }
}
