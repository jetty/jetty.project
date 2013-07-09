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

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.common.message.MessageWriter;
import org.eclipse.jetty.websocket.jsr356.encoders.EncodeFailedFuture;
import org.eclipse.jetty.websocket.jsr356.messages.SendHandlerWriteCallback;

public class JsrAsyncRemote implements RemoteEndpoint.Async
{
    private static final Logger LOG = Log.getLogger(JsrAsyncRemote.class);
    private final JsrSession session;
    private final WebSocketRemoteEndpoint jettyRemote;
    private final EncoderFactory encoders;

    protected JsrAsyncRemote(JsrSession session)
    {
        this.session = session;
        if (!(session.getRemote() instanceof WebSocketRemoteEndpoint))
        {
            StringBuilder err = new StringBuilder();
            err.append("Unexpected implementation [");
            err.append(session.getRemote().getClass().getName());
            err.append("].  Expected an instanceof [");
            err.append(WebSocketRemoteEndpoint.class.getName());
            err.append("]");
            throw new IllegalStateException(err.toString());
        }
        this.jettyRemote = (WebSocketRemoteEndpoint)session.getRemote();
        this.encoders = session.getEncoderFactory();
    }

    private void assertMessageNotNull(Object data)
    {
        if (data == null)
        {
            throw new IllegalArgumentException("message cannot be null");
        }
    }

    private void assertSendHandlerNotNull(SendHandler handler)
    {
        if (handler == null)
        {
            throw new IllegalArgumentException("SendHandler cannot be null");
        }
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
        assertMessageNotNull(data);
        return jettyRemote.sendBytesByFuture(data);
    }

    @Override
    public void sendBinary(ByteBuffer data, SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data).setFin(true);
        jettyRemote.sendFrame(frame,new SendHandlerWriteCallback(handler));
    }

    @SuppressWarnings(
    { "rawtypes", "unchecked" })
    @Override
    public Future<Void> sendObject(Object data)
    {
        LOG.debug("sendObject({})",data);
        assertMessageNotNull(data);

        Encoder encoder = encoders.getEncoderFor(data.getClass());
        if (encoder == null)
        {
            throw new IllegalArgumentException("No encoder for type: " + data.getClass());
        }

        if (encoder instanceof Encoder.Text)
        {
            Encoder.Text etxt = (Encoder.Text)encoder;
            try
            {
                String msg = etxt.encode(data);
                return jettyRemote.sendStringByFuture(msg);
            }
            catch (EncodeException e)
            {
                return new EncodeFailedFuture(data,etxt,Encoder.Text.class,e);
            }
        }
        else if (encoder instanceof Encoder.TextStream)
        {
            Encoder.TextStream etxt = (Encoder.TextStream)encoder;
            FutureWriteCallback callback = new FutureWriteCallback();
            try (MessageWriter writer = new MessageWriter(session))
            {
                writer.setCallback(callback);
                etxt.encode(data,writer);
                return callback;
            }
            catch (EncodeException | IOException e)
            {
                return new EncodeFailedFuture(data,etxt,Encoder.Text.class,e);
            }
        }
        else if (encoder instanceof Encoder.Binary)
        {
            Encoder.Binary ebin = (Encoder.Binary)encoder;
            try
            {
                ByteBuffer buf = ebin.encode(data);
                return jettyRemote.sendBytesByFuture(buf);
            }
            catch (EncodeException e)
            {
                return new EncodeFailedFuture(data,ebin,Encoder.Binary.class,e);
            }
        }
        else if (encoder instanceof Encoder.BinaryStream)
        {
            Encoder.BinaryStream ebin = (Encoder.BinaryStream)encoder;
            FutureWriteCallback callback = new FutureWriteCallback();
            try (MessageOutputStream out = new MessageOutputStream(session))
            {
                out.setCallback(callback);
                ebin.encode(data,out);
                return callback;
            }
            catch (EncodeException | IOException e)
            {
                return new EncodeFailedFuture(data,ebin,Encoder.Binary.class,e);
            }
        }

        throw new IllegalArgumentException("Unknown encoder type: " + encoder);
    }

    @SuppressWarnings(
    { "rawtypes", "unchecked" })
    @Override
    public void sendObject(Object data, SendHandler handler)
    {
        LOG.debug("sendObject({},{})",data,handler);
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);

        Encoder encoder = encoders.getEncoderFor(data.getClass());
        if (encoder == null)
        {
            throw new IllegalArgumentException("No encoder for type: " + data.getClass());
        }

        if (encoder instanceof Encoder.Text)
        {
            Encoder.Text etxt = (Encoder.Text)encoder;
            try
            {
                String msg = etxt.encode(data);
                sendText(msg,handler);
                return;
            }
            catch (EncodeException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.TextStream)
        {
            Encoder.TextStream etxt = (Encoder.TextStream)encoder;
            SendHandlerWriteCallback callback = new SendHandlerWriteCallback(handler);
            try (MessageWriter writer = new MessageWriter(session))
            {
                writer.setCallback(callback);
                etxt.encode(data,writer);
                return;
            }
            catch (EncodeException | IOException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.Binary)
        {
            Encoder.Binary ebin = (Encoder.Binary)encoder;
            try
            {
                ByteBuffer buf = ebin.encode(data);
                sendBinary(buf,handler);
                return;
            }
            catch (EncodeException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.BinaryStream)
        {
            Encoder.BinaryStream ebin = (Encoder.BinaryStream)encoder;
            SendHandlerWriteCallback callback = new SendHandlerWriteCallback(handler);
            try (MessageOutputStream out = new MessageOutputStream(session))
            {
                out.setCallback(callback);
                ebin.encode(data,out);
                return;
            }
            catch (EncodeException | IOException e)
            {
                handler.onResult(new SendResult(e));
            }
        }

        throw new IllegalArgumentException("Unknown encoder type: " + encoder);
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
        assertMessageNotNull(text);
        return jettyRemote.sendStringByFuture(text);
    }

    @Override
    public void sendText(String text, SendHandler handler)
    {
        assertMessageNotNull(text);
        assertSendHandlerNotNull(handler);
        WebSocketFrame frame = WebSocketFrame.text(text).setFin(true);
        jettyRemote.sendFrame(frame,new SendHandlerWriteCallback(handler));
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
