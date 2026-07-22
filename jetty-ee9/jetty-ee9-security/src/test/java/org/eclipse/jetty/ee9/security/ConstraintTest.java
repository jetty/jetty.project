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

package org.eclipse.jetty.ee9.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.AbstractHandler;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.HandlerWrapper;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.nested.SessionHandler;
import org.eclipse.jetty.ee9.nested.UserIdentityScope;
import org.eclipse.jetty.ee9.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.ee9.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.ee9.security.authentication.FormAuthenticator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.security.Password;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstraintTest
{
    private static final String TEST_REALM = "TestRealm";
    private Server _server;
    private LocalConnector _connector;
    private ConstraintSecurityHandler _security;
    private HttpConfiguration _config;
    private ContextHandler _contextHandler;
    private SessionHandler _sessionhandler;
    private ServletConstraint _forbidConstraint;
    private ServletConstraint _authAnyRoleConstraint;
    private ServletConstraint _authAdminConstraint;
    private ServletConstraint _relaxConstraint;
    private ServletConstraint _loginPageConstraint;
    private ServletConstraint _noAuthConstraint;
    private ServletConstraint _confidentialDataConstraint;
    private ServletConstraint _anyUserAuthConstraint;

    @BeforeEach
    public void setupServer()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _config = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _server.setConnectors(new Connector[]{_connector});

        _contextHandler = new ContextHandler();
        _sessionhandler = new SessionHandler();

        TestLoginService loginService = new TestLoginService(TEST_REALM);

        loginService.putUser("user0", new Password("password"), new String[]{});
        loginService.putUser("user", new Password("password"), new String[]{"user"});
        loginService.putUser("user2", new Password("password"), new String[]{"user"});
        loginService.putUser("admin", new Password("password"), new String[]{"user", "administrator"});
        loginService.putUser("user3", new Password("password"), new String[]{"foo"});
        loginService.putUser("user4", new Password("password"), new String[]{"A", "B", "C", "D"});

        _contextHandler.setContextPath("/ctx");
        _server.setHandler(_contextHandler);
        _contextHandler.setHandler(_sessionhandler);

        _server.addBean(loginService);

        _security = new ConstraintSecurityHandler();
        _sessionhandler.setHandler(_security);
        RequestHandler requestHandler = new RequestHandler(new String[]{"user", "user4"}, new String[]{"user", "foo"});
        _security.setHandler(requestHandler);

        _security.setConstraintMappings(getConstraintMappings(), getKnownRoles());
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _server.stop();
    }

    public Set<String> getKnownRoles()
    {
        Set<String> knownRoles = new HashSet<>();
        knownRoles.add("user");
        knownRoles.add("administrator");
        knownRoles.add("A");
        knownRoles.add("B");
        knownRoles.add("C");
        knownRoles.add("D");
        return knownRoles;
    }

    private List<ConstraintMapping> getConstraintMappings()
    {
        _forbidConstraint = new ServletConstraint();
        _forbidConstraint.setAuthenticate(true);
        _forbidConstraint.setName("forbid");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/forbid/*");
        mapping0.setConstraint(_forbidConstraint);

        _authAnyRoleConstraint = new ServletConstraint();
        _authAnyRoleConstraint.setAuthenticate(true);
        _authAnyRoleConstraint.setName("auth");
        _authAnyRoleConstraint.setRoles(new String[]{ServletConstraint.ANY_ROLE});
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/auth/*");
        mapping1.setConstraint(_authAnyRoleConstraint);

        _authAdminConstraint = new ServletConstraint();
        _authAdminConstraint.setAuthenticate(true);
        _authAdminConstraint.setName("admin");
        _authAdminConstraint.setRoles(new String[]{"administrator"});
        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/admin/*");
        mapping2.setConstraint(_authAdminConstraint);
        mapping2.setMethod("GET");
        ConstraintMapping mapping2o = new ConstraintMapping();
        mapping2o.setPathSpec("/admin/*");
        mapping2o.setConstraint(_forbidConstraint);
        mapping2o.setMethodOmissions(new String[]{"GET"});

        _relaxConstraint = new ServletConstraint();
        _relaxConstraint.setAuthenticate(false);
        _relaxConstraint.setName("relax");
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/admin/relax/*");
        mapping3.setConstraint(_relaxConstraint);

        _loginPageConstraint = new ServletConstraint();
        _loginPageConstraint.setAuthenticate(true);
        _loginPageConstraint.setName("loginpage");
        _loginPageConstraint.setRoles(new String[]{"administrator"});
        ConstraintMapping mapping4 = new ConstraintMapping();
        mapping4.setPathSpec("/testLoginPage");
        mapping4.setConstraint(_loginPageConstraint);

        _noAuthConstraint = new ServletConstraint();
        _noAuthConstraint.setAuthenticate(false);
        _noAuthConstraint.setName("allow forbidden");
        ConstraintMapping mapping5 = new ConstraintMapping();
        mapping5.setPathSpec("/forbid/post");
        mapping5.setConstraint(_noAuthConstraint);
        mapping5.setMethod("POST");
        ConstraintMapping mapping5o = new ConstraintMapping();
        mapping5o.setPathSpec("/forbid/post");
        mapping5o.setConstraint(_forbidConstraint);
        mapping5o.setMethodOmissions(new String[]{"POST"});

        _confidentialDataConstraint = new ServletConstraint();
        _confidentialDataConstraint.setAuthenticate(false);
        _confidentialDataConstraint.setName("data constraint");
        _confidentialDataConstraint.setDataConstraint(ServletConstraint.DC_CONFIDENTIAL);
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/data/*");
        mapping6.setConstraint(_confidentialDataConstraint);

        _anyUserAuthConstraint = new ServletConstraint();
        _anyUserAuthConstraint.setAuthenticate(true);
        _anyUserAuthConstraint.setName("** constraint");
        _anyUserAuthConstraint.setRoles(new String[]{
            ServletConstraint.ANY_AUTH, "user"
        }); //the "user" role is superfluous once ** has been defined
        ConstraintMapping mapping7 = new ConstraintMapping();
        mapping7.setPathSpec("/starstar/*");
        mapping7.setConstraint(_anyUserAuthConstraint);

        return Arrays.asList(mapping0, mapping1, mapping2, mapping2o, mapping3, mapping4, mapping5, mapping5o, mapping6, mapping7);
    }

    @Test
    public void testCombiningConstraints() throws Exception
    {
        String getString = "GET /ctx/test/info HTTP/1.0";
        String requestString = getString + "\r\n\r\n";
        String forbiddenString = "HTTP/1.1 403 Forbidden";

        _security.setAuthenticator(new BasicAuthenticator());

        //an auth-constraint with role *
        ServletConstraint authAnyRoleConstraint = new ServletConstraint();
        authAnyRoleConstraint.setAuthenticate(true);
        authAnyRoleConstraint.setName("anyAuth");
        authAnyRoleConstraint.setRoles(new String[]{ServletConstraint.ANY_ROLE});
        ConstraintMapping starMapping = new ConstraintMapping();
        starMapping.setPathSpec("/test/*");
        starMapping.setConstraint(authAnyRoleConstraint);

        //an auth-constraint with role **
        ServletConstraint authAnyAuthConstraint = new ServletConstraint();
        authAnyAuthConstraint.setAuthenticate(true);
        authAnyAuthConstraint.setName("** constraint");
        authAnyAuthConstraint.setRoles(new String[]{
            ServletConstraint.ANY_AUTH, "user"
        });
        ConstraintMapping starStarMapping = new ConstraintMapping();
        starStarMapping.setPathSpec("/test/*");
        starStarMapping.setConstraint(authAnyAuthConstraint);

        //a relax constraint, ie no auth-constraint
        ServletConstraint relaxConstraint = new ServletConstraint();
        relaxConstraint.setAuthenticate(false);
        relaxConstraint.setName("relax");
        ConstraintMapping relaxMapping = new ConstraintMapping();
        relaxMapping.setPathSpec("/test/*");
        relaxMapping.setConstraint(relaxConstraint);

        //a forbidden constraint
        ServletConstraint forbidConstraint = new ServletConstraint();
        forbidConstraint.setAuthenticate(true);
        forbidConstraint.setName("forbid");
        ConstraintMapping forbidMapping = new ConstraintMapping();
        forbidMapping.setPathSpec("/test/*");
        forbidMapping.setConstraint(forbidConstraint);

        //an auth-constraint with roles A, B
        ServletConstraint rolesConstraint = new ServletConstraint();
        rolesConstraint.setAuthenticate(true);
        rolesConstraint.setName("admin");
        rolesConstraint.setRoles(new String[]{"A", "B"});
        ConstraintMapping rolesABMapping = new ConstraintMapping();
        rolesABMapping.setPathSpec("/test/*");
        rolesABMapping.setConstraint(rolesConstraint);

        //an auth-constraint with roles C, C
        ServletConstraint roles2Constraint = new ServletConstraint();
        roles2Constraint.setAuthenticate(true);
        roles2Constraint.setName("admin");
        roles2Constraint.setRoles(new String[]{"C", "D"});
        ConstraintMapping rolesCDMapping = new ConstraintMapping();
        rolesCDMapping.setPathSpec("/test/*");
        rolesCDMapping.setConstraint(roles2Constraint);

        //test combining forbidden with relax
        List<ConstraintMapping> combinableConstraints = Arrays.asList(forbidMapping, relaxMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        String response;
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith(forbiddenString));

        //test combining forbidden with *
        _server.stop();
        combinableConstraints = Arrays.asList(forbidMapping, starMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith(forbiddenString));

        //test combining forbidden with **
        _server.stop();
        combinableConstraints = Arrays.asList(forbidMapping, starStarMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith(forbiddenString));

        //test combining forbidden with roles
        _server.stop();
        combinableConstraints = Arrays.asList(forbidMapping, rolesABMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith(forbiddenString));

        //test combining * with relax
        _server.stop();
        combinableConstraints = Arrays.asList(starMapping, relaxMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining * with **
        _server.stop();
        combinableConstraints = Arrays.asList(starMapping, starStarMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        response = _connector.getResponse(getString + "\r\n" +
            "Authorization: Basic " + authBase64("user4:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining * with roles
        _server.stop();
        combinableConstraints = Arrays.asList(starMapping, rolesABMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        response = _connector.getResponse(getString + "\r\n" +
            "Authorization: Basic " + authBase64("user4:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining ** with relax
        _server.stop();
        combinableConstraints = Arrays.asList(starStarMapping, relaxMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining ** with roles
        _server.stop();
        combinableConstraints = Arrays.asList(starStarMapping, rolesABMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        response = _connector.getResponse(getString + "\r\n" +
            "Authorization: Basic " + authBase64("user4:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining roles with roles
        _server.stop();
        combinableConstraints = Arrays.asList(rolesCDMapping, rolesABMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        response = _connector.getResponse(getString + "\r\n" +
            "Authorization: Basic " + authBase64("user4:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //test combining relax with roles
        _server.stop();
        combinableConstraints = Arrays.asList(rolesABMapping, relaxMapping);
        _security.setConstraintMappings(combinableConstraints);
        _server.start();
        response = _connector.getResponse(requestString);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    /**
     * Test that constraint mappings added before the context starts are
     * retained, but those that are added after the context starts are not.
     */
    @Test
    public void testDurableConstraints() throws Exception
    {
        List<ConstraintMapping> mappings =  _security.getConstraintMappings();
        assertThat("before start", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.start();
        
        mappings =  _security.getConstraintMappings();
        assertThat("after start", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.stop();
        
        //After a stop, just the durable mappings are left
        mappings = _security.getConstraintMappings();
        assertThat("after stop", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.start();
        
        //Verify the constraints are just the durables
        mappings = _security.getConstraintMappings();
        assertThat("after restart", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        //Add a non-durable constraint
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/xxxx/*");
        ServletConstraint constraint = new ServletConstraint();
        constraint.setAuthenticate(false);
        constraint.setName("transient");
        mapping.setConstraint(constraint);
        
        _security.addConstraintMapping(mapping);
        
        mappings = _security.getConstraintMappings();
        assertThat("after addition", getConstraintMappings().size() + 1, Matchers.equalTo(mappings.size()));
        
        _server.stop();
        _server.start();
        
        //After a stop, only the durable mappings remain
        mappings = _security.getConstraintMappings();
        assertThat("after addition", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        //test that setConstraintMappings replaces all existing mappings whether durable or not
        
        //test setConstraintMappings in durable state
        _server.stop();
        _security.setConstraintMappings(Collections.singletonList(mapping));
        mappings = _security.getConstraintMappings();
        assertThat("after set during stop", 1, Matchers.equalTo(mappings.size()));
        _server.start();
        mappings = _security.getConstraintMappings();
        assertThat("after set after start", 1, Matchers.equalTo(mappings.size()));
       
        //test setConstraintMappings not in durable state
        _server.stop();
        _server.start();
        assertThat("no change after start", 1, Matchers.equalTo(mappings.size()));
        _security.setConstraintMappings(getConstraintMappings());
        mappings = _security.getConstraintMappings();
        assertThat("durables lost", getConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        _server.stop();
        mappings = _security.getConstraintMappings();
        assertThat("no mappings", 0, Matchers.equalTo(mappings.size()));
    }
    
    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-1
     * &#064;ServletSecurity
     *
     * @throws Exception if test fails
     */
    @Test
    public void testSecurityElementExample131()
    {
        ServletSecurityElement element = new ServletSecurityElement();
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(mappings.isEmpty());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-2
     * &#064;ServletSecurity(@HttpConstraint(transportGuarantee = TransportGuarantee.CONFIDENTIAL))
     *
     * @throws Exception if test fails
     */
    @Test
    public void testSecurityElementExample132()
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL);
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        assertEquals(2, mapping.getConstraint().getDataConstraint());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-3
     *
     * @throws Exception if test fails
     * @ServletSecurity(@HttpConstraint(EmptyRoleSemantic.DENY))
     */
    @Test
    public void testSecurityElementExample133() throws Exception
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(EmptyRoleSemantic.DENY);
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(!mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        assertTrue(mapping.getConstraint().isForbidden());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-4
     *
     * @throws Exception if test fails
     * @ServletSecurity(@HttpConstraint(rolesAllowed = "R1"))
     */
    @Test
    public void testSecurityElementExample134() throws Exception
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(TransportGuarantee.NONE, "R1");
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(!mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        assertTrue(mapping.getConstraint().getAuthenticate());
        assertTrue(mapping.getConstraint().getRoles() != null);
        assertEquals(1, mapping.getConstraint().getRoles().length);
        assertEquals("R1", mapping.getConstraint().getRoles()[0]);
        assertEquals(0, mapping.getConstraint().getDataConstraint());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-5
     *
     * @throws Exception if test fails
     * @ServletSecurity((httpMethodConstraints = {
     * @HttpMethodConstraint(value = "GET", rolesAllowed = "R1"),
     * @HttpMethodConstraint(value = "POST", rolesAllowed = "R1",
     * transportGuarantee = TransportGuarantee.CONFIDENTIAL)})
     */
    @Test
    public void testSecurityElementExample135() throws Exception
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<HttpMethodConstraintElement>();
        methodElements.add(new HttpMethodConstraintElement("GET", new HttpConstraintElement(TransportGuarantee.NONE, "R1")));
        methodElements.add(new HttpMethodConstraintElement("POST", new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL, "R1")));
        ServletSecurityElement element = new ServletSecurityElement(methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(!mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertEquals("GET", mappings.get(0).getMethod());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles()[0]);
        assertTrue(mappings.get(0).getMethodOmissions() == null);
        assertEquals(0, mappings.get(0).getConstraint().getDataConstraint());
        assertEquals("POST", mappings.get(1).getMethod());
        assertEquals("R1", mappings.get(1).getConstraint().getRoles()[0]);
        assertEquals(2, mappings.get(1).getConstraint().getDataConstraint());
        assertTrue(mappings.get(1).getMethodOmissions() == null);
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-6
     *
     * @throws Exception if test fails
     * @ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"), httpMethodConstraints = @HttpMethodConstraint("GET"))
     */
    @Test
    public void testSecurityElementExample136() throws Exception
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<HttpMethodConstraintElement>();
        methodElements.add(new HttpMethodConstraintElement("GET"));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(!mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertTrue(mappings.get(0).getMethodOmissions() != null);
        assertEquals("GET", mappings.get(0).getMethodOmissions()[0]);
        assertTrue(mappings.get(0).getConstraint().getAuthenticate());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles()[0]);
        assertEquals("GET", mappings.get(1).getMethod());
        assertTrue(mappings.get(1).getMethodOmissions() == null);
        assertEquals(0, mappings.get(1).getConstraint().getDataConstraint());
        assertFalse(mappings.get(1).getConstraint().getAuthenticate());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-7
     *
     * @throws Exception if test fails
     * @ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"),
     * httpMethodConstraints = @HttpMethodConstraint(value="TRACE",
     * emptyRoleSemantic = EmptyRoleSemantic.DENY))
     */
    @Test
    public void testSecurityElementExample137() throws Exception
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<HttpMethodConstraintElement>();
        methodElements.add(new HttpMethodConstraintElement("TRACE", new HttpConstraintElement(EmptyRoleSemantic.DENY)));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(!mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertTrue(mappings.get(0).getMethodOmissions() != null);
        assertEquals("TRACE", mappings.get(0).getMethodOmissions()[0]);
        assertTrue(mappings.get(0).getConstraint().getAuthenticate());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles()[0]);
        assertEquals("TRACE", mappings.get(1).getMethod());
        assertTrue(mappings.get(1).getMethodOmissions() == null);
        assertEquals(0, mappings.get(1).getConstraint().getDataConstraint());
        assertTrue(mappings.get(1).getConstraint().isForbidden());
    }

    @Test
    public void testUncoveredHttpMethodDetection() throws Exception
    {
        //Test no methods named
        ServletConstraint constraint1 = new ServletConstraint();
        constraint1.setAuthenticate(true);
        constraint1.setName("** constraint");
        constraint1.setRoles(new String[]{ServletConstraint.ANY_AUTH, "user"}); //No methods named, no uncovered methods
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/starstar/*");
        mapping1.setConstraint(constraint1);

        _security.setConstraintMappings(Collections.singletonList(mapping1));
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        Set<String> uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertTrue(uncoveredPaths.isEmpty()); //no uncovered methods

        //Test only an explicitly named method, no omissions to cover other methods
        ServletConstraint constraint2 = new ServletConstraint();
        constraint2.setAuthenticate(true);
        constraint2.setName("user constraint");
        constraint2.setRoles(new String[]{"user"});
        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/user/*");
        mapping2.setMethod("GET");
        mapping2.setConstraint(constraint2);

        _security.addConstraintMapping(mapping2);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertEquals(1, uncoveredPaths.size());
        assertThat("/user/*", is(in(uncoveredPaths)));

        //Test an explicitly named method with an http-method-omission to cover all other methods
        ServletConstraint constraint2a = new ServletConstraint();
        constraint2a.setAuthenticate(true);
        constraint2a.setName("forbid constraint");
        ConstraintMapping mapping2a = new ConstraintMapping();
        mapping2a.setPathSpec("/user/*");
        mapping2a.setMethodOmissions(new String[]{"GET"});
        mapping2a.setConstraint(constraint2a);

        _security.addConstraintMapping(mapping2a);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertEquals(0, uncoveredPaths.size());

        //Test an http-method-omission only
        ServletConstraint constraint3 = new ServletConstraint();
        constraint3.setAuthenticate(true);
        constraint3.setName("omit constraint");
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/omit/*");
        mapping3.setMethodOmissions(new String[]{"GET", "POST"});
        mapping3.setConstraint(constraint3);

        _security.addConstraintMapping(mapping3);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertThat("/omit/*", is(in(uncoveredPaths)));

        _security.setDenyUncoveredHttpMethods(true);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertEquals(0, uncoveredPaths.size());
    }

    public static Stream<Arguments> basicScenarios()
    {
        List<Arguments> scenarios = new ArrayList<>();

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/noauth/info HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/forbid/info HTTP/1.0\r\n\r\n",
                HttpStatus.FORBIDDEN_403
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/auth/info HTTP/1.0\r\n\r\n",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "POST /ctx/auth/info HTTP/1.1\r\n" +
                    "Host: test\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "0123456789",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                    assertThat(response.get(HttpHeader.CONNECTION), nullValue());
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "POST /ctx/auth/info HTTP/1.1\r\n" +
                    "Host: test\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "012345",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                    assertThat(response.get(HttpHeader.CONNECTION), is("close"));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/auth/info HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user:wrong") + "\r\n" +
                    "\r\n",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/auth/info HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user:password") + "\r\n" +
                    "\r\n",
                HttpStatus.OK_200
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "POST /ctx/auth/info HTTP/1.0\r\n" +
                    "Content-Length: 10\r\n" +
                    "Authorization: Basic " + authBase64("user:password") + "\r\n" +
                    "\r\n" +
                    "0123456789",
                HttpStatus.OK_200
            )
        ));

        // == test admin
        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/admin/info HTTP/1.0\r\n\r\n",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/admin/info HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("admin:wrong") + "\r\n" +
                    "\r\n",
                HttpStatus.UNAUTHORIZED_401,
                (response) ->
                {
                    String authHeader = response.get(HttpHeader.WWW_AUTHENTICATE);
                    assertThat(response.toString(), authHeader, containsString("Basic realm=\"TestRealm\""));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/admin/info HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user:password") + "\r\n" +
                    "\r\n",
                HttpStatus.FORBIDDEN_403,
                (response) ->
                {
                    assertThat(response.getContent(), containsString("!role"));
                }
            )
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/admin/info HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("admin:password") + "\r\n" +
                    "\r\n",
                HttpStatus.OK_200)
        ));

        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200
            )
        ));

        // == check GET is in role administrator
        scenarios.add(Arguments.of(
            new Scenario(
                "GET /ctx/omit/x HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("admin:password") + "\r\n" +
                    "\r\n",
                HttpStatus.OK_200

            )
        ));

        // == check POST is in role user
        scenarios.add(Arguments.of(
            new Scenario(
                "POST /ctx/omit/x HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
                    "\r\n", HttpStatus.OK_200)
        ));

        // == check POST can be in role foo too
        scenarios.add(Arguments.of(
            new Scenario(
                "POST /ctx/omit/x HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user3:password") + "\r\n" +
                    "\r\n",
                HttpStatus.OK_200)
        ));

        // == check HEAD cannot be in role user
        scenarios.add(Arguments.of(
            new Scenario(
                "HEAD /ctx/omit/x HTTP/1.0\r\n" +
                    "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
                    "\r\n",
                HttpStatus.FORBIDDEN_403)
        ));

        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("basicScenarios")
    public void testBasic(Scenario scenario) throws Exception
    {
        List<ConstraintMapping> list = new ArrayList<>(getConstraintMappings());

        ServletConstraint constraint6 = new ServletConstraint();
        constraint6.setAuthenticate(true);
        constraint6.setName("omit HEAD and GET");
        constraint6.setRoles(new String[]{"user"});
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/omit/*");
        mapping6.setConstraint(constraint6);
        mapping6.setMethodOmissions(new String[]{
            "GET", "HEAD"
        }); //requests for every method except GET and HEAD must be in role "user"
        list.add(mapping6);

        ServletConstraint constraint7 = new ServletConstraint();
        constraint7.setAuthenticate(true);
        constraint7.setName("non-omitted GET");
        constraint7.setRoles(new String[]{"administrator"});
        ConstraintMapping mapping7 = new ConstraintMapping();
        mapping7.setPathSpec("/omit/*");
        mapping7.setConstraint(constraint7);
        mapping7.setMethod("GET"); //requests for GET must be in role "admin"
        list.add(mapping7);

        ServletConstraint constraint8 = new ServletConstraint();
        constraint8.setAuthenticate(true);
        constraint8.setName("non specific");
        constraint8.setRoles(new String[]{"foo"});
        ConstraintMapping mapping8 = new ConstraintMapping();
        mapping8.setPathSpec("/omit/*");
        mapping8.setConstraint(constraint8); //requests for all methods must be in role "foo"
        list.add(mapping8);

        Set<String> knownRoles = new HashSet<>();
        knownRoles.add("user");
        knownRoles.add("administrator");
        knownRoles.add("foo");

        _security.setConstraintMappings(list, knownRoles);

        _security.setAuthenticator(new BasicAuthenticator());
        try
        {
            _server.start();
            String rawResponse = _connector.getResponse(scenario.rawRequest);
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(rawResponse), scenario.rawRequest.startsWith("HEAD "));
            assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
            if (scenario.extraAsserts != null)
                scenario.extraAsserts.accept(response);
        }
        finally
        {
            _server.stop();
        }
    }

    private String digest(String nonce, String password, String nc) throws Exception
    {
        DigestAuthenticator authenticator = (DigestAuthenticator)_security.getAuthenticator();
        MessageDigest md = MessageDigest.getInstance(authenticator.getAlgorithm());

        // Calculate A1 digest.
        String a1 = "user:TestRealm:" + password;
        byte[] ha1 = md.digest(a1.getBytes(UTF_8));

        // Calculate A2 digest.
        String a2 = "GET:/ctx/auth/info";
        byte[] ha2 = md.digest(a2.getBytes(UTF_8));

        String rsp = StringUtil.toHexString(ha1).toLowerCase(Locale.ROOT) + ":" + nonce + ":" + nc +
            ":1234567890:auth:" + StringUtil.toHexString(ha2).toLowerCase(Locale.ROOT);
        return StringUtil.toHexString(md.digest(rsp.getBytes(UTF_8))).toLowerCase(Locale.ROOT);
    }

    @Test
    public void testDigest() throws Exception
    {
        DigestAuthenticator authenticator = new DigestAuthenticator();
        authenticator.setMaxNonceCount(5);
        _security.setAuthenticator(authenticator);
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Digest realm=\"TestRealm\""));

        Pattern nonceP = Pattern.compile("nonce=\"([^\"]*)\",");
        Matcher matcher = nonceP.matcher(response);
        assertTrue(matcher.find());
        String nonce = matcher.group(1);

        // Wrong password.
        String digest = digest(nonce, "WRONG", "00000001");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000001, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));

        // Right password.
        digest = digest(nonce, "password", "00000002");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000002, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // Replay of request with nc=00000002.
        digest = digest(nonce, "password", "00000002");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000002, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));

        // Next request.
        digest = digest(nonce, "password", "00000004");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000004, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // Out of order request.
        digest = digest(nonce, "password", "00000003");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000003, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // Beyond max nonce count.
        digest = digest(nonce, "password", "00000006");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=00000006, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("stale=true"));
    }

    @Test
    public void testFormDispatch() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", true));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("Cache-Control: no-cache"));
        assertThat(response, containsString("Expires"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 31\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong\r\n");
        assertThat(response, containsString("testErrorPage"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));
    }

    @Test
    public void testFormRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("JSESSIONID=")));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));
        assertThat(response, not(containsString("JSESSIONID=")));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("JSESSIONID="));
        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 32\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong");
        assertThat(response, containsString("Location"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        assertThat(response, containsString("JSESSIONID="));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
    }

    public static Stream<Arguments> onAuthenticationTests()
    {
        return Stream.of(
            Arguments.of(false, 0),
            Arguments.of(false, -1),
            Arguments.of(false, 2400),
            Arguments.of(true, 0),
            Arguments.of(true, -1),
            Arguments.of(true, 2400)
        );
    }

    @ParameterizedTest
    @MethodSource("onAuthenticationTests")
    public void testSessionOnAuthentication(boolean sessionRenewOnAuthentication, int sessionMaxInactiveIntervalOnAuthentication) throws Exception
    {
        final int UNAUTH_SECONDS = 1200;

        // Use a FormAuthenticator as an example of session authentication
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));

        _sessionhandler.setMaxInactiveInterval(UNAUTH_SECONDS);
        _security.setSessionRenewedOnAuthentication(sessionRenewOnAuthentication);
        _security.setSessionMaxInactiveIntervalOnAuthentication(sessionMaxInactiveIntervalOnAuthentication);
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("JSESSIONID="));
        String sessionId = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));
        assertThat(response, not(containsString("JSESSIONID=" + sessionId)));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));

        if (sessionRenewOnAuthentication)
        {
            // check session ID has changed.
            assertNull(_sessionhandler.getSessionManager().getManagedSession(sessionId));
            assertThat(response, containsString("Set-Cookie:"));
            assertThat(response, containsString("JSESSIONID="));
            assertThat(response, not(containsString("JSESSIONID=" + sessionId)));
            sessionId = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        }
        else
        {
            // check session ID has not changed.
            assertThat(response, not(containsString("Set-Cookie:")));
            assertThat(response, not(containsString("JSESSIONID=")));
        }

        ManagedSession session = _sessionhandler.getSessionManager().getManagedSession(sessionId);
        if (sessionMaxInactiveIntervalOnAuthentication == 0)
        {
            // check max interval has not been updated
            assertThat(session.getMaxInactiveInterval(), is(UNAUTH_SECONDS));
        }
        else
        {
            // check max interval has not been updated
            assertThat(session.getMaxInactiveInterval(), is(sessionMaxInactiveIntervalOnAuthentication));
        }

        // check session still there.
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testFormPostRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("POST /ctx/auth/info HTTP/1.0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 27\r\n" +
            "\r\n" +
            "test_parameter=test_value\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 31\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong\r\n");

        assertThat(response, containsString("Location"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        // sneak in other request
        response = _connector.getResponse("GET /ctx/auth/other HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("test_value")));

        // retry post as GET
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("test_value"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));
    }

    @Test
    public void testNonFormPostRedirectHttp10() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response = _connector.getResponse("POST /ctx/auth/info HTTP/1.0\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: keep-alive\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "0123456789\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Connection: keep-alive"));

        response = _connector.getResponse("POST /ctx/auth/info HTTP/1.0\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: keep-alive\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "012345\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: keep-alive")));
    }

    @Test
    public void testNonFormPostRedirectHttp11() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response = _connector.getResponse("POST /ctx/auth/info HTTP/1.1\r\n" +
            "Host: test\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "0123456789\r\n");
        assertThat(response, containsString(" 303 See Other"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: close")));

        response = _connector.getResponse("POST /ctx/auth/info HTTP/1.1\r\n" +
            "Host: test\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "012345\r\n");
        assertThat(response, containsString(" 303 See Other"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("Connection: close"));
    }

    @Test
    public void testFormNoCookies() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        int jsession = response.indexOf(";jsessionid=");
        String session = response.substring(jsession + 12, response.indexOf("\r\n", jsession));

        response = _connector.getResponse("GET /ctx/testLoginPage;jsessionid=" + session + ";other HTTP/1.0\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));

        response = _connector.getResponse("POST /ctx/j_security_check;jsessionid=" + session + ";other HTTP/1.0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 31\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong\r\n");
        assertThat(response, containsString("Location"));

        response = _connector.getResponse("POST /ctx/j_security_check;jsessionid=" + session + ";other HTTP/1.0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info;jsessionid=" + session + ";other HTTP/1.0\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/info;jsessionid=" + session + ";other HTTP/1.0\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));
    }

    /**
     * Test Request.login() Request.logout() with FORM authenticator
     */
    @Test
    public void testFormProgrammaticLoginLogout() throws Exception
    {
        //Test programmatic login/logout within same request:
        // login  - perform programmatic login that should succeed, next request should be also logged in
        // loginfail  - perform programmatic login that should fail, next request should not be logged in
        // loginfaillogin - perform programmatic login that should fail then another that succeeds, next request should be logged in
        // loginlogin - perform successful login then try another that should fail, next request should be logged in
        // loginlogout - perform successful login then logout, next request should not be logged in
        // loginlogoutlogin - perform successful login then logout then login successfully again, next request should be logged in
        _security.setHandler(new ProgrammaticLoginRequestHandler());
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response;

        //login
        response = _connector.getResponse("GET /ctx/prog?action=login HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=admin"));
        _server.stop();

        //loginfail
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginfail HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        if (response.contains("JSESSIONID"))
        {
            session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
            response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
                "Cookie: JSESSIONID=" + session + "\r\n" +
                "\r\n");
        }
        else
            response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n\r\n");

        assertThat(response, not(containsString("user=admin")));
        _server.stop();

        //loginfaillogin
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginfail HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        response = _connector.getResponse("GET /ctx/prog?action=login HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=admin"));
        _server.stop();

        //loginlogin
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginlogin HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=admin"));
        _server.stop();

        //loginlogout
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginlogout HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=null"));
        _server.stop();

        //loginlogoutlogin
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginlogoutlogin HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=user0"));
        _server.stop();
        
        //loginauth
        _server.start();
        response = _connector.getResponse("GET /ctx/prog?action=loginauth HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=admin"));
        assertThat(response, containsString("remoteUser=admin"));
        assertThat(response, containsString("authType=API"));
        assertThat(response, containsString("auth=true"));
        _server.stop();

        //Test constraint-based login with programmatic login/logout:
        // constraintlogin - perform constraint login, followed by programmatic login which should fail (already logged in)
        _server.start();
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("JSESSIONID="));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        assertThat(response, containsString("JSESSIONID="));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?action=constraintlogin HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        _server.stop();

        // logout - perform constraint login, followed by programmatic logout, which should succeed
        _server.start();
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("JSESSIONID="));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        assertThat(response, containsString("JSESSIONID="));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        response = _connector.getResponse("GET /ctx/prog?action=logout HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        response = _connector.getResponse("GET /ctx/prog?x=y HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("user=null"));
    }

    @Test
    public void testStrictBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"TestRealm\""));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user:wrong") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"TestRealm\""));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user3:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // test admin
        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"TestRealm\""));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("admin:wrong") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"TestRealm\""));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user:password") + "\r\n" +
            "\r\n");

        assertThat(response, startsWith("HTTP/1.1 403 "));
        assertThat(response, containsString("!role"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("admin:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testStrictFormDispatch()
        throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", true));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        // assertThat(response,containsString(" 302 Found"));
        // assertThat(response,containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("Cache-Control: no-cache"));
        assertThat(response, containsString("Expires"));
        assertThat(response, containsString("URI=/ctx/testLoginPage"));

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 31\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong\r\n");
        // assertThat(response,containsString("Location"));
        assertThat(response, containsString("testErrorPage"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=user0&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        // log in again as user2
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertThat(response,startsWith("HTTP/1.1 302 "));
//        assertThat(response,containsString("testLoginPage"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=user2&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        // log in again as admin
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertThat(response,startsWith("HTTP/1.1 302 "));
//        assertThat(response,containsString("testLoginPage"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=admin&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testStrictFormRedirect() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\nHost:wibble.com:8888\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));

        String session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 31\r\n" +
            "\r\n" +
            "j_username=user&j_password=wrong\r\n");
        assertThat(response, containsString("Location"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=user3&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        // log in again as user2
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("testLoginPage"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=user2&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //check user2 does not have right role to access /admin/*
        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!role"));

        //log in as user3, who doesn't have a valid role, but we are checking a constraint
        //of ** which just means they have to be authenticated
        response = _connector.getResponse("GET /ctx/starstar/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("testLoginPage"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=user3&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/starstar/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/starstar/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // log in again as admin
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
//        assertThat(response,startsWith("HTTP/1.1 302 "));
//        assertThat(response,containsString("testLoginPage"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 36\r\n" +
            "\r\n" +
            "j_username=admin&j_password=password\r\n");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/auth/info"));
        session = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testDataRedirection() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String rawResponse;

        rawResponse = _connector.getResponse("GET /ctx/data/info HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        _config.setSecurePort(8443);
        _config.setSecureScheme("https");

        rawResponse = _connector.getResponse("GET /ctx/data/info HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FOUND_302));
        String location = response.get(HttpHeader.LOCATION);
        assertThat("Location header", location, containsString(":8443/ctx/data/info"));
        assertThat("Location header", location, not(containsString("https:///")));

        _config.setSecurePort(443);
        rawResponse = _connector.getResponse("GET /ctx/data/info HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FOUND_302));
        location = response.get(HttpHeader.LOCATION);
        assertThat("Location header", location, not(containsString(":443/ctx/data/info")));

        _config.setSecurePort(8443);
        rawResponse = _connector.getResponse("GET /ctx/data/info HTTP/1.0\r\nHost: wobble.com\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FOUND_302));
        location = response.get(HttpHeader.LOCATION);
        assertThat("Location header", location, containsString("https://wobble.com:8443/ctx/data/info"));

        _config.setSecurePort(443);
        rawResponse = _connector.getResponse("GET /ctx/data/info HTTP/1.0\r\nHost: wobble.com\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FOUND_302));
        location = response.get(HttpHeader.LOCATION);
        assertThat("Location header", location, containsString("https://wobble.com/ctx/data/info"));
    }

    @Test
    public void testRoleRef() throws Exception
    {
        RoleCheckHandler check = new RoleCheckHandler();
        _security.setHandler(check);
        _security.setAuthenticator(new BasicAuthenticator());

        _server.start();

        String rawResponse;
        rawResponse = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n", 100000, TimeUnit.MILLISECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
            "\r\n", 100000, TimeUnit.MILLISECONDS);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));

        _server.stop();

        RoleRefHandler roleref = new RoleRefHandler();
        roleref.setHandler(_security.getHandler());
        _security.setHandler(roleref);
        roleref.setHandler(check);

        _server.start();

        rawResponse = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
            "\r\n", 100000, TimeUnit.MILLISECONDS);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testDeferredBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=null"));

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("admin:wrong") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=null"));

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("admin:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user=admin"));
    }

    @Test
    public void testRelaxedMethod() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/forbid/somethig HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));

        response = _connector.getResponse("POST /ctx/forbid/post HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));

        response = _connector.getResponse("GET /ctx/forbid/post HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));
    }

    @Test
    public void testUncoveredMethod() throws Exception
    {
        ConstraintMapping specificMethod = new ConstraintMapping();
        specificMethod.setMethod("GET");
        specificMethod.setPathSpec("/specific/method");
        specificMethod.setConstraint(_forbidConstraint);
        _security.addConstraintMapping(specificMethod);
        _security.setAuthenticator(new BasicAuthenticator());
        Logger.getAnonymousLogger().info("Uncovered method for /specific/method is expected");
        _server.start();

        assertThat(_security.getPathsWithUncoveredHttpMethods(), contains("/specific/method"));

        String response;
        response = _connector.getResponse("GET /ctx/specific/method HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));

        response = _connector.getResponse("POST /ctx/specific/method HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 ")); // This is so stupid, but it is the S P E C
    }

    @Test
    public void testForbidTraceAndOptions() throws Exception
    {
        ConstraintMapping forbidTrace = new ConstraintMapping();
        forbidTrace.setMethod("TRACE");
        forbidTrace.setPathSpec("/");
        forbidTrace.setConstraint(_forbidConstraint);
        ConstraintMapping allowOmitTrace = new ConstraintMapping();
        allowOmitTrace.setMethodOmissions(new String[] {"TRACE"});
        allowOmitTrace.setPathSpec("/");
        allowOmitTrace.setConstraint(_relaxConstraint);

        ConstraintMapping forbidOptions = new ConstraintMapping();
        forbidOptions.setMethod("OPTIONS");
        forbidOptions.setPathSpec("/");
        forbidOptions.setConstraint(_forbidConstraint);
        ConstraintMapping allowOmitOptions = new ConstraintMapping();
        allowOmitOptions.setMethodOmissions(new String[] {"OPTIONS"});
        allowOmitOptions.setPathSpec("/");
        allowOmitOptions.setConstraint(_relaxConstraint);

        ConstraintMapping someConstraint = new ConstraintMapping();
        someConstraint.setPathSpec("/some/constaint/*");
        someConstraint.setConstraint(_noAuthConstraint);

        _security.setConstraintMappings(new ConstraintMapping[] {forbidTrace, allowOmitTrace, forbidOptions, allowOmitOptions, someConstraint});

        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        assertThat(_security.getPathsWithUncoveredHttpMethods(), Matchers.empty());

        String response;
        response = _connector.getResponse("TRACE /ctx/some/path HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));

        response = _connector.getResponse("OPTIONS /ctx/some/path HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));

        response = _connector.getResponse("GET /ctx/some/path HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));

        response = _connector.getResponse("GET /ctx/some/constraint/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));

        response = _connector.getResponse("OPTIONS /ctx/some/constraint/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*",
        "  ",
        "\t",
        "bogus name"
    })
    public void testSetInvalidHttpMethod(String name)
    {
        ConstraintMapping mapping = new ConstraintMapping();
        assertThrows(IllegalArgumentException.class, () -> mapping.setMethod(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*",
        "  ",
        "\t",
        "bogus name"
    })
    public void testSetInvalidHttpMethodOmission(String name)
    {
        ConstraintMapping mapping = new ConstraintMapping();
        assertThrows(IllegalArgumentException.class, () -> mapping.setMethodOmissions(new String[]{name}));
    }

    @Test
    public void testSetHttpMethodOmissionWithNullEntries()
    {
        ConstraintMapping mapping = new ConstraintMapping();
        String[] names = new String[]{"GET", null, "POST"};
        assertThrows(IllegalArgumentException.class, () -> mapping.setMethodOmissions(names));
    }

    public static Stream<Arguments> servletSpecCombinedConstraintExampleCases()
    {
        return Stream.of(
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/*"
            Arguments.of("PUT", "/foo", true, true, is(empty()), is(not(UserDataConstraint.Confidential))),
            Arguments.of("GET", "/foo", false, false, null, null),
            Arguments.of("POST", "/foo", false, false, null, null),
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/acme/wholesale/*"
            Arguments.of("PUT", "/acme/wholesale/foo", true, false, contains("SALESCLERK"), is(not(UserDataConstraint.Confidential))),
            Arguments.of("GET", "/acme/wholesale/foo", true, false, containsInAnyOrder("CONTRACTOR", "SALESCLERK"), is(not(UserDataConstraint.Confidential))),
            Arguments.of("POST", "/acme/wholesale/foo", true, false, contains("CONTRACTOR"), is(UserDataConstraint.Confidential)),
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/acme/retail/*"
            Arguments.of("PUT", "/acme/retail/foo", true, true, is(empty()), is(not(UserDataConstraint.Confidential))),
            Arguments.of("GET", "/acme/retail/foo", true, false, containsInAnyOrder("CONTRACTOR", "HOMEOWNER"), is(not(UserDataConstraint.Confidential))),
            Arguments.of("POST", "/acme/retail/foo", true, false, containsInAnyOrder("CONTRACTOR", "HOMEOWNER"), is(not(UserDataConstraint.Confidential)))
        );
    }

    /**
     * Replication of Constraint combination from
     * <a href="https://jakarta.ee/specifications/servlet/5.0/jakarta-servlet-spec-5.0#combining-constraints">Jakarta Servlet Spec 13.8.1 : Combining Constraints</a>.
     */
    @ParameterizedTest
    @MethodSource("servletSpecCombinedConstraintExampleCases")
    public void testServletSpecCombinedConstraintsExamples(String httpMethod, String requestPath,
                                                           boolean expectedIsChecked,
                                                           boolean expectedForbidden,
                                                           org.hamcrest.Matcher<Set<String>> rolesMatcher,
                                                           org.hamcrest.Matcher<UserDataConstraint> dataConstraintMatcher) throws Exception
    {
        List<ConstraintMapping> mappings = new ArrayList<>();

        // Note: the path spec `/*` is known to produce the "has uncovered HTTP methods" warning, do not try to fix by changing these constraints.

        /*
        <security-constraint>
          <web-resource-collection>
            <web-resource-name>precluded methods</web-resource-name>
            <url-pattern>/*</url-pattern>
            <url-pattern>/acme/wholesale/*</url-pattern>
            <url-pattern>/acme/retail/*</url-pattern>
            <http-method-omission>GET</http-method-omission>
            <http-method-omission>POST</http-method-omission>
          </web-resource-collection>
          <auth-constraint/>
        </security-constraint>
         */
        ServletConstraint forbidConstraint = new ServletConstraint();
        forbidConstraint.setAuthenticate(true);
        forbidConstraint.setName("forbidden");
        for (String pathSpec: List.of("/*", "/acme/wholesale/*", "/acme/retail/*"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec(pathSpec);
            mapping.setMethodOmissions(new String[]{"GET", "POST"});
            mapping.setConstraint(forbidConstraint);
            mappings.add(mapping);
        }

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
        ServletConstraint wholesaleConstraint = new ServletConstraint();
        wholesaleConstraint.setAuthenticate(true);
        wholesaleConstraint.setRoles(new String[]{"SALESCLERK"});
        for (String wholesaleMethod: List.of("GET", "PUT"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/wholesale/*");
            mapping.setMethod(wholesaleMethod);
            mapping.setConstraint(wholesaleConstraint);
            mappings.add(mapping);
        }

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
        ServletConstraint wholesale2Constraint = new ServletConstraint();
        wholesale2Constraint.setAuthenticate(true);
        wholesale2Constraint.setRoles(new String[]{"CONTRACTOR"});
        wholesale2Constraint.setDataConstraint(ServletConstraint.DC_CONFIDENTIAL);
        for (String wholesale2Method: List.of("GET", "POST"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/wholesale/*");
            mapping.setMethod(wholesale2Method);
            mapping.setConstraint(wholesale2Constraint);
            mappings.add(mapping);
        }

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
        ServletConstraint retailConstraint = new ServletConstraint();
        retailConstraint.setAuthenticate(true);
        retailConstraint.setRoles(new String[]{"CONTRACTOR", "HOMEOWNER"});
        for (String retailMethod: List.of("GET", "POST"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/retail/*");
            mapping.setMethod(retailMethod);
            mapping.setConstraint(retailConstraint);
            mappings.add(mapping);
        }

        Set<String> knownRoles = Set.of("SALESCLERK", "CONTRACTOR", "HOMEOWNER");
        _security.setConstraintMappings(mappings, knownRoles);
        _server.start();

        RoleInfo roleInfo = _security.prepareConstraintInfo(requestPath, httpMethod);
        assertThat("%s %s roleInfo isChecked".formatted(httpMethod, requestPath), roleInfo.isChecked(), is(expectedIsChecked));
        if (roleInfo.isChecked())
        {
            assertThat("%s %s forbidden".formatted(httpMethod, requestPath), roleInfo.isForbidden(), is(expectedForbidden));
            assertThat("%s %s roles".formatted(httpMethod, requestPath), roleInfo.getRoles(), rolesMatcher);
            assertThat("%s %s user data constraint".formatted(httpMethod, requestPath), roleInfo.getUserDataConstraint(), dataConstraintMatcher);
        }
    }

    public static Stream<Arguments> singleForbiddenMethodOmissionCases()
    {
        return Stream.of(
            // Try some RFC9110 standardized method declarations
            Arguments.of("GET", "GET", "/test/foo", false),
            Arguments.of("GET", "PUT", "/test/foo", true),
            Arguments.of("GET", "POST", "/test/foo", true),
            Arguments.of("GET", "DELETE", "/test/foo", true),
            // Try some WebDav methods
            Arguments.of("PATCH", "PATCH", "/test/foo", false),
            Arguments.of("PATCH", "PROPPATCH", "/test/foo", true),
            Arguments.of("PATCH", "ORDERPATCH", "/test/foo", true),
            Arguments.of("PATCH", "PROPFIND", "/test/foo", true),
            Arguments.of("PATCH", "MKCOL", "/test/foo", true),
            Arguments.of("MKCOL", "MKCOL", "/test/foo", false),
            Arguments.of("PROPPATCH", "PATCH", "/test/foo", true),
            Arguments.of("PROPPATCH", "ORDERPATCH", "/test/foo", true),
            Arguments.of("PROPFIND", "PROPFIND", "/test/foo", false),
            // Try some non-standard method declarations
            Arguments.of("CorpQuery", "CorpQuery", "/test/foo", false),
            Arguments.of("CorpQuery", "GET", "/test/foo", true),
            Arguments.of("CorpQuery", "POST", "/test/foo", true)
        );
    }

    /**
     * Tests forbidden http-method-omission
     */
    @ParameterizedTest
    @MethodSource("singleForbiddenMethodOmissionCases")
    public void testSingleForbiddenMethodOmissionConstraint(String httpMethodOmission, String requestHttpMethod, String requestPath,
                                                            boolean expectedForbidden) throws Exception
    {
        ServletConstraint forbidConstraint = new ServletConstraint();
        forbidConstraint.setAuthenticate(true);
        forbidConstraint.setName("forbid");
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{httpMethodOmission});
        forbiddenMapping.setConstraint(forbidConstraint);
        _security.setConstraintMappings(List.of(forbiddenMapping));
        _server.start();

        RoleInfo roleInfo = _security.prepareConstraintInfo(requestPath, requestHttpMethod);
        boolean constraintIsChecked = expectedForbidden && !httpMethodOmission.equalsIgnoreCase(requestHttpMethod);
        assertThat("%s %s roleInfo isChecked".formatted(requestHttpMethod, requestPath), roleInfo.isChecked(), is(constraintIsChecked));
        if (roleInfo.isChecked())
        {
            assertThat("%s %s forbidden".formatted(requestHttpMethod, requestPath), roleInfo.isForbidden(), is(expectedForbidden));
        }
    }

    public static Stream<Arguments> singleAllowedMethodOmissionCases()
    {
        return Stream.of(
            // Try some RFC9110 standardized method declarations
            Arguments.of("GET", "GET", "/test/foo", false),
            Arguments.of("GET", "PUT", "/test/foo", true),
            Arguments.of("GET", "POST", "/test/foo", true),
            Arguments.of("GET", "DELETE", "/test/foo", true),
            // Try some WebDav methods
            Arguments.of("PATCH", "PATCH", "/test/foo", false),
            Arguments.of("PATCH", "PROPPATCH", "/test/foo", true),
            Arguments.of("PATCH", "ORDERPATCH", "/test/foo", true),
            Arguments.of("PATCH", "PROPFIND", "/test/foo", true),
            Arguments.of("PATCH", "MKCOL", "/test/foo", true),
            Arguments.of("MKCOL", "MKCOL", "/test/foo", false),
            Arguments.of("PROPPATCH", "PATCH", "/test/foo", true),
            Arguments.of("PROPPATCH", "ORDERPATCH", "/test/foo", true),
            Arguments.of("PROPFIND", "PROPFIND", "/test/foo", false),
            // Try some non-standard method declarations
            Arguments.of("CorpQuery", "CorpQuery", "/test/foo", false),
            Arguments.of("CorpQuery", "GET", "/test/foo", true),
            Arguments.of("CorpQuery", "POST", "/test/foo", true)
        );
    }

    /**
     * Tests allowed http-method-omission
     */
    @ParameterizedTest
    @MethodSource("singleAllowedMethodOmissionCases")
    public void testSingleAllowedMethodOmissionConstraint(String httpMethodOmission, String requestHttpMethod, String requestPath, boolean expectAllowed) throws Exception
    {
        ServletConstraint allowConstraint = new ServletConstraint();
        allowConstraint.setAuthenticate(false);
        allowConstraint.setName("allowed");
        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{httpMethodOmission});
        allowedMapping.setConstraint(allowConstraint);
        _security.setConstraintMappings(List.of(allowedMapping));
        _server.start();

        RoleInfo roleInfo = _security.prepareConstraintInfo(requestPath, requestHttpMethod);
        boolean constraintIsChecked = !expectAllowed && !httpMethodOmission.equalsIgnoreCase(requestHttpMethod);
        assertThat("%s %s roleInfo isChecked".formatted(requestHttpMethod, requestPath), roleInfo.isChecked(), is(constraintIsChecked));
        if (roleInfo.isChecked())
        {
            assertThat("%s %s allowed".formatted(requestHttpMethod, requestPath), roleInfo.isForbidden(), is(not(expectAllowed)));
        }
    }

    public static Stream<Arguments> combinedAllowedForbiddenMethodOmissionConstraintsCases()
    {
        return Stream.of(
            // Neither constraint covers GET
            Arguments.of("GET", "/test/foo", false),
            // The forbidden constraint wins
            Arguments.of("PUT", "/test/foo", true),
            Arguments.of("POST", "/test/foo", true),
            Arguments.of("DELETE", "/test/foo", true)
        );
    }

    /**
     * Test of two constraints that have the same path, same http-method-omission, no roles, but different auth-constraint settings.
     */
    @ParameterizedTest
    @MethodSource("combinedAllowedForbiddenMethodOmissionConstraintsCases")
    public void testCombinedAllowedForbiddenMethodOmissionConstraints(String httpMethod, String requestPath, boolean expectedForbidden) throws Exception
    {
        // Note: these two constraints are known to produce the "has uncovered HTTP methods" warning, do not try to fix by changing these constraints.
        ServletConstraint forbidConstraint = new ServletConstraint();
        forbidConstraint.setAuthenticate(true);
        forbidConstraint.setName("forbidden");
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{"GET"});
        forbiddenMapping.setConstraint(forbidConstraint);

        ServletConstraint allowConstraint = new ServletConstraint();
        allowConstraint.setAuthenticate(false);
        allowConstraint.setName("allowed");
        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{"GET"});
        allowedMapping.setConstraint(allowConstraint);

        // This is the reverse order from testCombinedForbiddenAllowedMethodOmissionConstraints
        _security.setConstraintMappings(List.of(allowedMapping, forbiddenMapping));
        _server.start();

        RoleInfo roleInfo = _security.prepareConstraintInfo(requestPath, httpMethod);
        boolean constraintIsChecked = expectedForbidden && !"GET".equalsIgnoreCase(httpMethod);
        assertThat("%s %s roleInfo isChecked".formatted(httpMethod, requestPath), roleInfo.isChecked(), is(constraintIsChecked));
        if (roleInfo.isChecked())
        {
            assertThat("%s %s forbidden".formatted(httpMethod, requestPath), roleInfo.isForbidden(), is(expectedForbidden));
        }
    }

    /**
     * Test of two constraints that have the same path, same http-method-omission, no roles, but different auth-constraint settings.
     */
    @ParameterizedTest
    @MethodSource("combinedAllowedForbiddenMethodOmissionConstraintsCases")
    public void testCombinedForbiddenAllowedMethodOmissionConstraints(String httpMethod, String requestPath, boolean expectedForbidden) throws Exception
    {
        // Note: these two constraints are known to produce the "has uncovered HTTP methods" warning, do not try to fix by changing these constraints.
        ServletConstraint forbidConstraint = new ServletConstraint();
        forbidConstraint.setAuthenticate(true);
        forbidConstraint.setName("forbidden");
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{"GET"});
        forbiddenMapping.setConstraint(forbidConstraint);

        ServletConstraint allowConstraint = new ServletConstraint();
        allowConstraint.setAuthenticate(false);
        allowConstraint.setName("allowed");
        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{"GET"});
        allowedMapping.setConstraint(allowConstraint);

        // This is the reverse order from testCombinedAllowedForbiddenMethodOmissionConstraints
        _security.setConstraintMappings(List.of(forbiddenMapping, allowedMapping));
        _server.start();

        RoleInfo roleInfo = _security.prepareConstraintInfo(requestPath, httpMethod);
        boolean constraintIsChecked = expectedForbidden && !"GET".equalsIgnoreCase(httpMethod);
        assertThat("%s %s roleInfo isChecked".formatted(httpMethod, requestPath), roleInfo.isChecked(), is(constraintIsChecked));
        if (roleInfo.isChecked())
        {
            assertThat("%s %s forbidden".formatted(httpMethod, requestPath), roleInfo.isForbidden(), is(expectedForbidden));
        }
    }

    @Test
    public void testDefaultConstraint() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());

        ConstraintMapping forbidDefault = new ConstraintMapping();
        forbidDefault.setPathSpec("/");
        forbidDefault.setConstraint(_forbidConstraint);
        _security.addConstraintMapping(forbidDefault);

        ConstraintMapping allowRoot = new ConstraintMapping();
        allowRoot.setPathSpec("");
        allowRoot.setConstraint(_relaxConstraint);
        _security.addConstraintMapping(allowRoot);

        _server.start();
        String response;

        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/anything HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/noauth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/forbid/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));

        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"TestRealm\""));

        response = _connector.getResponse("GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    private static String authBase64(String authorization)
    {
        byte[] raw = authorization.getBytes(ISO_8859_1);
        return Base64.getEncoder().encodeToString(raw);
    }

    private class RequestHandler extends AbstractHandler
    {
        private final List<String> _acceptableUsers;
        private final List<String> _acceptableRoles;

        public RequestHandler(String[] users, String[] roles)
        {
            _acceptableUsers = Arrays.asList(users);
            _acceptableRoles = Arrays.asList(roles);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            if (request.getAuthType() == null || isAcceptableUser(request) || isInAcceptableRole(request))
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().println("URI=" + request.getRequestURI());
                String user = request.getRemoteUser();
                response.getWriter().println("user=" + user);
                if (request.getParameter("test_parameter") != null)
                    response.getWriter().println(request.getParameter("test_parameter"));
            }
            else
                response.sendError(500);
        }

        private boolean isAcceptableUser(HttpServletRequest request)
        {
            String user = request.getRemoteUser();
            if (_acceptableUsers == null)
            {
                return true;
            }

            if (user == null)
                return false;

            return _acceptableUsers.contains(user);
        }

        private boolean isInAcceptableRole(HttpServletRequest request)
        {
            if (_acceptableRoles == null)
                return true;

            for (String role : _acceptableRoles)
            {
                if (request.isUserInRole(role))
                    return true;
            }

            return false;
        }
    }

    private class ProgrammaticLoginRequestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            String action = request.getParameter("action");
            if (StringUtil.isBlank(action))
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().println("user=" + request.getRemoteUser());
                return;
            }
            else if ("loginauth".equals(action))
            {
                request.login("admin", "password");
                response.getWriter().println("userPrincipal=" + request.getUserPrincipal());
                response.getWriter().println("remoteUser=" + request.getRemoteUser());
                response.getWriter().println("authType=" + request.getAuthType());
                response.getWriter().println("auth=" + request.authenticate(response));
                return;
            }
            else if ("login".equals(action))
            {
                request.login("admin", "password");
                return;
            }
            else if ("loginfail".equals(action))
            {
                request.login("admin", "fail");
                return;
            }
            else if ("loginfaillogin".equals(action))
            {
                try
                {
                    request.login("admin", "fail");
                }
                catch (ServletException e)
                {
                    request.login("admin", "password");
                }
                return;
            }
            else if ("loginlogin".equals(action))
            {
                request.login("admin", "password");
                request.login("foo", "bar");
            }
            else if ("loginlogout".equals(action))
            {
                request.login("admin", "password");
                request.logout();
            }
            else if ("loginlogoutlogin".equals(action))
            {
                request.login("admin", "password");
                request.logout();
                request.login("user0", "password");
            }
            else if ("constraintlogin".equals(action))
            {
                String user = request.getRemoteUser();
                request.login("admin", "password");
            }
            else if ("logout".equals(action))
            {
                request.logout();
            }
            else
                response.sendError(500);
        }
    }

    private class RoleRefHandler extends HandlerWrapper
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            UserIdentityScope old = ((Request)request).getUserIdentityScope();

            UserIdentityScope scope = new UserIdentityScope()
            {
                @Override
                public ContextHandler getContextHandler()
                {
                    return null;
                }

                @Override
                public String getContextPath()
                {
                    return "/";
                }

                @Override
                public String getName()
                {
                    return "someServlet";
                }

                @Override
                public Map<String, String> getRoleRefMap()
                {
                    Map<String, String> map = new HashMap<>();
                    map.put("untranslated", "user");
                    return map;
                }
            };

            ((Request)request).setUserIdentityScope(scope);

            try
            {
                super.handle(target, baseRequest, request, response);
            }
            finally
            {
                ((Request)request).setUserIdentityScope(old);
            }
        }
    }

    private class RoleCheckHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            if (request.getAuthType() == null || "user".equals(request.getRemoteUser()) || request.isUserInRole("untranslated"))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }

    public static class Scenario
    {
        public final String rawRequest;
        public final int expectedStatus;
        public Consumer<HttpTester.Response> extraAsserts;

        public Scenario(String rawRequest, int expectedStatus)
        {
            this.rawRequest = rawRequest;
            this.expectedStatus = expectedStatus;
        }

        public Scenario(String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            this.rawRequest = rawRequest;
            this.expectedStatus = expectedStatus;
            this.extraAsserts = extraAsserts;
        }

        @Override
        public String toString()
        {
            return rawRequest;
        }
    }
}
