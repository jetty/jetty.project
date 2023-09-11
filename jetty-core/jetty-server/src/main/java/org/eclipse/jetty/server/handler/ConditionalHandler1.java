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

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;

public abstract class ConditionalHandler1 extends Handler.Wrapper
{
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (canHandle(request))
            return super.handle(request, response, callback);

        // Skip any composed conditional handlers
        Handler handler = getHandler();
        while (handler instanceof ConditionalHandler1 conditional)
            handler = conditional.getHandler();

        //  Skip the first non conditional singleton
        if (handler instanceof Singleton singleton && singleton.getHandler() != null)
            return singleton.getHandler().handle(request, response, callback);
        return false;
    }

    protected abstract boolean canHandle(Request request);

    public static class Paths extends ConditionalHandler1
    {
        private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);

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

    public static class Methods extends ConditionalHandler1
    {
        private final IncludeExclude<String> _methods = new IncludeExclude<>();

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

        ConditionalHandler1.Paths paths = new ConditionalHandler1.Paths();
        paths.getPaths().include("/foo/*");
        ConditionalHandler1.Methods methods = new ConditionalHandler1.Methods();
        methods.getMethods().include("GET");
        GzipHandler gzip = new GzipHandler();

        server.setHandler(paths);
        paths.setHandler(methods);
        methods.setHandler(gzip);
        gzip.setHandler(new ContextHandler());

        // ...
    }

}
