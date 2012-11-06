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

package org.eclipse.jetty.websocket.common.extensions.mux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.websocket.SendResult;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.common.io.IncomingFrames;
import org.eclipse.jetty.websocket.common.io.InternalConnection;
import org.eclipse.jetty.websocket.common.io.OutgoingFrames;
import org.eclipse.jetty.websocket.common.io.WebSocketSession;

/**
 * MuxChannel, acts as WebSocketConnection for specific sub-channel.
 */
public class MuxChannel implements WebSocketConnection, InternalConnection, IncomingFrames, OutgoingFrames, SuspendToken
{
    private static final Logger LOG = Log.getLogger(MuxChannel.class);

    private final long channelId;
    private final Muxer muxer;
    private final AtomicBoolean inputClosed;
    private final AtomicBoolean outputClosed;
    private final AtomicBoolean suspendToken;
    private ConnectionState connectionState;
    private WebSocketPolicy policy;
    private WebSocketSession session;
    private IncomingFrames incoming;
    private String subProtocol;

    public MuxChannel(long channelId, Muxer muxer)
    {
        this.channelId = channelId;
        this.muxer = muxer;
        this.policy = muxer.getPolicy().clonePolicy();

        this.suspendToken = new AtomicBoolean(false);
        this.connectionState = ConnectionState.CONNECTING;

        this.inputClosed = new AtomicBoolean(false);
        this.outputClosed = new AtomicBoolean(false);
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        CloseInfo close = new CloseInfo(statusCode,reason);
        try
        {
            outgoingFrame(close.asFrame());
        }
        catch (IOException e)
        {
            LOG.warn("Unable to issue Close",e);
            disconnect();
        }
    }

    @Override
    public void configureFromExtensions(List<Extension> extensions)
    {
        /* ignore */
    }

    @Override
    public void disconnect()
    {
        this.connectionState = ConnectionState.CLOSED;
        // TODO: disconnect the virtual end-point?
    }

    public long getChannelId()
    {
        return channelId;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return muxer.getRemoteAddress();
    }

    @Override
    public URI getRequestURI()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public ConnectionState getState()
    {
        return this.connectionState;
    }

    @Override
    public String getSubProtocol()
    {
        return this.subProtocol;
    }

    /**
     * Incoming exceptions from Muxer.
     */
    @Override
    public void incomingError(WebSocketException e)
    {
        incoming.incomingError(e);
    }

    /**
     * Incoming frames from Muxer
     */
    @Override
    public void incomingFrame(WebSocketFrame frame)
    {
        incoming.incomingFrame(frame);
    }

    public boolean isActive()
    {
        return (getState() != ConnectionState.CLOSED);
    }

    @Override
    public boolean isInputClosed()
    {
        return inputClosed.get();
    }

    @Override
    public boolean isOpen()
    {
        return isActive() && muxer.isOpen();
    }

    @Override
    public boolean isOutputClosed()
    {
        return outputClosed.get();
    }

    @Override
    public boolean isReading()
    {
        return true;
    }

    public void onClose()
    {
        this.connectionState = ConnectionState.CLOSED;
    }

    @Override
    public void onCloseHandshake(boolean incoming, CloseInfo close)
    {
        boolean in = inputClosed.get();
        boolean out = outputClosed.get();
        if (incoming)
        {
            in = true;
            this.inputClosed.set(true);
        }
        else
        {
            out = true;
            this.outputClosed.set(true);
        }

        LOG.debug("onCloseHandshake({},{}), input={}, output={}",incoming,close,in,out);

        if (in && out)
        {
            LOG.debug("Close Handshake satisfied, disconnecting");
            this.disconnect();
        }

        if (close.isHarsh())
        {
            LOG.debug("Close status code was harsh, disconnecting");
            this.disconnect();
        }
    }

    public void onOpen()
    {
        this.connectionState = ConnectionState.OPEN;
    }

    /**
     * Frames destined for the Muxer
     */
    @Override
    public Future<SendResult> outgoingFrame(WebSocketFrame frame) throws IOException
    {
        return muxer.output(channelId,frame);
    }

    /**
     * Ping frame destined for the Muxer
     */
    @Override
    public Future<SendResult> ping(byte[] payload) throws IOException
    {
        return outgoingFrame(WebSocketFrame.ping().setPayload(payload));
    }

    @Override
    public void resume()
    {
        if (suspendToken.getAndSet(false))
        {
            // TODO: Start reading again. (how?)
        }
    }

    @Override
    public void setIncoming(IncomingFrames incoming)
    {
        this.incoming = incoming;
    }

    @Override
    public void setSession(WebSocketSession session)
    {
        this.session = session;
        session.setOutgoing(this);
    }

    public void setSubProtocol(String subProtocol)
    {
        this.subProtocol = subProtocol;
    }

    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        // TODO: how to suspend reading?
        return this;
    }

    public void wireUpExtensions(List<AbstractExtension> extensions)
    {
        // Start with default routing.
        incoming = session;
        OutgoingFrames outgoing = this;

        if (extensions != null)
        {
            // FIXME
            // Iterator<AbstractExtension> extIter;
            // // Connect outgoings
            // extIter = extensions.iterator();
            // while (extIter.hasNext())
            // {
            // AbstractExtension ext = extIter.next();
            // ext.setNextOutgoingFrames(outgoing);
            // outgoing = ext;
            // }
            //
            // // Connect incomings
            // Collections.reverse(extensions);
            // extIter = extensions.iterator();
            // while (extIter.hasNext())
            // {
            // AbstractExtension ext = extIter.next();
            // ext.setNextIncomingFrames(incoming);
            // incoming = ext;
            // }
        }

        // set outgoing
        this.session.setOutgoing(outgoing);
    }

    /**
     * Generate a binary message, destined for Muxer
     */
    @Override
    public Future<SendResult> write(byte[] buf, int offset, int len) throws IOException
    {
        return outgoingFrame(WebSocketFrame.binary().setPayload(buf,offset,len));
    }

    /**
     * Generate a binary message, destined for Muxer
     */
    @Override
    public Future<SendResult> write(ByteBuffer buffer) throws IOException
    {
        return outgoingFrame(WebSocketFrame.binary().setPayload(buffer));
    }

    /**
     * Generate a text message, destined for Muxer
     */
    @Override
    public Future<SendResult> write(String message) throws IOException
    {
        return outgoingFrame(WebSocketFrame.text(message));
    }
}
