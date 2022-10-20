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

import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Handler that delegates to other handlers through a configured {@link PathMappings}.
 */
public class PathMappingsHandler extends Handler.Wrapper
{
    private final static Logger LOG = LoggerFactory.getLogger(PathMappingsHandler.class);
    private final PathMappings<Handler> mappings = new PathMappings<>();

    public void addMapping(PathSpec pathSpec, Handler handler)
    {
        if (isStarted())
            throw new IllegalStateException("Cannot add mapping: " + this);

        mappings.put(pathSpec, handler);
    }

    @Override
    protected void doStart() throws Exception
    {
        mappings.getMappings().forEach((mapped) -> addBean(mapped.getResource()));
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        mappings.getMappings().forEach((mapped) -> removeBean(mapped.getResource()));
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        String pathInContext = request.getPathInContext();
        MatchedResource<Handler> matchedResource = mappings.getMatched(pathInContext);
        if (matchedResource == null)
        {
            if  (LOG.isDebugEnabled())
                LOG.debug("No match on pathInContext of {}", pathInContext);
            return super.handle(request);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Matched pathInContext of {} to {} -> {}", pathInContext, matchedResource.getPathSpec(), matchedResource.getResource());
        return matchedResource.getResource().handle(request);
    }
}
