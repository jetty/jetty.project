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

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @version $Revision: 1441 $ $Date: 2010-04-02 12:28:17 +0200 (Fri, 02 Apr 2010) $
 */
public class SpecExampleConstraintTest
{
    private static final String TEST_REALM = "TestRealm";
    private static Server _server;
    private static LocalConnector _connector;
    private static SessionHandler _session;
    private ConstraintSecurityHandler _security;

    @BeforeAll
    public static void startServer()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.setConnectors(new Connector[]{_connector});

        ContextHandler context = new ContextHandler();
        _session = new SessionHandler();

        TestLoginService loginService = new TestLoginService(TEST_REALM);

        loginService.putUser("fred", new Password("password"), IdentityService.NO_ROLES);
        loginService.putUser("harry", new Password("password"), new String[]{"HOMEOWNER"});
        loginService.putUser("chris", new Password("password"), new String[]{"CONTRACTOR"});
        loginService.putUser("steven", new Password("password"), new String[]{"SALESCLERK"});

        context.setContextPath("/ctx");
        _server.setHandler(context);
        context.setHandler(_session);

        _server.addBean(loginService);
    }

    @BeforeEach
    public void setupSecurity()
    {
        _security = new ConstraintSecurityHandler();
        _session.setHandler(_security);
        RequestHandler handler = new RequestHandler();
        _security.setHandler(handler);

        
        /*
        
        <security-constraint>
        <web-resource-collection>
        <web-resource-name>precluded methods</web-resource-name>
        <url-pattern>/*</url-pattern>
        <url-pattern>/acme/wholesale/*</url-pattern>
        <url-pattern>/acme/retail/*</url-pattern>
        <http-method-exception>GET</http-method-exception>
        <http-method-exception>POST</http-method-exception>
        </web-resource-collection>
        <auth-constraint/>
        </security-constraint>
        */

        Constraint constraint0 = new Constraint();
        constraint0.setAuthenticate(true);
        constraint0.setName("precluded methods");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/*");
        mapping0.setConstraint(constraint0);
        mapping0.setMethodOmissions(new String[]{"GET", "POST"});

        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/acme/wholesale/*");
        mapping1.setConstraint(constraint0);
        mapping1.setMethodOmissions(new String[]{"GET", "POST"});

        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/acme/retail/*");
        mapping2.setConstraint(constraint0);
        mapping2.setMethodOmissions(new String[]{"GET", "POST"});
        
        /*
        
        <security-constraint>
        <web-resource-collection>
        <web-resource-name>wholesale</web-resource-name>
        <url-pattern>/acme/wholesale/*</url-pattern>
        <http-method>GET</http-method>
        <http-method>PUT</http-method>
        </web-resource-collection>
        <auth-constraint>
        <role-name>SALESCLERK</role-name>
        </auth-constraint>
        </security-constraint>
        */
        Constraint constraint1 = new Constraint();
        constraint1.setAuthenticate(true);
        constraint1.setName("wholesale");
        constraint1.setRoles(new String[]{"SALESCLERK"});
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/acme/wholesale/*");
        mapping3.setConstraint(constraint1);
        mapping3.setMethod("GET");
        ConstraintMapping mapping4 = new ConstraintMapping();
        mapping4.setPathSpec("/acme/wholesale/*");
        mapping4.setConstraint(constraint1);
        mapping4.setMethod("PUT");

        /*
        <security-constraint>
          <web-resource-collection>
            <web-resource-name>wholesale 2</web-resource-name>
            <url-pattern>/acme/wholesale/*</url-pattern>
            <http-method>GET</http-method>
            <http-method>POST</http-method>
          </web-resource-collection>
          <auth-constraint>
            <role-name>CONTRACTOR</role-name>
          </auth-constraint>
          <user-data-constraint>
             <transport-guarantee>CONFIDENTIAL</transport-guarantee>
          </user-data-constraint>
        </security-constraint>
         */
        Constraint constraint2 = new Constraint();
        constraint2.setAuthenticate(true);
        constraint2.setName("wholesale 2");
        constraint2.setRoles(new String[]{"CONTRACTOR"});
        constraint2.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping5 = new ConstraintMapping();
        mapping5.setPathSpec("/acme/wholesale/*");
        mapping5.setMethod("GET");
        mapping5.setConstraint(constraint2);
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/acme/wholesale/*");
        mapping6.setMethod("POST");
        mapping6.setConstraint(constraint2);
        
        /*
<security-constraint>
<web-resource-collection>
<web-resource-name>retail</web-resource-name>
<url-pattern>/acme/retail/*</url-pattern>
<http-method>GET</http-method>
<http-method>POST</http-method>
</web-resource-collection>
<auth-constraint>
<role-name>CONTRACTOR</role-name>
<role-name>HOMEOWNER</role-name>
</auth-constraint>
</security-constraint>
*/
        Constraint constraint4 = new Constraint();
        constraint4.setName("retail");
        constraint4.setAuthenticate(true);
        constraint4.setRoles(new String[]{"CONTRACTOR", "HOMEOWNER"});
        ConstraintMapping mapping7 = new ConstraintMapping();
        mapping7.setPathSpec("/acme/retail/*");
        mapping7.setMethod("GET");
        mapping7.setConstraint(constraint4);
        ConstraintMapping mapping8 = new ConstraintMapping();
        mapping8.setPathSpec("/acme/retail/*");
        mapping8.setMethod("POST");
        mapping8.setConstraint(constraint4);

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add("CONTRACTOR");
        knownRoles.add("HOMEOWNER");
        knownRoles.add("SALESCLERK");

        _security.setConstraintMappings(Arrays.asList(new ConstraintMapping[]
            {
                mapping0, mapping1, mapping2, mapping3, mapping4, mapping5,
                mapping6, mapping7, mapping8
            }), knownRoles);
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
    public void testUncoveredHttpMethodDetection() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        Set<String> paths = _security.getPathsWithUncoveredHttpMethods();
        assertEquals(1, paths.size());
        assertEquals("/*", paths.iterator().next());
    }

    @Test
    public void testUncoveredHttpMethodsDenied() throws Exception
    {
        try
        {
            _security.setDenyUncoveredHttpMethods(false);
            _security.setAuthenticator(new BasicAuthenticator());
            _server.start();

            //There are uncovered methods for GET/POST at url /*
            //without deny-uncovered-http-methods they should be accessible
            String response;
            response = _connector.getResponse("GET /ctx/index.html HTTP/1.0\r\n\r\n");
            assertThat(response, startsWith("HTTP/1.1 200 OK"));

            //set deny-uncovered-http-methods true
            _security.setDenyUncoveredHttpMethods(true);

            //check they cannot be accessed
            response = _connector.getResponse("GET /ctx/index.html HTTP/1.0\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));
        }
        finally
        {
            _security.setDenyUncoveredHttpMethods(false);
        }
    }

    @Test
    public void testBasic() throws Exception
    {

        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;
        /*
          /star                 all methods except GET/POST forbidden
          /acme/wholesale/star  all methods except GET/POST forbidden
          /acme/retail/star     all methods except GET/POST forbidden
          /acme/wholesale/star  GET must be in role CONTRACTOR or SALESCLERK
          /acme/wholesale/star  POST must be in role CONTRACTOR and confidential transport
          /acme/retail/star     GET must be in role CONTRACTOR or HOMEOWNER
          /acme/retail/star     POST must be in role CONTRACTOR or HOMEOWNER
        */

        //a user in role HOMEOWNER is forbidden HEAD request
        response = _connector.getResponse("HEAD /ctx/index.html HTTP/1.0\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"));

        Base64.Encoder authEncoder = Base64.getEncoder();
        String encodedHarry = authEncoder.encodeToString("harry:password".getBytes(ISO_8859_1));
        String encodedChris = authEncoder.encodeToString("chris:password".getBytes(ISO_8859_1));

        response = _connector.getResponse("HEAD /ctx/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedHarry + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("HEAD /ctx/acme/wholesale/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedHarry + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("HEAD /ctx/acme/retail/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedHarry + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        //a user in role CONTRACTOR can do a GET
        response = _connector.getResponse("GET /ctx/acme/wholesale/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedChris + "\r\n" +
            "\r\n");

        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //a user in role CONTRACTOR can only do a post if confidential
        response = _connector.getResponse("POST /ctx/acme/wholesale/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedChris + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!Secure"));

        //a user in role HOMEOWNER can do a GET
        response = _connector.getResponse("GET /ctx/acme/retail/index.html HTTP/1.0\r\n" +
            "Authorization: Basic " + encodedHarry + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    private class RequestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            response.setStatus(200);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().println("URI=" + request.getRequestURI());
            String user = request.getRemoteUser();
            response.getWriter().println("user=" + user);
            if (request.getParameter("test_parameter") != null)
                response.getWriter().println(request.getParameter("test_parameter"));
        }
    }
}
