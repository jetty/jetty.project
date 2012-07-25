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

import java.io.IOException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public abstract class Extension implements OutgoingFrames, IncomingFrames
{
    private Logger LOG = Log.getLogger(this.getClass());
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
        nextIncoming(e);
    }

    @Override
    public void incoming(WebSocketFrame frame)
    {
        // pass thru, un-modified
        nextIncoming(frame);
    }

    /**
     * Convenience method for {@link #getNextIncomingFrames()#incoming(WebSocketException)}
     * 
     * @param e
     *            the exception to pass to the next input/incoming
     */
    public void nextIncoming(WebSocketException e)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("nextIncoming({}) - {}",e,nextIncomingFrames);
        }
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("nextIncoming({}) - {}",frame,nextIncomingFrames);
        }
        nextIncomingFrames.incoming(frame);
    }

    /**
     * Convenience method for {@link #getNextOutgoingFrames()#output(WebSocketFrame)}
     * 
     * @param frame
     *            the frame to send to the next output
     */
    public <C> void nextOutput(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("nextOutput({}) - {}",frame,nextOutgoingFrames);
        }
        nextOutgoingFrames.output(context,callback,frame);
    }

    public <C> void nextOutputNoCallback(WebSocketFrame frame) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("nextOutput({}) - {}",frame,nextOutgoingFrames);
        }
        nextOutgoingFrames.output(null,new FutureCallback<Void>(),frame);
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
    {
        // pass thru, un-modified
        nextOutput(context,callback,frame);
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

    public boolean useRsv1()
    {
        return false;
    }

    public boolean useRsv2()
    {
        return false;
    }

    public boolean useRsv3()
    {
        return false;
    }
}
