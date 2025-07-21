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

package org.eclipse.jetty.rewrite.handler;

import java.util.List;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteHandlerMissingChildTest
{
    private final Server _server = new Server();
    private final LocalConnector _connector =
            new LocalConnector(_server, new HttpConnectionFactory(new HttpConfiguration()));
    private final RewriteHandler _rewriteHandler = new RewriteHandler();

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testChildHandlerIsNullThrowsException() throws Exception
    {
        _rewriteHandler.setRules(List.of(new RewriteRegexRule("/xxx/(.*)", "/$1/zzz")));
        _rewriteHandler.setOriginalPathAttribute("originalPath");

        _server.addConnector(_connector);
        _server.setHandler(_rewriteHandler);
        _server.start();

        String request = """
            GET /xxx/bar HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertEquals(500, response.getStatus(), "Expected HTTP 500 error from RewriteHandler");
        assertEquals("Server Error", response.getReason());

    }
}