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

package org.eclipse.jetty.ee10.demos;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class HelloHandler extends Handler.Abstract
{
    final String greeting;
    final String body;

    public HelloHandler()
    {
        this("Hello World");
    }

    public HelloHandler(String greeting)
    {
        this(greeting, null);
    }

    public HelloHandler(String greeting, String body)
    {
        this.greeting = greeting;
        this.body = body == null ? "" : body;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);

        Content.Sink.write(response, true, "<h1>" + greeting + "</h1>\n" + body, callback);
        return true;
    }
}
