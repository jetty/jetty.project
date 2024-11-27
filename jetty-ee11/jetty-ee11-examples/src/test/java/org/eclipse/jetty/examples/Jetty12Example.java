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

package org.eclipse.jetty.examples;

import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;

public class Jetty12Example
{
    public static void main(String[] args) throws Exception
    {
        org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server();

        org.eclipse.jetty.server.ServerConnector connector = new org.eclipse.jetty.server.ServerConnector(server);
        server.addConnector(connector);

        // Declare server handler collection
        org.eclipse.jetty.server.handler.ContextHandlerCollection contexts =
                new org.eclipse.jetty.server.handler.ContextHandlerCollection();
        server.setHandler(contexts);

        // Add an embedded jetty-12 context
        org.eclipse.jetty.server.handler.ContextHandler embedded =
                new org.eclipse.jetty.server.handler.ContextHandler("/embedded");
        embedded.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request,
                                  org.eclipse.jetty.server.Response response,
                                  Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                Content.Sink.write(response, true, "the handler says Hello World", callback);
                return true;
            }
        });
        contexts.addHandler(embedded);

        // Add an EE11 ServletContext
        URLClassLoader ee11Loader = new URLClassLoader(
                new URL[] {new URL("file:lib/ee11/servlet-api-6.0.jar"),
                        new URL("file:lib/ee11/jetty-ee11-servlet.jar")},
                Jetty12Example.class.getClassLoader());
        org.eclipse.jetty.server.handler.ContextHandler ee11Context = (org.eclipse.jetty.server.handler.ContextHandler)
                ee11Loader.loadClass("org.eclipse.jetty.ee11.servlet.ServletContextHandler")
                        .getDeclaredConstructor().newInstance();
        org.eclipse.jetty.server.Handler ee11Servlet = (org.eclipse.jetty.server.Handler)
                ee11Loader.loadClass("org.eclipse.jetty.ee11.servlet.ServletHandler")
                        .getDeclaredConstructor().newInstance();
        ee11Context.setHandler(ee11Servlet);
        contexts.addHandler(ee11Context);
        ee11Servlet.getClass().getMethod("addServletWithMapping", String.class, String.class)
                .invoke(ee11Servlet, "org.acme.MyServlet", "/");


        server.start();
        server.dumpStdErr();
        server.join();
    }
}
