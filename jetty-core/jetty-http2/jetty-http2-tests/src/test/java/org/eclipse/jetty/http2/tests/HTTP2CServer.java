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

package org.eclipse.jetty.http2.tests;

import java.util.Date;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class HTTP2CServer extends Server
{
    public HTTP2CServer(int port)
    {
        HttpConfiguration config = new HttpConfiguration();
        // HTTP + HTTP/2 connector

        HttpConnectionFactory http1 = new HttpConnectionFactory(config);
        HTTP2CServerConnectionFactory http2c = new HTTP2CServerConnectionFactory(config);
        ServerConnector connector = new ServerConnector(this, 1, 1, http1, http2c);
        connector.setPort(port);
        addConnector(connector);

        ((QueuedThreadPool)getThreadPool()).setName("server");

        setHandler(new SimpleHandler());
    }

//    public static void main(String... args) throws Exception
//    {
//        HTTP2CServer server = new HTTP2CServer(8080);
//        server.start();
//    }

    private static class SimpleHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            Fields fields = Request.extractQueryParameters(request);

            String code = fields.getValue("code");
            if (code != null)
                response.setStatus(Integer.parseInt(code));

            response.getHeaders().put("Custom", "Value");
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            String content = "Hello from Jetty using " + request.getConnectionMetaData().getProtocol() + "\n";
            content += "uri=" + Request.getPathInContext(request) + "\n";
            content += "date=" + new Date() + "\n";
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length());
            Content.Sink.write(response, true, content, callback);
        }
    }
}
