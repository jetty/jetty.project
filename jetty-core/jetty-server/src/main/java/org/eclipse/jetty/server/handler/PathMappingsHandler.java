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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Handler that delegates to other handlers through a configured {@link PathMappings}.
 */

public class PathMappingsHandler extends Handler.AbstractContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(PathMappingsHandler.class);

    private final PathMappings<Handler> mappings = new PathMappings<>();

    @Override
    public void addHandler(Handler handler)
    {
        throw new UnsupportedOperationException("Arbitrary addHandler() not supported, use addMapping() instead");
    }

    @Override
    public void addHandler(Supplier<Handler> supplier)
    {
        throw new UnsupportedOperationException("Arbitrary addHandler() not supported, use addMapping() instead");
    }

    @Override
    public List<Handler> getHandlers()
    {
        return mappings.streamResources().map(MappedResource::getResource).toList();
    }

    public void addMapping(PathSpec pathSpec, Handler handler)
    {
        if (isStarted())
            throw new IllegalStateException("Cannot add mapping: " + this);

        // check that self isn't present
        if (handler == this || handler instanceof Handler.Container container && container.getDescendants().contains(this))
            throw new IllegalStateException("Unable to addHandler of self: " + handler);

        // check existing mappings
        for (MappedResource<Handler> entry : mappings)
        {
            Handler entryHandler = entry.getResource();

            if (entryHandler == this ||
                entryHandler == handler ||
                (entryHandler instanceof Handler.Container container && container.getDescendants().contains(this)))
                throw new IllegalStateException("addMapping loop detected: " + handler);
        }

        mappings.put(pathSpec, handler);
        addBean(handler);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, mappings);
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        String pathInContext = Request.getPathInContext(request);
        MatchedResource<Handler> matchedResource = mappings.getMatched(pathInContext);
        if (matchedResource == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No match on pathInContext of {}", pathInContext);
            return null;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Matched pathInContext of {} to {} -> {}", pathInContext, matchedResource.getPathSpec(), matchedResource.getResource());
        return matchedResource.getResource().handle(request);
    }
}
