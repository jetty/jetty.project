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

package org.eclipse.jetty.websocket.core.server;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

public class WebSocketUpgradeHandler extends Handler.Wrapper
{
    private final WebSocketMappings mappings;
    private final Configuration.ConfigurationCustomizer customizer = new Configuration.ConfigurationCustomizer();

    public WebSocketUpgradeHandler()
    {
        this(new WebSocketComponents());
    }

    public WebSocketUpgradeHandler(WebSocketComponents components)
    {
        this.mappings = new WebSocketMappings(components);
        setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
            }
        });
    }

    public void addMapping(String pathSpec, WebSocketNegotiator negotiator)
    {
        mappings.addMapping(new ServletPathSpec(pathSpec), negotiator);
    }

    public void addMapping(PathSpec pathSpec, WebSocketNegotiator negotiator)
    {
        mappings.addMapping(pathSpec, negotiator);
    }

    public Configuration getConfiguration()
    {
        return customizer;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Request.Processor processor = super.handle(request);
        if (processor == null)
            return null;

        String target = request.getPathInContext();
        WebSocketNegotiator negotiator = mappings.getMatchedNegotiator(target, pathSpec ->
        {
            // Store PathSpec resource mapping as request attribute,
            // for WebSocketCreator implementors to use later if they wish.
            request.setAttribute(PathSpec.class.getName(), pathSpec);
        });

        if (negotiator == null)
            return processor;
        return new WebSocketProcessor(processor, negotiator);
    }

    private class WebSocketProcessor implements Request.Processor
    {
        private final Request.Processor _processor;
        private final WebSocketNegotiator _negotiator;

        public WebSocketProcessor(Request.Processor processor, WebSocketNegotiator negotiator)
        {
            _processor = processor;
            _negotiator = negotiator;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            try
            {
                if (mappings.upgrade(_negotiator, request, response, callback, customizer))
                    return;

                _processor.process(request, response, callback);
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }
}
