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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;

import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.Parser;

/**
 * Represents the stack of Extensions.
 */
@ManagedObject("Extension Stack")
public class ExtensionStack extends ContainerLifeCycle implements IncomingFrames, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(ExtensionStack.class);

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private final ExtensionFactory factory;
    private List<Extension> extensions;
    private IncomingFrames nextIncoming;
    private OutgoingFrames nextOutgoing;

    public ExtensionStack(ExtensionFactory factory)
    {
        this.factory = factory;
    }

    public void configure(Generator generator)
    {
        generator.configureFromExtensions(extensions);
    }

    public void configure(Parser parser)
    {
        parser.configureFromExtensions(extensions);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        // Wire up Extensions
        if ((extensions != null) && (extensions.size() > 0))
        {
            ListIterator<Extension> exts = extensions.listIterator();

            // Connect outgoings
            while (exts.hasNext())
            {
                Extension ext = exts.next();
                ext.setNextOutgoingFrames(nextOutgoing);
                nextOutgoing = ext;

                if (ext instanceof LifeCycle)
                {
                    addBean(ext, true);
                }
            }

            // Connect incomings
            while (exts.hasPrevious())
            {
                Extension ext = exts.previous();
                ext.setNextIncomingFrames(nextIncoming);
                nextIncoming = ext;
            }
        }
    }

    @Override
    public String dumpSelf()
    {
        return String.format("%s@%x[size=%d,queueSize=%d]", getClass().getSimpleName(), hashCode(), extensions.size(), getQueueSize());
    }

    @ManagedAttribute(name = "Extension List", readonly = true)
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    private IncomingFrames getLastIncoming()
    {
        IncomingFrames last = nextIncoming;
        boolean done = false;
        while (!done)
        {
            if (last instanceof AbstractExtension)
            {
                last = ((AbstractExtension)last).getNextIncoming();
            }
            else
            {
                done = true;
            }
        }
        return last;
    }

    private OutgoingFrames getLastOutgoing()
    {
        OutgoingFrames last = nextOutgoing;
        boolean done = false;
        while (!done)
        {
            if (last instanceof AbstractExtension)
            {
                last = ((AbstractExtension)last).getNextOutgoing();
            }
            else
            {
                done = true;
            }
        }
        return last;
    }

    /**
     * Get the list of negotiated extensions, each entry being a full "name; params" extension configuration
     *
     * @return list of negotiated extensions
     */
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        List<ExtensionConfig> ret = new ArrayList<>();
        if (extensions == null)
        {
            return ret;
        }

        for (Extension ext : extensions)
        {
            if (ext.getName().charAt(0) == '@')
            {
                // special, internal-only extensions, not present on negotiation level
                continue;
            }
            ret.add(ext.getConfig());
        }
        return ret;
    }

    @ManagedAttribute(name = "Next Incoming Frames Handler", readonly = true)
    public IncomingFrames getNextIncoming()
    {
        return nextIncoming;
    }

    @ManagedAttribute(name = "Next Outgoing Frames Handler", readonly = true)
    public OutgoingFrames getNextOutgoing()
    {
        return nextOutgoing;
    }

    public boolean hasNegotiatedExtensions()
    {
        return (this.extensions != null) && (this.extensions.size() > 0);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        nextIncoming.incomingFrame(frame);
    }

    /**
     * Perform the extension negotiation.
     * <p>
     * For the list of negotiated extensions, use {@link #getNegotiatedExtensions()}
     *
     * @param configs the configurations being requested
     */
    public void negotiate(List<ExtensionConfig> configs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Extension Configs={}", configs);

        this.extensions = new ArrayList<>();

        String[] rsvClaims = new String[3];

        for (ExtensionConfig config : configs)
        {
            Extension ext = factory.newInstance(config);
            if (ext == null)
            {
                // Extension not present on this side
                continue;
            }

            // Check RSV
            if (ext.isRsv1User() && (rsvClaims[0] != null))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Not adding extension {}. Extension {} already claimed RSV1", config, rsvClaims[0]);
                continue;
            }
            if (ext.isRsv2User() && (rsvClaims[1] != null))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Not adding extension {}. Extension {} already claimed RSV2", config, rsvClaims[1]);
                continue;
            }
            if (ext.isRsv3User() && (rsvClaims[2] != null))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Not adding extension {}. Extension {} already claimed RSV3", config, rsvClaims[2]);
                continue;
            }

            // Add Extension
            extensions.add(ext);
            addBean(ext);

            if (LOG.isDebugEnabled())
                LOG.debug("Adding Extension: {}", config);

            // Record RSV Claims
            if (ext.isRsv1User())
            {
                rsvClaims[0] = ext.getName();
            }
            if (ext.isRsv2User())
            {
                rsvClaims[1] = ext.getName();
            }
            if (ext.isRsv3User())
            {
                rsvClaims[2] = ext.getName();
            }
        }
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        FrameEntry entry = new FrameEntry(frame, callback, batchMode);
        if (LOG.isDebugEnabled())
            LOG.debug("Queuing {}", entry);
        offerEntry(entry);
        flusher.iterate();
    }

    public void setNextIncoming(IncomingFrames nextIncoming)
    {
        this.nextIncoming = nextIncoming;
    }

    public void setNextOutgoing(OutgoingFrames nextOutgoing)
    {
        this.nextOutgoing = nextOutgoing;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        for (Extension extension : extensions)
        {
            if (extension instanceof AbstractExtension)
            {
                ((AbstractExtension)extension).setPolicy(policy);
            }
        }
    }

    private void offerEntry(FrameEntry entry)
    {
        synchronized (this)
        {
            entries.offer(entry);
        }
    }

    private FrameEntry pollEntry()
    {
        synchronized (this)
        {
            return entries.poll();
        }
    }

    private int getQueueSize()
    {
        synchronized (this)
        {
            return entries.size();
        }
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("ExtensionStack[");
        s.append("queueSize=").append(getQueueSize());
        s.append(",extensions=");
        if (extensions == null)
        {
            s.append("<null>");
        }
        else
        {
            s.append('[');
            boolean delim = false;
            for (Extension ext : extensions)
            {
                if (delim)
                {
                    s.append(',');
                }
                if (ext == null)
                {
                    s.append("<null>");
                }
                else
                {
                    s.append(ext.getName());
                }
                delim = true;
            }
            s.append(']');
        }
        s.append(",incoming=").append((this.nextIncoming == null) ? "<null>" : this.nextIncoming.getClass().getName());
        s.append(",outgoing=").append((this.nextOutgoing == null) ? "<null>" : this.nextOutgoing.getClass().getName());
        s.append("]");
        return s.toString();
    }

    private static class FrameEntry
    {
        private final Frame frame;
        private final WriteCallback callback;
        private final BatchMode batchMode;

        private FrameEntry(Frame frame, WriteCallback callback, BatchMode batchMode)
        {
            this.frame = frame;
            this.callback = callback;
            this.batchMode = batchMode;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class Flusher extends IteratingCallback implements WriteCallback
    {
        private FrameEntry current;

        @Override
        protected Action process() throws Exception
        {
            current = pollEntry();
            if (current == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Entering IDLE");
                return Action.IDLE;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Processing {}", current);
            nextOutgoing.outgoingFrame(current.frame, this, current.batchMode);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // This IteratingCallback never completes.
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            // This IteratingCallback never fails.
            // The callback are those provided by WriteCallback (implemented
            // below) and even in case of writeFailed() we call succeeded().
        }

        @Override
        public void writeSuccess()
        {
            // Notify first then call succeeded(), otherwise
            // write callbacks may be invoked out of order.
            notifyCallbackSuccess(current.callback);
            succeeded();
        }

        @Override
        public void writeFailed(Throwable x)
        {
            // Notify first, the call succeeded() to drain the queue.
            // We don't want to call failed(x) because that will put
            // this flusher into a final state that cannot be exited,
            // and the failure of a frame may not mean that the whole
            // connection is now invalid.
            notifyCallbackFailure(current.callback, x);
            succeeded();
        }

        private void notifyCallbackSuccess(WriteCallback callback)
        {
            try
            {
                if (callback != null)
                    callback.writeSuccess();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while notifying success of callback " + callback, x);
            }
        }

        private void notifyCallbackFailure(WriteCallback callback, Throwable failure)
        {
            try
            {
                if (callback != null)
                    callback.writeFailed(failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while notifying failure of callback " + callback, x);
            }
        }
    }
}
