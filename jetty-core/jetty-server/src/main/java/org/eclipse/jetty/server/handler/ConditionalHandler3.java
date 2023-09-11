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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;

public abstract class ConditionalHandler3 extends Handler.AbstractContainer implements Handler.Container
{
    private final Handler _success;
    private final Handler _fail;

    protected ConditionalHandler3(Handler success, Handler fail)
    {
        _success = Objects.requireNonNull(success);
        addBean(_success);
        _fail = Objects.requireNonNull(fail);
        addBean(_fail);
    }

    @Override
    public List<Handler> getHandlers()
    {
        if (_fail == null || _success instanceof Container container && container.getDescendants().contains(_fail))
            return Collections.singletonList(_success);
        return List.of(_success, _fail);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (canHandle(request))
            return _success.handle(request, response, callback);
        if (_fail != null)
            return _fail.handle(request, response, callback);
        return false;
    }

    protected abstract boolean canHandle(Request request);

    public static class Paths extends ConditionalHandler3
    {
        private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);

        public Paths(Handler success, Handler fail)
        {
            super(success, fail);
        }

        @Override
        protected boolean canHandle(Request request)
        {
            return _paths.test(Request.getPathInContext(request));
        }

        public IncludeExclude<String> getPaths()
        {
            return _paths;
        }
    }

    public static class Methods extends ConditionalHandler3
    {
        private final IncludeExclude<String> _methods = new IncludeExclude<>();

        public Methods(Handler success, Handler fail)
        {
            super(success, fail);
        }

        @Override
        protected boolean canHandle(Request request)
        {
            return _methods.test(request.getMethod());
        }

        public IncludeExclude<String> getMethods()
        {
            return _methods;
        }
    }

    public static void main(String... args)
    {
        Server server = new Server();
        GzipHandler gzip = new GzipHandler();
        ContextHandler context = new ContextHandler();

        ConditionalHandler3.Paths pathsGzipOrContext = new ConditionalHandler3.Paths(gzip, context);
        pathsGzipOrContext.getPaths().include("/foo/*");
        ConditionalHandler3.Methods methodPathsGzipOrContext = new ConditionalHandler3.Methods(pathsGzipOrContext, context);
        methodPathsGzipOrContext.getMethods().include("GET");

        server.setHandler(methodPathsGzipOrContext);
        gzip.setHandler(context);

        // ...
    }
}
