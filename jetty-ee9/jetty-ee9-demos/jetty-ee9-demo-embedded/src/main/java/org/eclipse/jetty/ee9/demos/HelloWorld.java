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

package org.eclipse.jetty.ee9.demos;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HelloWorld extends Handler.Abstract
{
    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        return (req, response, callback) ->
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.write(true, BufferUtil.toBuffer("<h1>Hello World</h1>"), callback);
        };
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = new Server(port);
        server.setHandler(new HelloWorld());

        server.start();
        server.join();
    }
}
