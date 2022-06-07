//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Extension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the stack of Extensions.
 */
@ManagedObject("Extension Stack")
public class ExtensionStack implements IncomingFrames, OutgoingFrames, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ExtensionStack.class);

    private final WebSocketComponents components;
    private final Behavior behavior;
    private List<Extension> extensions;
    private IncomingFrames incoming;
    private OutgoingFrames outgoing;
    private final Extension[] rsvClaims = new Extension[3];
    private LongConsumer lastDemand;
    private DemandChain demandChain = n -> lastDemand.accept(n);

    public ExtensionStack(WebSocketComponents components, Behavior behavior)
    {
        this.components = components;
        this.behavior = behavior;
    }

    public void close()
    {
        for (Extension e : extensions)
        {
            e.close();
        }
    }

    @ManagedAttribute(name = "Extension List", readonly = true)
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    /**
     * Get the list of negotiated extensions, each entry being a full "name; params" extension configuration
     *
     * @return list of negotiated extensions
     */
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        if (extensions == null)
            return Collections.emptyList();

        return extensions.stream().filter(e -> !e.getName().startsWith("@")).map(Extension::getConfig).collect(Collectors.toList());
    }

    @ManagedAttribute(name = "Next Incoming Frames Handler", readonly = true)
    public IncomingFrames getNextIncoming()
    {
        return incoming;
    }

    @ManagedAttribute(name = "Next Outgoing Frames Handler", readonly = true)
    public OutgoingFrames getNextOutgoing()
    {
        return outgoing;
    }

    public boolean hasNegotiatedExtensions()
    {
        return (this.extensions != null) && (this.extensions.size() > 0);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (incoming == null)
            throw new IllegalStateException();
        incoming.onFrame(frame, callback);
    }

    /**
     * Perform the extension negotiation.
     * <p>
     * For the list of negotiated extensions, use {@link #getNegotiatedExtensions()}
     *
     * @param offeredConfigs the configurations being requested by the client
     * @param negotiatedConfigs the configurations accepted by the server
     */
    public void negotiate(List<ExtensionConfig> offeredConfigs, List<ExtensionConfig> negotiatedConfigs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Extension Configs={}", negotiatedConfigs);

        this.extensions = new ArrayList<>();

        for (ExtensionConfig config : negotiatedConfigs)
        {
            Extension ext;

            try
            {
                ext = components.getExtensionRegistry().newInstance(config, components);
            }
            catch (Throwable t)
            {
                /* If there was an error creating the extension we need to differentiate between a
                bad ExtensionConfig offered by the client and a bad ExtensionConfig negotiated by the server.

                When deciding whether to throw a BadMessageException and send a 400 response or a WebSocketException
                and send a 500 response it depends on whether this is running on the client or the server. */
                switch (behavior)
                {
                    case SERVER:
                    {
                        String parameterizedName = config.getParameterizedName();
                        for (ExtensionConfig offered : offeredConfigs)
                        {
                            if (offered.getParameterizedName().equals(parameterizedName))
                                throw new BadMessageException("could not instantiate offered extension", t);
                        }
                        throw new WebSocketException("could not instantiate negotiated extension", t);
                    }
                    case CLIENT:
                    {
                        String parameterizedName = config.getParameterizedName();
                        for (ExtensionConfig offered : offeredConfigs)
                        {
                            if (offered.getParameterizedName().equals(parameterizedName))
                                throw new WebSocketException("could not instantiate offered extension", t);
                        }
                        throw new BadMessageException("could not instantiate negotiated extension", t);
                    }
                    default:
                        throw new IllegalStateException();
                }
            }

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

            if (LOG.isDebugEnabled())
                LOG.debug("Adding Extension: {}", config);

            // Record RSV Claims
            if (ext.isRsv1User())
                rsvClaims[0] = ext;
            if (ext.isRsv2User())
                rsvClaims[1] = ext;
            if (ext.isRsv3User())
                rsvClaims[2] = ext;
        }

        // Wire up Extensions and DemandChain.
        if ((extensions != null) && (extensions.size() > 0))
        {
            ListIterator<Extension> exts = extensions.listIterator();

            // Connect outgoings
            while (exts.hasNext())
            {
                Extension ext = exts.next();
                ext.setNextOutgoingFrames(outgoing);
                outgoing = ext;

                if (ext instanceof DemandChain)
                {
                    DemandChain demandingExtension = (DemandChain)ext;
                    demandingExtension.setNextDemand(demandChain::demand);
                    demandChain = demandingExtension;
                }
            }

            // Connect incomingFrames
            while (exts.hasPrevious())
            {
                Extension ext = exts.previous();
                ext.setNextIncomingFrames(incoming);
                incoming = ext;
            }
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        if (outgoing == null)
            throw new IllegalStateException();
        if (LOG.isDebugEnabled())
            LOG.debug("Extending out {} {} {}", frame, callback, batch);
        outgoing.sendFrame(frame, callback, batch);
    }

    public void initialize(IncomingFrames incoming, OutgoingFrames outgoing, WebSocketCoreSession coreSession)
    {
        if (extensions == null)
            throw new IllegalStateException();
        if (extensions.isEmpty())
        {
            this.incoming = incoming;
            this.outgoing = outgoing;
        }
        else
        {
            extensions.get(0).setNextOutgoingFrames(outgoing);
            extensions.get(extensions.size() - 1).setNextIncomingFrames(incoming);
        }

        for (Extension extension : extensions)
        {
            extension.setCoreSession(coreSession);
        }
    }

    public void demand(long n)
    {
        demandChain.demand(n);
    }

    public void setLastDemand(LongConsumer lastDemand)
    {
        this.lastDemand = lastDemand;
    }

    public Extension getRsv1User()
    {
        return rsvClaims[0];
    }

    public Extension getRsv2User()
    {
        return rsvClaims[1];
    }

    public Extension getRsv3User()
    {
        return rsvClaims[2];
    }

    public boolean isRsv1Used()
    {
        return (rsvClaims[0] != null);
    }

    public boolean isRsv2Used()
    {
        return (rsvClaims[1] != null);
    }

    public boolean isRsv3Used()
    {
        return (rsvClaims[2] != null);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, extensions == null ? Collections.emptyList() : extensions);
    }

    @Override
    public String dumpSelf()
    {
        return String.format("%s@%x[size=%d]", getClass().getSimpleName(), hashCode(), extensions.size());
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("ExtensionStack[extensions=");
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
        s.append(",incoming=").append((this.incoming == null) ? "<null>" : this.incoming.getClass().getName());
        s.append(",outgoing=").append((this.outgoing == null) ? "<null>" : this.outgoing.getClass().getName());
        s.append("]");
        return s.toString();
    }
}
