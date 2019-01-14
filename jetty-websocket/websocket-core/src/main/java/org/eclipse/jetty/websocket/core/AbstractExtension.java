//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;

@ManagedObject("Abstract Extension")
public abstract class AbstractExtension implements Extension, Dumpable
{
    private final Logger log;
    private ByteBufferPool bufferPool;
    private ExtensionConfig config;
    private OutgoingFrames nextOutgoing;
    private IncomingFrames nextIncoming;
    private WebSocketChannel channel;

    public AbstractExtension()
    {
        log = Log.getLogger(this.getClass());
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        // incoming
        dumpWithHeading(out, indent, "incoming", this.nextIncoming);
        dumpWithHeading(out, indent, "outgoing", this.nextOutgoing);
    }

    protected void dumpWithHeading(Appendable out, String indent, String heading, Object bean) throws IOException
    {
        out.append(indent).append(" +- ");
        out.append(heading).append(" : ");
        out.append(bean.toString());
    }

    @Override
    public void init(ExtensionConfig config, ByteBufferPool bufferPool)
    {
        this.config = config;
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

    protected void nextIncomingFrame(Frame frame, Callback callback)
    {
        log.debug("nextIncomingFrame({})", frame);
        this.nextIncoming.onFrame(frame, callback);
    }

    protected void nextOutgoingFrame(Frame frame, Callback callback, boolean batch)
    {
        log.debug("nextOutgoingFrame({})", frame);
        this.nextOutgoing.sendFrame(frame, callback, batch);
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

    @Override
    public void setWebSocketChannel(WebSocketChannel webSocketChannel)
    {
        channel = webSocketChannel;
    }

    protected WebSocketChannel getWebSocketChannel()
    {
        return channel;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), config.getParameterizedName());
    }
}
