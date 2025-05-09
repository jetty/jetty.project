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

package org.eclipse.jetty.test.rfc;

import java.util.List;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.rewrite.handler.RedirectRegexRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConditionalHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test only the differences between RFC2616 and RFC7230
 * <a href="https://datatracker.ietf.org/doc/html/rfc7230#appendix-A.2">Appendix 2</a>
 */
public class RFC7230Test
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        HttpConfiguration httpConfiguration = connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration();
        httpConfiguration.setHttpCompliance(HttpCompliance.RFC7230);
        httpConfiguration.setSendDateHeader(true);
        connector.setIdleTimeout(10000);
        server.addConnector(connector);

        ConditionalHandler options = new ConditionalHandler.ElseNext()
        {
            final DefaultHandler optionsHandler = new DefaultHandler();
            {
                installBean(optionsHandler);
            }

            @Override
            public void setServer(Server server)
            {
                super.setServer(server);
                optionsHandler.setServer(server);
            }

            @Override
            protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
            {
                return optionsHandler.handle(request, response, callback);
            }
        };
        options.includeMethod("OPTIONS");

        server.setHandler(options);

        RewriteHandler rewrite = new RewriteHandler();
        rewrite.addRule(new RedirectRegexRule("/redirect/(.*)", "/tests/$1"));
        options.setHandler(rewrite);

        PathMappingsHandler pathMappings = new PathMappingsHandler.NoContext();
        rewrite.setHandler(pathMappings);

        pathMappings.addMapping(PathSpec.from("/echo/*"), new EchoHandler());

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setAcceptRanges(true);
        ContextHandler staticContext = new ContextHandler(resourceHandler, "/static");
        staticContext.setBaseResource(ResourceFactory.of(server).newClassLoaderResource("/static/"));
        pathMappings.addMapping(PathSpec.from("/static/*"), staticContext);

        ContextHandler vcontext = new ContextHandler();
        vcontext.setContextPath("/");
        vcontext.setVirtualHosts(List.of("VirtualHost"));
        vcontext.setHandler(new DumpHandler("Virtual Dump"));

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(new DumpHandler());

        pathMappings.addMapping(PathSpec.from("/"), new Handler.Sequence(vcontext, context));

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testNoUserInfo_2_7_1() throws Exception
    {
        String req = """
            GET http://username:password@host:8888/path HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req));

        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testInvalidWhiteSpaceInField_3_2() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET http://localhost/path HTTP/1.1
            Host: localhost
            Bad : whitespace
            Connection: close
            
            """));

        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    @Disabled // TODO
    public void testInvalidWhiteSpaceInTE_3_2() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET http://localhost/path HTTP/1.1
            Host: localhost
            Transfer-Encoding: identity;bad = space,chunked
            
            0;
            
            """));

        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testNoFolding_3_2_4() throws Exception
    {
        String req = """
            GET http://localhost/path HTTP/1.1
            Host: localhost
            Folded: value
             over
             many
             lines
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req));

        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }
}
