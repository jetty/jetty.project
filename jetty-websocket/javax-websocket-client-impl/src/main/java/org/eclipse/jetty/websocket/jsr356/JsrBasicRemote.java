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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.websocket.EncodeException;
import javax.websocket.RemoteEndpoint;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.common.message.MessageWriter;
import org.eclipse.jetty.websocket.common.util.TextUtil;

public class JsrBasicRemote extends AbstractJsrRemote implements RemoteEndpoint.Basic
{
    private static final Logger LOG = Log.getLogger(JsrBasicRemote.class);

    protected JsrBasicRemote(JsrSession session)
    {
        super(session);
    }

    @Override
    public OutputStream getSendStream() throws IOException
    {
        return new MessageOutputStream(session);
    }

    @Override
    public Writer getSendWriter() throws IOException
    {
        return new MessageWriter(session);
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException
    {
        assertMessageNotNull(data);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({})",BufferUtil.toDetailString(data));
        }
        jettyRemote.sendBytes(data);
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException
    {
        assertMessageNotNull(partialByte);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({},{})",BufferUtil.toDetailString(partialByte),isLast);
        }
        jettyRemote.sendPartialBytes(partialByte,isLast);
    }

    @Override
    public void sendObject(Object data) throws IOException, EncodeException
    {
        // TODO avoid the use of a Future
        Future<Void> fut = sendObjectViaFuture(data);
        try
        {
            fut.get(); // block till done
        }
        catch (ExecutionException e)
        {
            throw new IOException("Failed to write object",e.getCause());
        }
        catch (InterruptedException e)
        {
            throw new IOException("Failed to write object",e);
        }
    }

    @Override
    public void sendText(String text) throws IOException
    {
        assertMessageNotNull(text);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({})",TextUtil.hint(text));
        }
        jettyRemote.sendString(text);
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException
    {
        assertMessageNotNull(partialMessage);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({},{})",TextUtil.hint(partialMessage),isLast);
        }
        jettyRemote.sendPartialString(partialMessage,isLast);
    }
}
