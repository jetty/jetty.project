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

package org.eclipse.jetty.websocket.core.api;

import java.io.IOException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.io.IncomingFrames;
import org.eclipse.jetty.websocket.core.io.OutgoingFrames;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

public abstract class Extension implements OutgoingFrames, IncomingFrames
{
    private Logger LOG = Log.getLogger(this.getClass());
    private WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private ExtensionConfig config;
    private IncomingFrames nextIncomingFrames;
    private OutgoingFrames nextOutgoingFrames;
    private WebSocketConnection connection;

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public ExtensionConfig getConfig()
    {
        return config;
    }

    public WebSocketConnection getConnection()
    {
        return connection;
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
     * Used to indicate that the extension makes use of the RSV1 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV1.
     * 
     * @return true if extension uses RSV1 for its own purposes.
     */
    public boolean isRsv1User()
    {
        return false;
    }

    /**
     * Used to indicate that the extension makes use of the RSV2 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV2.
     * 
     * @return true if extension uses RSV2 for its own purposes.
     */
    public boolean isRsv2User()
    {
        return false;
    }

    /**
     * Used to indicate that the extension makes use of the RSV3 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV3.
     * 
     * @return true if extension uses RSV3 for its own purposes.
     */
    public boolean isRsv3User()
    {
        return false;
    }

    /**
     * Used to indicate that the extension works as a decoder of TEXT Data Frames.
     * <p>
     * This is used to adjust validation during parsing/generating, as per spec TEXT Data Frames can only contain UTF8 encoded String data.
     * <p>
     * Example: a compression extension will process a compressed set of text data, the parser/generator should no longer be concerned about the validity of the
     * TEXT Data Frames as this is now the responsibility of the extension.
     * 
     * @return true if extension will process TEXT Data Frames, false if extension makes no modifications of TEXT Data Frames. If false, the parser/generator is
     *         now free to validate the conformance to spec of TEXT Data Frames.
     */
    public boolean isTextDataDecoder()
    {
        return false;
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

    public void setConnection(WebSocketConnection connection)
    {
        this.connection = connection;
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
