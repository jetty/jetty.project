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

package org.eclipse.jetty.security;

import java.util.List;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.security.Constraint.Transport;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class SecurityHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SecurityHandler.PathMapped _securityHandler;

    @BeforeEach
    public void configureServer() throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        HttpConfiguration httpConfiguration = http.getHttpConfiguration();
        httpConfiguration.setSecurePort(9999);
        httpConfiguration.setSecureScheme("BWTP");
        httpConfiguration.addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _connector.setIdleTimeout(300000);

        HttpConnectionFactory https = new HttpConnectionFactory();
        https.getHttpConfiguration().addCustomizer((request, responseHeaders) ->
        {
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI()).scheme(HttpScheme.HTTPS);
            return new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return uri;
                }

                @Override
                public boolean isSecure()
                {
                    return true;
                }
            };
        });

        _connectorS = new LocalConnector(_server, https);
        _server.setConnectors(new Connector[]{_connector, _connectorS});

        ContextHandler contextHandler = new ContextHandler("/ctx");
        _server.setHandler(contextHandler);
        _securityHandler = new SecurityHandler.PathMapped();
        contextHandler.setHandler(_securityHandler);
        _securityHandler.setHandler(new AuthenticationTestHandler());
        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (_server.isRunning())
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testNoConstraints() throws Exception
    {
        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));
    }

    @Test
    public void testForbidden() throws Exception
    {
        _securityHandler.put("/secret/*", Constraint.FORBIDDEN);

        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));

        response = _connector.getResponse("GET /ctx/secret/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, not(containsString("OK")));
    }

    @Test
    public void testUserData() throws Exception
    {
        _securityHandler.put("/confidential/*", Constraint.SECURE_TRANSPORT);

        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));

        response = _connector.getResponse("GET /ctx/confidential/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: BWTP://"));
        assertThat(response, containsString(":9999"));
        assertThat(response, not(containsString("OK")));

        response = _connectorS.getResponse("GET /ctx/confidential/info HTTP/1.0\r\nForwarded: proto=https\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));
    }

    @Test
    public void testCombinedForbiddenConfidential() throws Exception
    {
        _securityHandler.put("/*", Constraint.ALLOWED);
        _securityHandler.put("/confidential/*", Constraint.SECURE_TRANSPORT);
        _securityHandler.put("*.hidden", Constraint.FORBIDDEN);

        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));

        response = _connector.getResponse("GET /ctx/something.hidden HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/confidential/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: BWTP://"));
        assertThat(response, containsString(":9999"));
        assertThat(response, not(containsString("OK")));

        response = _connectorS.getResponse("GET /ctx/confidential/info HTTP/1.0\r\nForwarded: proto=https\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Unauthenticated"));

        response = _connectorS.getResponse("GET /ctx/confidential/info.hidden HTTP/1.0\r\nForwarded: proto=https\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, not(containsString("OK")));
    }

    @Test
    public void testPathMatchesPrecedence()
    {
        PathMappings<Constraint> p = new PathMappings<>();
        p.put("/foo/*", Constraint.from("foo", Transport.ANY, null, null));
        p.put("*.txt", Constraint.from("txt", Transport.ANY, null, null));
        p.put("/foo/bar/bob/*", Constraint.from("foobarbob", Transport.ANY, null, null));
        p.put("*.thing.txt", Constraint.from("thingtxt", Transport.ANY, null, null));
        p.put("/", Constraint.from("default", Transport.ANY, null, null));
        p.put("/*", Constraint.from("everything", Transport.ANY, null, null));
        p.put("", Constraint.from("root", Transport.ANY, null, null));
        p.put("/foo/bar/bob/some.thing.txt", Constraint.from("exact", Transport.ANY, null, null));
        p.put("/foo/bar/*", Constraint.from("foobar", Transport.ANY, null, null));

        List<MappedResource<Constraint>> matches = p.getMatches("/foo/bar/bob/some.thing.txt");
        matches.sort(new SecurityHandler.PathMapped()::compare);

        List<String> names = matches.stream()
            .map(MappedResource::getResource)
            .map(Constraint::getName)
            .toList();

        assertThat(names, contains(
            "default",
            "everything",
            "foo",
            "foobar",
            "foobarbob",
            "txt",
            "thingtxt",
            "exact"
        ));

    }
}
