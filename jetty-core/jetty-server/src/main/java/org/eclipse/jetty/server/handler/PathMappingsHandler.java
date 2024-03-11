//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.MatchedPath;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Handler that delegates to other handlers through a configured {@link PathMappings}.
 */
public class PathMappingsHandler extends Handler.AbstractContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(PathMappingsHandler.class);

    private final PathMappings<Handler> mappings = new PathMappings<>();

    public PathMappingsHandler()
    {
        this(true);
    }

    public PathMappingsHandler(boolean dynamic)
    {
        super(dynamic);
    }

    @Override
    public List<Handler> getHandlers()
    {
        return mappings.streamResources().map(MappedResource::getResource).toList();
    }

    public void addMapping(PathSpec pathSpec, Handler handler)
    {
        Objects.requireNonNull(pathSpec, "PathSpec cannot be null");
        Objects.requireNonNull(handler, "Handler cannot be null");

        if (!isDynamic() && isStarted())
            throw new IllegalStateException("Cannot add mapping: " + this);

        // Check that self isn't present.
        if (handler == this)
            throw new IllegalStateException("Unable to addHandler of self: " + handler);

        // Check for loops.
        if (handler instanceof Handler.Container container && container.getDescendants().contains(this))
            throw new IllegalStateException("loop detected: " + handler);

        Server server = getServer();
        if (server != null)
        {
            handler.setServer(server);

            // If the collection can be changed dynamically, then the risk is that if we switch from NON_BLOCKING to BLOCKING
            // whilst the execution strategy may have already dispatched the very last available thread, thinking it would
            // never block, only for it to lose the race and find a newly added BLOCKING handler.
            InvocationType serverInvocationType = server.getInvocationType();
            InvocationType invocationType = InvocationType.NON_BLOCKING;
            invocationType = Invocable.combine(invocationType, handler.getInvocationType());
            if (isDynamic() && server.isStarted() && serverInvocationType != invocationType && serverInvocationType != InvocationType.BLOCKING)
                throw new IllegalArgumentException("Cannot change invocation type of started server");
        }

        // Add new mapping and remove any old.
        Handler old = mappings.get(pathSpec);
        mappings.put(pathSpec, handler);
        updateBean(old, handler);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, mappings);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        String pathInContext = Request.getPathInContext(request);
        MatchedResource<Handler> matchedResource = mappings.getMatched(pathInContext);
        if (matchedResource == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No mappings matched {}", pathInContext);
            return false;
        }
        Handler handler = matchedResource.getResource();
        PathSpec pathSpec = matchedResource.getPathSpec();
        if (LOG.isDebugEnabled())
            LOG.debug("Matched {} to {} -> {}", pathInContext, matchedResource.getPathSpec(), handler);

        PathSpecRequest pathSpecRequest = new PathSpecRequest(request, pathSpec);
        boolean handled = handler.handle(pathSpecRequest, response, callback);
        if (LOG.isDebugEnabled())
            LOG.debug("Handled {} {} by {}", handled, pathInContext, handler);
        return handled;
    }

    private static class PathSpecRequest extends Request.Wrapper
    {
        private final PathSpec pathSpec;
        private final Context context;
        private final MatchedPath matchedPath;

        public PathSpecRequest(Request request, PathSpec pathSpec)
        {
            super(request);
            this.pathSpec = pathSpec;
            matchedPath = pathSpec.matched(request.getHttpURI().getCanonicalPath());
            setAttribute(PathSpec.class.getName(), this.pathSpec);
            this.context = new Context.Wrapper(request.getContext())
            {
                @Override
                public String getContextPath()
                {
                    return matchedPath.getPathMatch();
                }

                @Override
                public String getPathInContext(String canonicallyEncodedPath)
                {
                    return matchedPath.getPathInfo();
                }
            };
        }

        @Override
        public Context getContext()
        {
            return context;
        }
    }
}
