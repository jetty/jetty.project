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

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;

/**
 *
 */
public class Constrain2tTest extends TestCase
{
    private static final String TEST_REALM = "TestRealm";

    Server _server = new Server();
    LocalConnector _connector = new LocalConnector();
    ContextHandler _context = new ContextHandler();
    SessionHandler _session = new SessionHandler();
    ConstraintSecurityHandler _security = new ConstraintSecurityHandler();
    HashLoginService _loginService = new HashLoginService(TEST_REALM);

    RequestHandler _handler = new RequestHandler();

    {
        _server.setConnectors(new Connector[]{_connector});
        _context.setContextPath("/ctx");
        _server.setHandler(_context);
        _context.setHandler(_session);
        _session.setHandler(_security);
        _security.setHandler(_handler);

        _loginService.putUser("user",new Password("password"));
        _loginService.putUser("user2",new Password("password"), new String[] {"user"});
        _loginService.putUser("admin",new Password("password"), new String[] {"user","administrator"});
        _server.addBean(_loginService);
    }

    public Constrain2tTest(String arg0)
    {
        super(arg0);
        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setName("auth");
        constraint0.setRoles(new String[]{Constraint.ANY_ROLE});
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/auth.html");
        mapping0.setConstraint(constraint0);

        Set<String> knownRoles=new HashSet<String>();
        knownRoles.add("user");
        knownRoles.add("administrator");

        _security.setConstraintMappings(new ConstraintMapping[]
                {
                        mapping0
                },knownRoles);
    }

    /*
     * @see TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        super.setUp();

    }

    /*
     * @see TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        super.tearDown();
        _server.stop();
    }


    public void testRootFormDispatch()
            throws Exception
    {
        _context.setContextPath("/");
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",true));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /noauth.html HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /auth.html HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf("Cache-Control: no-cache") > 0);
        assertTrue(response.indexOf("Expires") > 0);
        assertTrue(response.indexOf("URI=/testLoginPage") > 0);

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/"));

        response = _connector.getResponses("POST /j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("testErrorPage") > 0);

        response = _connector.getResponses("POST /j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/auth.html") > 0);

        response = _connector.getResponses("GET /auth.html HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }


    public void testRootFormRedirect()
            throws Exception
    {
        _context.setContextPath("/");
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",false));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /noauth.html HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /auth.html HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf(" 302 Found") > 0);
        assertTrue(response.indexOf("/testLoginPage") > 0);

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/"));

        response = _connector.getResponses("POST /j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("Location") > 0);

        response = _connector.getResponses("POST /j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/auth.html") > 0);

        response = _connector.getResponses("GET /auth.html HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }


    class RequestHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response ) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            if (request.getAuthType()==null || "user".equals(request.getRemoteUser()) || request.isUserInRole("user"))
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().println("URI="+request.getRequestURI());
                String user = request.getRemoteUser();
                response.getWriter().println("user="+user);
            }
            else
                response.sendError(500);
        }
    }

}
