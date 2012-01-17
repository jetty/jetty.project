// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.security;

import static org.junit.Assert.assertThat;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision: 1441 $ $Date: 2010-04-02 12:28:17 +0200 (Fri, 02 Apr 2010) $
 */
public class DataConstraintsTest
{
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SessionHandler _session;
    private ConstraintSecurityHandler _security;

    @Before
    public  void startServer()
    {
        _server = new Server();
        _connector = new LocalConnector();
        _connector.setIntegralPort(9998);
        _connector.setIntegralScheme("FTP");
        _connector.setConfidentialPort(9999);
        _connector.setConfidentialScheme("SPDY");
        _connectorS = new LocalConnector()
        {
            @Override
            public void customize(EndPoint endpoint, Request request) throws IOException
            {
                super.customize(endpoint,request);
                request.setScheme(HttpSchemes.HTTPS);
            }

            @Override
            public boolean isIntegral(Request request)
            {
                return true;
            }

            @Override
            public boolean isConfidential(Request request)
            {
                return true;
            }
        };
        _server.setConnectors(new Connector[]{_connector,_connectorS});

        ContextHandler _context = new ContextHandler();
        _session = new SessionHandler();

        _context.setContextPath("/ctx");
        _server.setHandler(_context);
        _context.setHandler(_session);

        _security = new ConstraintSecurityHandler();
        _session.setHandler(_security);
        
        _security.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.sendError(404);
            }
        });
        
    }

    @After
    public void stopServer() throws Exception
    {
        if (_server.isRunning())
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testIntegral() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(false);
        constraint0.setName("integral");
        constraint0.setDataConstraint(Constraint.DC_INTEGRAL);
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/integral/*");
        mapping0.setConstraint(constraint0);
        
        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                {
                        mapping0
                }));
    
        _server.start();
        
        String response;
        response = _connector.getResponses("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: FTP://"));
        assertThat(response, containsString(":9998"));
        
        response = _connectorS.getResponses("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 404 Not Found"));

    }
    
    @Test
    public void testConfidential() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(false);
        constraint0.setName("confid");
        constraint0.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/confid/*");
        mapping0.setConstraint(constraint0);
        
        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                {
                        mapping0
                }));
    
        _server.start();
        
        String response;
        response = _connector.getResponses("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: SPDY://"));
        assertThat(response, containsString(":9999"));
        
        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 404 Not Found"));

    }

}
