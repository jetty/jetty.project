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
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.component.Graceful;

public abstract class ConditionalHandler2 extends Handler.Wrapper implements Handler.Container, Graceful
{
    private final Handler _success;

    protected ConditionalHandler2(Handler handler)
    {
        _success = Objects.requireNonNull(handler);
        addBean(_success);
    }

    @Override
    protected void doStart() throws Exception
    {
        // Set the handler at the tail of the success chain to this handlers handler.
        Handler tail = _success;
        while (tail instanceof Handler.Singleton singleton && singleton.getHandler() != null)
            tail = singleton.getHandler();
        if (tail instanceof Handler.Singleton singleton)
            singleton.setHandler(getHandler());

        super.doStart();
    }

    @Override
    public List<Handler> getHandlers()
    {
        if (getHandler() == null)
            return Collections.singletonList(_success);
        return List.of(_success, getHandler());
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        if (_success instanceof Graceful graceful)
            return graceful.shutdown();
        return null;
    }

    @Override
    public boolean isShutdown()
    {
        if (_success instanceof Graceful graceful)
            return graceful.isShutdown();
        return !isRunning();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (canHandle(request))
            return _success.handle(request, response, callback);

        if (_success instanceof Wrapper wrapper && wrapper.getHandler() != null)
            return wrapper.getHandler().handle(request, response, callback);
        return false;
    }

    protected abstract boolean canHandle(Request request);

    public static class Paths extends ConditionalHandler2
    {
        private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);

        public Paths(Handler handler)
        {
            super(handler);
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

    public static class Methods extends ConditionalHandler2
    {
        private final IncludeExclude<String> _methods = new IncludeExclude<>();

        public Methods(Handler handler)
        {
            super(handler);
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

        ConditionalHandler2.Paths pathsGzip = new ConditionalHandler2.Paths(gzip);
        pathsGzip.getPaths().include("/foo/*");
        ConditionalHandler2.Methods methodsPathGzip = new ConditionalHandler2.Methods(pathsGzip);
        methodsPathGzip.getMethods().include("GET");

        server.setHandler(methodsPathGzip);
        methodsPathGzip.setHandler(new ContextHandler());

        // ...
    }

}
