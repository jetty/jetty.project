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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision: 1441 $ $Date: 2010-04-02 12:28:17 +0200 (Fri, 02 Apr 2010) $
 */
public class ConstraintTest
{
    private static final String TEST_REALM = "TestRealm";
    private static Server _server;
    private static LocalConnector _connector;
    private static SessionHandler _session;
    private ConstraintSecurityHandler _security;

    @BeforeClass
    public static void startServer()
    {
        _server = new Server();
        _connector = new LocalConnector();
        _server.setConnectors(new Connector[]{_connector});

        ContextHandler _context = new ContextHandler();
        _session = new SessionHandler();

        HashLoginService _loginService = new HashLoginService(TEST_REALM);
        _loginService.putUser("user",new Password("password"));
        _loginService.putUser("user2",new Password("password"), new String[] {"user"});
        _loginService.putUser("admin",new Password("password"), new String[] {"user","administrator"});

        _context.setContextPath("/ctx");
        _server.setHandler(_context);
        _context.setHandler(_session);

        _server.addBean(_loginService);
    }

    @Before
    public void setupSecurity()
    {
        _security = new ConstraintSecurityHandler();
        _session.setHandler(_security);
        RequestHandler _handler = new RequestHandler();
        _security.setHandler(_handler);

        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setName("forbid");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/forbid/*");
        mapping0.setConstraint(constraint0);

        Constraint constraint1 = new Constraint();
        constraint1.setAuthenticate(true);
        constraint1.setName("auth");
        constraint1.setRoles(new String[]{Constraint.ANY_ROLE});
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/auth/*");
        mapping1.setConstraint(constraint1);

        Constraint constraint2 = new Constraint();
        constraint2.setAuthenticate(true);
        constraint2.setName("admin");
        constraint2.setRoles(new String[]{"administrator"});
        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/admin/*");
        mapping2.setConstraint(constraint2);
        mapping2.setMethod("GET");

        Constraint constraint3 = new Constraint();
        constraint3.setAuthenticate(false);
        constraint3.setName("relax");
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/admin/relax/*");
        mapping3.setConstraint(constraint3);

        Constraint constraint4 = new Constraint();
        constraint4.setAuthenticate(true);
        constraint4.setName("loginpage");
        constraint4.setRoles(new String[]{"administrator"});
        ConstraintMapping mapping4 = new ConstraintMapping();
        mapping4.setPathSpec("/testLoginPage");
        mapping4.setConstraint(constraint4);

        Constraint constraint5 = new Constraint();
        constraint5.setAuthenticate(false);
        constraint5.setName("allow forbidden POST");
        ConstraintMapping mapping5 = new ConstraintMapping();
        mapping5.setPathSpec("/forbid/post");
        mapping5.setConstraint(constraint5);
        mapping5.setMethod("POST");
        
        
        Set<String> knownRoles=new HashSet<String>();
        knownRoles.add("user");
        knownRoles.add("administrator");

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
                {
                        mapping0, mapping1, mapping2, mapping3, mapping4, mapping5
                }), knownRoles);
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
    public void testConstraints() throws Exception
    {
        ConstraintMapping[] mappings =_security.getConstraintMappings().toArray(new ConstraintMapping[0]);

        assertTrue (mappings[0].getConstraint().isForbidden());
        assertFalse(mappings[1].getConstraint().isForbidden());
        assertFalse(mappings[2].getConstraint().isForbidden());
        assertFalse(mappings[3].getConstraint().isForbidden());

        assertFalse(mappings[0].getConstraint().isAnyRole());
        assertTrue (mappings[1].getConstraint().isAnyRole());
        assertFalse(mappings[2].getConstraint().isAnyRole());
        assertFalse(mappings[3].getConstraint().isAnyRole());

        assertFalse(mappings[0].getConstraint().hasRole("administrator"));
        assertTrue (mappings[1].getConstraint().hasRole("administrator"));
        assertTrue (mappings[2].getConstraint().hasRole("administrator"));
        assertFalse(mappings[3].getConstraint().hasRole("administrator"));

        assertTrue (mappings[0].getConstraint().getAuthenticate());
        assertTrue (mappings[1].getConstraint().getAuthenticate());
        assertTrue (mappings[2].getConstraint().getAuthenticate());
        assertFalse(mappings[3].getConstraint().getAuthenticate());
    }

    @Test
    public void testBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _security.setStrict(false);
        _server.start();

        String response;
        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:wrong") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));


        // test admin
        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("admin:wrong") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:password") + "\r\n" +
                "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 403 "));
        assertTrue(response.indexOf("!role") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("admin:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testFormDispatch() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",true));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf("Cache-Control: no-cache") > 0);
        assertTrue(response.indexOf("Expires") > 0);
        assertTrue(response.indexOf("URI=/ctx/testLoginPage") > 0);

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("testErrorPage") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);
    }

    @Test
    public void testFormRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",false));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf(" 302 Found") > 0);
        assertTrue(response.indexOf("/ctx/testLoginPage") > 0);
        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/testLoginPage HTTP/1.0\r\n"+
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.indexOf(" 200 OK") > 0);
        assertTrue(response.indexOf("URI=/ctx/testLoginPage") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("Location") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);
    }

    @Test
    public void testFormPostRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",false));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("POST /ctx/auth/info HTTP/1.0\r\n"+
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 27\r\n" +
                "\r\n" +
                "test_parameter=test_value\r\n");
        assertTrue(response.indexOf(" 302 Found") > 0);
        assertTrue(response.indexOf("/ctx/testLoginPage") > 0);
        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/testLoginPage HTTP/1.0\r\n"+
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.indexOf(" 200 OK") > 0);
        assertTrue(response.indexOf("URI=/ctx/testLoginPage") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("Location") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        // sneak in other request
        response = _connector.getResponses("GET /ctx/auth/other HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(!response.contains("test_value"));

        // retry post as GET
        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.contains("test_value"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);
    }
    
    @Test
    public void testFormNoCookies() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",false));
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf(" 302 Found") > 0);
        assertTrue(response.indexOf("/ctx/testLoginPage") > 0);
        int jsession=response.indexOf(";jsessionid=");
        String session = response.substring(jsession + 12, response.indexOf("\r\n",jsession));

        response = _connector.getResponses("GET /ctx/testLoginPage;jsessionid="+session+";other HTTP/1.0\r\n"+
                "\r\n");
        assertTrue(response.indexOf(" 200 OK") > 0);
        assertTrue(response.indexOf("URI=/ctx/testLoginPage") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check;jsessionid="+session+";other HTTP/1.0\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
        "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("Location") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check;jsessionid="+session+";other HTTP/1.0\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info;jsessionid="+session+";other HTTP/1.0\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info;jsessionid="+session+";other HTTP/1.0\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);
    }

    @Test
    public void testStrictBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;
        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:wrong") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user2:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));


        // test admin
        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("admin:wrong") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
        assertTrue(response.indexOf("WWW-Authenticate: basic realm=\"TestRealm\"") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user:password") + "\r\n" +
                "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 403 "));
        assertTrue(response.indexOf("!role") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("admin:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));


        response = _connector.getResponses("GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testStrictFormDispatch()
            throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",true));
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        // assertTrue(response.indexOf(" 302 Found") > 0);
        // assertTrue(response.indexOf("/ctx/testLoginPage") > 0);
        assertTrue(response.indexOf("Cache-Control: no-cache") > 0);
        assertTrue(response.indexOf("Expires") > 0);
        assertTrue(response.indexOf("URI=/ctx/testLoginPage") > 0);

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
                "j_username=user&j_password=wrong\r\n");
        // assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("testErrorPage") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);



        // log in again as user2
        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertTrue(response.startsWith("HTTP/1.1 302 "));
//        assertTrue(response.indexOf("testLoginPage") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 36\r\n" +
                "\r\n" +
                "j_username=user2&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);



        // log in again as admin
        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertTrue(response.startsWith("HTTP/1.1 302 "));
//        assertTrue(response.indexOf("testLoginPage") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 36\r\n" +
                "\r\n" +
                "j_username=admin&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testStrictFormRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage","/testErrorPage",false));
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.indexOf(" 302 Found") > 0);
        assertTrue(response.indexOf("/ctx/testLoginPage") > 0);

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 31\r\n" +
                "\r\n" +
                "j_username=user&j_password=wrong\r\n");
        assertTrue(response.indexOf("Location") > 0);

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 35\r\n" +
                "\r\n" +
                "j_username=user&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);



        // log in again as user2
        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("testLoginPage") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 36\r\n" +
                "\r\n" +
                "j_username=user2&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));


        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"));
        assertTrue(response.indexOf("!role") > 0);



        // log in again as admin
        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertTrue(response.startsWith("HTTP/1.1 302 "));
//        assertTrue(response.indexOf("testLoginPage") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("POST /ctx/j_security_check HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 36\r\n" +
                "\r\n" +
                "j_username=admin&j_password=password\r\n");
        assertTrue(response.startsWith("HTTP/1.1 302 "));
        assertTrue(response.indexOf("Location") > 0);
        assertTrue(response.indexOf("/ctx/auth/info") > 0);
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf(";Path=/ctx"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/admin/info HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testRoleRef() throws Exception
    {
        RoleCheckHandler check=new RoleCheckHandler();
        _security.setHandler(check);
        _security.setAuthenticator(new BasicAuthenticator());
        _security.setStrict(false);
        _server.start();

        String response;
        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user2:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 500 "));

        _server.stop();

        RoleRefHandler roleref = new RoleRefHandler();
        _security.setHandler(roleref);
        roleref.setHandler(check);

        _server.start();

        response = _connector.getResponses("GET /ctx/auth/info HTTP/1.0\r\n" +
                "Authorization: Basic " + B64Code.encode("user2:password") + "\r\n" +
                "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testDeferredBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _security.setStrict(false);
        _server.start();

        String response;

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n"+
            "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.indexOf("user=null") > 0);

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n"+
                "Authorization: Basic " + B64Code.encode("admin:wrong") + "\r\n" +
            "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.indexOf("user=null") > 0);

        response = _connector.getResponses("GET /ctx/noauth/info HTTP/1.0\r\n"+
                "Authorization: Basic " + B64Code.encode("admin:password") + "\r\n" +
            "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.indexOf("user=admin") > 0);
    }

    @Test
    public void testRelaxedMethod() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _security.setStrict(false);
        _server.start();

        String response;
        response = _connector.getResponses("GET /ctx/forbid/somethig HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 "));
        
        response = _connector.getResponses("POST /ctx/forbid/post HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 "));
        
        response = _connector.getResponses("GET /ctx/forbid/post HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 "));  // This is so stupid, but it is the S P E C
    }
    private class RequestHandler extends AbstractHandler
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
                if (request.getParameter("test_parameter")!=null)
                    response.getWriter().println(request.getParameter("test_parameter"));
            }
            else
                response.sendError(500);
        }
    }

    private class RoleRefHandler extends HandlerWrapper
    {
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
         */
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            UserIdentity.Scope old = ((Request) request).getUserIdentityScope();

            UserIdentity.Scope scope = new UserIdentity.Scope()
            {
                public String getContextPath()
                {
                    return "/";
                }

                public String getName()
                {
                    return "someServlet";
                }

                public Map<String, String> getRoleRefMap()
                {
                    Map<String, String> map = new HashMap<String, String>();
                    map.put("untranslated", "user");
                    return map;
                }
            };

            ((Request)request).setUserIdentityScope(scope);

            try
            {
                super.handle(target,baseRequest,request, response);
            }
            finally
            {
                ((Request)request).setUserIdentityScope(old);
            }
        }
    }

    private class RoleCheckHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response ) throws IOException, ServletException
        {
            ((Request) request).setHandled(true);
            if (request.getAuthType()==null || "user".equals(request.getRemoteUser()) || request.isUserInRole("untranslated"))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }
}
