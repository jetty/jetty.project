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

import java.net.InetSocketAddress;

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;

public class ConditionalHandler0 extends Handler.Wrapper
{
    private final boolean _skip;
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<InetAccessSet.PatternTuple, InetAccessSet.AccessTuple> _inet = new IncludeExcludeSet<>(InetAccessSet.class);

    public ConditionalHandler0()
    {
        this(false);
    }

    public ConditionalHandler0(boolean skip)
    {
        _skip = skip;
    }

    public IncludeExclude<String> getPaths()
    {
        return _paths;
    }

    public IncludeExclude<String> getMethods()
    {
        return _methods;
    }

    public IncludeExcludeSet<InetAccessSet.PatternTuple, InetAccessSet.AccessTuple> getInet()
    {
        return _inet;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        String pathInContext = Request.getPathInContext(request);
        if (_methods.test(request.getMethod()) &&
            !_paths.isEmpty() && _paths.test(pathInContext) &&
            !_inet.isEmpty() && request.getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress inet &&
            _inet.test(new InetAccessSet.AccessTuple(request.getConnectionMetaData().getConnector().getName(), inet.getAddress(), pathInContext)))
            return super.handle(request, response, callback);

        if (_skip && getHandler() instanceof Singleton wrapper && wrapper.getHandler() != null)
            return wrapper.getHandler().handle(request, response, callback);

        return false;
    }

    public static void main(String... args)
    {
        Server server = new Server();
        ConditionalHandler0 conditional = new ConditionalHandler0(true);
        conditional.getPaths().include("/foo/*");
        conditional.getMethods().include("GET");
        conditional.getInet().include(InetAccessSet.PatternTuple.from("connector1@127.0.0.1|/foo"));

        GzipHandler gzip = new GzipHandler();

        server.setHandler(conditional);
        conditional.setHandler(gzip);
        gzip.setHandler(new ContextHandler());

        // ...
    }

}
