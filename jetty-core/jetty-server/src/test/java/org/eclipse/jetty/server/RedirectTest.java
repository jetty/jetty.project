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

package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RedirectTest
{
    private Server server;
    private LocalConnector localConnector;

    public Server startServer(HttpConfiguration httpConfiguration, Handler handler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server, new HttpConnectionFactory(httpConfiguration));
        server.addConnector(localConnector);

        server.setHandler(handler);
        server.start();
        return server;
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testSendRedirectWithFragment() throws Exception
    {
        final int redirectCode = HttpStatus.MOVED_TEMPORARILY_302;
        final String redirectLocation = "http://local/path/to/resource#fragment";

        Handler.Abstract handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, redirectCode, redirectLocation, false);
                return true;
            }
        };

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        startServer(httpConfiguration, handler);

        String rawRequest = """
            GET /test HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(redirectCode, response.getStatus());
        assertEquals(redirectLocation, response.get("Location"));
    }
}
