//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
        
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setSecurePort(9999);
        http.getHttpConfiguration().setSecureScheme("BWTP");
        _connector = new LocalConnector(_server,http);
        _connector.setIdleTimeout(300000);

        HttpConnectionFactory https = new HttpConnectionFactory();
        https.getHttpConfiguration().addCustomizer(new HttpConfiguration.Customizer()
        {
            @Override
            public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
            {
                request.setScheme(HttpScheme.HTTPS.asString());
                request.setSecure(true);
            }
        });
        
        _connectorS = new LocalConnector(_server,https);
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
            @Override
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
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));
        Assert.assertThat(response, Matchers.containsString("Location: BWTP://"));
        Assert.assertThat(response, Matchers.containsString(":9999"));

        response = _connectorS.getResponses("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

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
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));
        Assert.assertThat(response, Matchers.containsString("Location: BWTP://"));
        Assert.assertThat(response, Matchers.containsString(":9999"));

        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }

    @Test
    public void testConfidentialWithNoRolesSetAndNoMethodRestriction() throws Exception
    {
        Constraint constraint0 = new Constraint();
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

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));

        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }

    @Test
    public void testConfidentialWithNoRolesSetAndMethodRestriction() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setName("confid");
        constraint0.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/confid/*");
        mapping0.setMethod(HttpMethod.POST.asString());
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));

        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));

        response = _connectorS.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }
    @Test
    public void testConfidentialWithRolesSetAndMethodRestriction() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setRoles(new String[] { "admin" } );
        constraint0.setName("confid");
        constraint0.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/confid/*");
        mapping0.setMethod(HttpMethod.POST.asString());
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));

        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));

        response = _connectorS.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }

    @Test
    public void testConfidentialWithRolesSetAndMethodRestrictionAndAuthenticationRequired() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setRoles(new String[] { "admin" } );
        constraint0.setAuthenticate(true);
        constraint0.setName("confid");
        constraint0.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/confid/*");
        mapping0.setMethod(HttpMethod.POST.asString());
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));
        DefaultIdentityService identityService = new DefaultIdentityService();
        _security.setLoginService(new CustomLoginService(identityService));
        _security.setIdentityService(identityService);
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connectorS.getResponses("GET /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));

        response = _connectorS.getResponses("POST /ctx/confid/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 401 Unauthorized"));

        response = _connector.getResponses("GET /ctx/confid/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponses("POST /ctx/confid/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));

        response = _connectorS.getResponses("POST /ctx/confid/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }

    @Test
    public void testRestrictedWithoutAuthenticator() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setRoles(new String[] { "admin" } );
        constraint0.setName("restricted");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/restricted/*");
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

    }

    @Test
    public void testRestrictedWithoutAuthenticatorAndMethod() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setRoles(new String[] { "admin" } );
        constraint0.setName("restricted");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/restricted/*");
        mapping0.setMethod("GET");
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\r\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 403 Forbidden"));

    }

    @Test
    public void testRestricted() throws Exception
    {
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setRoles(new String[] { "admin" } );
        constraint0.setName("restricted");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/restricted/*");
        mapping0.setMethod("GET");
        mapping0.setConstraint(constraint0);

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                                                                            {
                mapping0
                                                                            }));
        DefaultIdentityService identityService = new DefaultIdentityService();
        _security.setLoginService(new CustomLoginService(identityService));
        _security.setIdentityService(identityService);
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 401 Unauthorized"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 401 Unauthorized"));

        response = _connector.getResponses("GET /ctx/restricted/info HTTP/1.0\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\n\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connectorS.getResponses("GET /ctx/restricted/info HTTP/1.0\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\n\n");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

    }

    private class CustomLoginService implements LoginService{
        private IdentityService identityService;

        public CustomLoginService(IdentityService identityService)
        {
            this.identityService = identityService;
        }
        
        @Override
        public String getName()
        {
            return "name";
        }

        @Override
        public UserIdentity login(String username, Object credentials, ServletRequest request)
        {
            if("admin".equals(username) && "password".equals(credentials))
                    return new DefaultUserIdentity(null,null,new String[] { "admin" } );
            return null;
        }

        @Override
        public boolean validate(UserIdentity user)
        {
            return false;
        }

        @Override
        public IdentityService getIdentityService()
        {
            return identityService;
        }

        @Override
        public void setIdentityService(IdentityService service)
        {
        }

        @Override
        public void logout(UserIdentity user)
        {
        }

    }

}
