// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public abstract class Extension implements OutgoingFrames, IncomingFrames
{
    private WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private ExtensionConfig config;
    private IncomingFrames nextIncomingFrames;
    private OutgoingFrames nextOutgoingFrames;

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public ExtensionConfig getConfig()
    {
        return config;
    }

    public String getName()
    {
        return config.getName();
    }

    public IncomingFrames getNextIncomingFrames()
    {
        return nextIncomingFrames;
    }

    public OutgoingFrames getNextOutgoingFrames()
    {
        return nextOutgoingFrames;
    }

    public String getParameterizedName()
    {
        return config.getParameterizedName();
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public void incoming(WebSocketException e)
    {
        // pass thru, un-modified
        nextIncomingFrames.incoming(e);
    }

    @Override
    public void incoming(WebSocketFrame frame)
    {
        // pass thru, un-modified
        nextIncomingFrames.incoming(frame);
    }

    /**
     * Convenience method for {@link #getNextIncomingFrames()#incoming(WebSocketException)}
     * 
     * @param e
     *            the exception to pass to the next input/incoming
     */
    public void nextIncoming(WebSocketException e)
    {
        nextIncomingFrames.incoming(e);
    }

    /**
     * Convenience method for {@link #getNextIncomingFrames()#incoming(WebSocketFrame)}
     * 
     * @param frame
     *            the frame to send to the next input/incoming
     */
    public void nextIncoming(WebSocketFrame frame)
    {
        nextIncomingFrames.incoming(frame);
    }

    /**
     * Convenience method for {@link #getNextOutgoingFrames()#output(WebSocketFrame)}
     * 
     * @param frame
     *            the frame to send to the next output
     */
    public <C> void nextOutput(C context, Callback<C> callback, WebSocketFrame frame)
    {
        nextOutgoingFrames.output(context,callback,frame);
    }

    public <C> void nextOutputNoCallback(WebSocketFrame frame)
    {
        nextOutgoingFrames.output(null,new FutureCallback<Void>(),frame);
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame)
    {
        // pass thru, un-modified
        nextOutgoingFrames.output(context,callback,frame);
    }

    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public void setConfig(ExtensionConfig config)
    {
        this.config = config;
    }

    public void setNextIncomingFrames(IncomingFrames nextIncomingFramesHandler)
    {
        this.nextIncomingFrames = nextIncomingFramesHandler;
    }

    public void setNextOutgoingFrames(OutgoingFrames nextOutgoingFramesHandler)
    {
        this.nextOutgoingFrames = nextOutgoingFramesHandler;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }
}
