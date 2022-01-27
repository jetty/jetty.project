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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;

public abstract class AbstractRuleTestCase
{
    protected Server _server = new Server();
    protected LocalConnector _connector;
    protected volatile Request _request;
    protected volatile Response _response;
    protected volatile CountDownLatch _latch;
    protected boolean _isSecure = false;

    @AfterEach
    public void stopServer() throws Exception
    {
        stop();
    }

    protected void start(final boolean isSecure) throws Exception
    {
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addCustomizer(new HttpConfiguration.Customizer()
        {
            @Override
            public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
            {
                request.setSecure(isSecure);
            }
        });
        _server.setConnectors(new Connector[]{_connector});

        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                _request = baseRequest;
                _response = _request.getResponse();
                try
                {
                    _latch.await();
                }
                catch (InterruptedException e)
                {
                    throw new ServletException(e);
                }
            }
        });

        _server.start();

        _latch = new CountDownLatch(1);
        _connector.executeRequest("GET / HTTP/1.0\nCookie: set=already\n\n");

        while (_response == null)
        {
            Thread.sleep(1);
        }
    }

    protected void reset()
    {
        if (_latch != null)
            _latch.countDown();
        _request = null;
        _response = null;
        _latch = new CountDownLatch(1);
        _connector.executeRequest("GET / HTTP/1.0\nCookie: set=already\n\n");

        while (_response == null)
        {
            Thread.yield();
        }
    }

    protected void stop() throws Exception
    {
        _latch.countDown();
        _server.stop();
        _server.join();
        _request = null;
        _response = null;
    }
}
