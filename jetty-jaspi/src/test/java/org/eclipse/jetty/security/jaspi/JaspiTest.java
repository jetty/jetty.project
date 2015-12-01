//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JaspiTest
{
    Server _server;
    LocalConnector _connector;
    public class TestLoginService extends AbstractLoginService
    {
        protected Map<String, UserPrincipal> _users = new HashMap<>();
        protected Map<String, String[]> _roles = new HashMap();
     
      

        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser (String username, Credential credential, String[] roles)
        {
            UserPrincipal userPrincipal = new UserPrincipal(username,credential);
            _users.put(username, userPrincipal);
            _roles.put(username, roles);
        }
        
        /** 
         * @see org.eclipse.jetty.security.AbstractLoginService#loadRoleInfo(org.eclipse.jetty.security.AbstractLoginService.UserPrincipal)
         */
        @Override
        protected String[] loadRoleInfo(UserPrincipal user)
        {
           return _roles.get(user.getName());
        }

        /** 
         * @see org.eclipse.jetty.security.AbstractLoginService#loadUserInfo(java.lang.String)
         */
        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return _users.get(username);
        }
    }
    
    @Before
    public void before() throws Exception
    {
        System.setProperty("org.apache.geronimo.jaspic.configurationFile","src/test/resources/jaspi.xml");
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);
        
        TestLoginService loginService = new TestLoginService("TestRealm");
        loginService.putUser("user",new Password("password"),new String[]{"users"});
        loginService.putUser("admin",new Password("secret"),new String[]{"users","admins"});
        _server.addBean(loginService);
        
        ContextHandler context = new ContextHandler();
        contexts.addHandler(context);
        context.setContextPath("/ctx");
        
        JaspiAuthenticatorFactory jaspiAuthFactory = new JaspiAuthenticatorFactory();
        
        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setHandler(security);
        security.setAuthenticatorFactory(jaspiAuthFactory);
        // security.setAuthenticator(new BasicAuthenticator());
       
        Constraint constraint = new Constraint("All","users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/jaspi/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);
        
        TestHandler handler = new TestHandler();
        security.setHandler(handler);
        
        ContextHandler other = new ContextHandler();
        contexts.addHandler(other);
        other.setContextPath("/other");
        ConstraintSecurityHandler securityOther = new ConstraintSecurityHandler();
        other.setHandler(securityOther);
        securityOther.setAuthenticatorFactory(jaspiAuthFactory);
        securityOther.addConstraintMapping(mapping);        
        securityOther.setHandler(new TestHandler());
        
        _server.start();
    }
    
    @After
    public void after() throws Exception
    {
        _server.stop();
    }
    
    @Test
    public void testNoConstraint() throws Exception
    {
        String response = _connector.getResponses("GET /ctx/test HTTP/1.0\n\n");
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
    }
    
    @Test
    public void testConstraintNoAuth() throws Exception
    {
        String response = _connector.getResponses("GET /ctx/jaspi/test HTTP/1.0\n\n");
        assertThat(response,startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response,Matchers.containsString("WWW-Authenticate: basic realm=\"TestRealm\""));
    }
    
    @Test
    public void testConstraintWrongAuth() throws Exception
    {
        String response = _connector.getResponses("GET /ctx/jaspi/test HTTP/1.0\n"+
                                                  "Authorization: Basic " + B64Code.encode("user:wrong") + "\n\n");
        assertThat(response,startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response,Matchers.containsString("WWW-Authenticate: basic realm=\"TestRealm\""));
    }
    
    @Test
    public void testConstraintAuth() throws Exception
    {
        String response = _connector.getResponses("GET /ctx/jaspi/test HTTP/1.0\n"+
                                                  "Authorization: Basic " + B64Code.encode("user:password") + "\n\n");
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
    }
    
    @Test
    public void testOtherNoAuth() throws Exception
    {
        String response = _connector.getResponses("GET /other/test HTTP/1.0\n\n");
        assertThat(response,startsWith("HTTP/1.1 403 Forbidden"));
    }
    
    @Test
    public void testOtherAuth() throws Exception
    {
        String response = _connector.getResponses("GET /other/test HTTP/1.0\n"+
                                                  "X-Forwarded-User: user\n\n");
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
    }
    
    public class TestHandler extends AbstractHandler
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().println("All OK");
            response.getWriter().println("requestURI="+request.getRequestURI());
        }
    }
}
