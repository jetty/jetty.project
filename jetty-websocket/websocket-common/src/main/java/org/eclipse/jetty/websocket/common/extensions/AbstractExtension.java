//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.extensions;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

@ManagedObject("Abstract Extension")
public abstract class AbstractExtension extends AbstractLifeCycle implements Extension
{
    private final Logger log;
    private WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private ExtensionConfig config;
    private LogicalConnection connection;
    private OutgoingFrames nextOutgoing;
    private IncomingFrames nextIncoming;

    public AbstractExtension()
    {
        log = Log.getLogger(this.getClass());
    }

    @Deprecated
    public void init(WebSocketContainerScope container)
    {
        init(container.getPolicy(), container.getBufferPool());
    }

    public void init(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        this.bufferPool = bufferPool;
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    @Override
    public ExtensionConfig getConfig()
    {
        return config;
    }

    public LogicalConnection getConnection()
    {
        return connection;
    }

    @Override
    public String getName()
    {
        return config.getName();
    }

    @ManagedAttribute(name = "Next Incoming Frame Handler", readonly = true)
    public IncomingFrames getNextIncoming()
    {
        return nextIncoming;
    }

    @ManagedAttribute(name = "Next Outgoing Frame Handler", readonly = true)
    public OutgoingFrames getNextOutgoing()
    {
        return nextOutgoing;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Used to indicate that the extension makes use of the RSV1 bit of the base websocket framing.
     * <p>
     * This is used to adjust validation during parsing, as well as a checkpoint against 2 or more extensions all simultaneously claiming ownership of RSV1.
     *
     * @return true if extension uses RSV1 for its own purposes.
     */
    @Override
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
    @Override
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
    @Override
    public boolean isRsv3User()
    {
        return false;
    }

    protected void nextIncomingFrame(Frame frame)
    {
        if (log.isDebugEnabled())
            log.debug("nextIncomingFrame({})", frame);
        this.nextIncoming.incomingFrame(frame);
    }

    protected void nextOutgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        try
        {
            if (log.isDebugEnabled())
                log.debug("nextOutgoingFrame({})", frame);
            this.nextOutgoing.outgoingFrame(frame, callback, batchMode);
        }
        catch (Throwable t)
        {
            if (callback != null)
                callback.writeFailed(t);
            else
                log.warn(t);
        }
    }

    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public void setConfig(ExtensionConfig config)
    {
        this.config = config;
    }

    public void setConnection(LogicalConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames nextIncoming)
    {
        this.nextIncoming = nextIncoming;
    }

    @Override
    public void setNextOutgoingFrames(OutgoingFrames nextOutgoing)
    {
        this.nextOutgoing = nextOutgoing;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), config.getParameterizedName());
    }
}
