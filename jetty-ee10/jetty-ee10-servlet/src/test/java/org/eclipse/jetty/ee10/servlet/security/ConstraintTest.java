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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Password;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstraintTest
{
    private static final String TEST_REALM = "TestRealm";
    private Server _server;
    private LocalConnector _connector;
    private ConstraintSecurityHandler _security;
    private HttpConfiguration _config;
    private ServletContextHandler _servletContextHandler;
    private Constraint.Builder _forbidConstraint;
    private Constraint.Builder _relaxConstraint;
    private Constraint.Builder _noAuthConstraint;

    @BeforeEach
    public void setupServer()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _config = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _server.setConnectors(new Connector[]{_connector});

        _servletContextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS | ServletContextHandler.SECURITY);
        _servletContextHandler.setContextPath("/ctx");
        _server.setHandler(_servletContextHandler);

        SessionHandler sessionHandler = _servletContextHandler.getSessionHandler();
        _servletContextHandler.setHandler(sessionHandler);

        TestLoginService loginService = new TestLoginService(TEST_REALM);
        loginService.putUser("user0", new Password("password"), new String[]{});
        loginService.putUser("user", new Password("password"), new String[]{"user"});
        loginService.putUser("user2", new Password("password"), new String[]{"user"});
        loginService.putUser("admin", new Password("password"), new String[]{"user", "administrator"});
        loginService.putUser("user3", new Password("password"), new String[]{"foo"});
        loginService.putUser("user4", new Password("password"), new String[]{"A", "B", "C", "D"});
        _server.addBean(loginService);

        _security = (ConstraintSecurityHandler)_servletContextHandler.getSecurityHandler();
        _security.setConstraintMappings(getConstraintMappings(), getKnownRoles());
        sessionHandler.setHandler(_security);

        ServletHandler servletHandler = _servletContextHandler.getServletHandler();
        _security.setHandler(servletHandler);

        TestServlet testServlet = new TestServlet();
        servletHandler.addServletWithMapping(new ServletHolder("test", testServlet), "/");
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

        return knownRoles;
    }

    private List<ConstraintMapping> getConstraintMappings()
    {
        _forbidConstraint = new Constraint.Builder();
        _forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        _forbidConstraint.name("forbid");
        ConstraintMapping mapping0 = new ConstraintMapping();
        mapping0.setPathSpec("/forbid/*");
        mapping0.setConstraint(_forbidConstraint.build());

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/auth/*");
        mapping1.setConstraint(authAnyRoleConstraint.build());

        Constraint.Builder authAdminConstraint = new Constraint.Builder();
        authAdminConstraint.name("admin");
        authAdminConstraint.roles("administrator");
        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/admin/*");
        mapping2.setConstraint(authAdminConstraint.build());
        mapping2.setMethod("GET");
        ConstraintMapping mapping2o = new ConstraintMapping();
        mapping2o.setPathSpec("/admin/*");
        mapping2o.setConstraint(_forbidConstraint.build());
        mapping2o.setMethodOmissions(new String[]{"GET"});

        _relaxConstraint = new Constraint.Builder();
        _relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        _relaxConstraint.name("relax");
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/admin/relax/*");
        mapping3.setConstraint(_relaxConstraint.build());

        Constraint.Builder loginPageConstraint = new Constraint.Builder();
        loginPageConstraint.name("loginpage");
        loginPageConstraint.roles("administrator");
        ConstraintMapping mapping4 = new ConstraintMapping();
        mapping4.setPathSpec("/testLoginPage");
        mapping4.setConstraint(loginPageConstraint.build());

        _noAuthConstraint = new Constraint.Builder();
        _noAuthConstraint.authorization(Constraint.Authorization.ALLOWED);
        _noAuthConstraint.name("allow forbidden");
        ConstraintMapping mapping5 = new ConstraintMapping();
        mapping5.setPathSpec("/forbid/post");
        mapping5.setConstraint(_noAuthConstraint.build());
        mapping5.setMethod("POST");
        ConstraintMapping mapping5o = new ConstraintMapping();
        mapping5o.setPathSpec("/forbid/post");
        mapping5o.setConstraint(_forbidConstraint.build());
        mapping5o.setMethodOmissions(new String[]{"POST"});

        Constraint.Builder confidentialDataConstraint = new Constraint.Builder();
        confidentialDataConstraint.authorization(Constraint.Authorization.ALLOWED);
        confidentialDataConstraint.name("data constraint");
        confidentialDataConstraint.transport(Constraint.Transport.SECURE);
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/data/*");
        mapping6.setConstraint(confidentialDataConstraint.build());

        Constraint.Builder anyUserAuthConstraint = new Constraint.Builder();
        anyUserAuthConstraint.authorization(Constraint.Authorization.ANY_USER);
        anyUserAuthConstraint.name("** constraint");
        ConstraintMapping mapping7 = new ConstraintMapping();
        mapping7.setPathSpec("/starstar/*");
        mapping7.setConstraint(anyUserAuthConstraint.build());

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
        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("anyAuth");
        ConstraintMapping starMapping = new ConstraintMapping();
        starMapping.setPathSpec("/test/*");
        starMapping.setConstraint(authAnyRoleConstraint.build());

        //an auth-constraint with role **
        Constraint.Builder authAnyAuthConstraint = new Constraint.Builder();
        authAnyAuthConstraint.authorization(Constraint.Authorization.ANY_USER);
        authAnyAuthConstraint.name("** constraint");
        ConstraintMapping starStarMapping = new ConstraintMapping();
        starStarMapping.setPathSpec("/test/*");
        starStarMapping.setConstraint(authAnyAuthConstraint.build());

        //a relax constraint, ie no auth-constraint
        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping relaxMapping = new ConstraintMapping();
        relaxMapping.setPathSpec("/test/*");
        relaxMapping.setConstraint(relaxConstraint.build());

        //a forbidden constraint
        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping forbidMapping = new ConstraintMapping();
        forbidMapping.setPathSpec("/test/*");
        forbidMapping.setConstraint(forbidConstraint.build());

        //an auth-constraint with roles A, B
        Constraint.Builder rolesConstraint = new Constraint.Builder();
        rolesConstraint.name("admin");
        rolesConstraint.roles("A", "B");
        ConstraintMapping rolesABMapping = new ConstraintMapping();
        rolesABMapping.setPathSpec("/test/*");
        rolesABMapping.setConstraint(rolesConstraint.build());

        //an auth-constraint with roles C, C
        Constraint.Builder roles2Constraint = new Constraint.Builder();
        roles2Constraint.name("admin");
        roles2Constraint.roles("C", "D");
        ConstraintMapping rolesCDMapping = new ConstraintMapping();
        rolesCDMapping.setPathSpec("/test/*");
        rolesCDMapping.setConstraint(roles2Constraint.build());

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
        Constraint.Builder constraint = new Constraint.Builder();
        constraint.authorization(Constraint.Authorization.ALLOWED);
        constraint.name("transient");
        mapping.setConstraint(constraint.build());
        
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
        assertThat(mapping.getConstraint().getTransport(), is(Constraint.Transport.SECURE));
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-3
     *
     * <pre>
     * &#064;ServletSecurity(@HttpConstraint(EmptyRoleSemantic.DENY))
     * </pre>
     */
    @Test
    public void testSecurityElementExample133()
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(EmptyRoleSemantic.DENY);
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        Constraint constraint = mapping.getConstraint();
        assertSame(constraint.getAuthorization(), Constraint.Authorization.FORBIDDEN);
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-4
     * <pre>
     * &#064;ServletSecurity(&#064;HttpConstraint(rolesAllowed = "R1"))
     * </pre>
     */
    @Test
    public void testSecurityElementExample134()
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(TransportGuarantee.NONE, "R1");
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        assertNotSame(mapping.getConstraint().getAuthorization(), Constraint.Authorization.ALLOWED);
        assertNotNull(mapping.getConstraint().getRoles());
        assertEquals("R1", mapping.getConstraint().getRoles().stream().findFirst().orElse(null));
        assertThat(mapping.getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-5
     * <pre>
     * &#064;ServletSecurity((httpMethodConstraints = {
     * &#064;HttpMethodConstraint(value = "GET", rolesAllowed = "R1"),
     * &#064;HttpMethodConstraint(value = "POST", rolesAllowed = "R1",
     * transportGuarantee = TransportGuarantee.CONFIDENTIAL)})
     * </pre>
     */
    @Test
    public void testSecurityElementExample135()
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<>();
        methodElements.add(new HttpMethodConstraintElement("GET", new HttpConstraintElement(TransportGuarantee.NONE, "R1")));
        methodElements.add(new HttpMethodConstraintElement("POST", new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL, "R1")));
        ServletSecurityElement element = new ServletSecurityElement(methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertEquals("GET", mappings.get(0).getMethod());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertNull(mappings.get(0).getMethodOmissions());
        assertThat(mappings.get(0).getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
        assertEquals("POST", mappings.get(1).getMethod());
        assertEquals("R1", mappings.get(1).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertThat(mappings.get(1).getConstraint().getTransport(), is(Constraint.Transport.SECURE));
        assertNull(mappings.get(1).getMethodOmissions());
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-6
     * <pre>
     * &#064;ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"), httpMethodConstraints = @HttpMethodConstraint("GET"))
     * </pre>
     */
    @Test
    public void testSecurityElementExample136()
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<>();
        methodElements.add(new HttpMethodConstraintElement("GET"));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertNotNull(mappings.get(0).getMethodOmissions());
        assertEquals("GET", mappings.get(0).getMethodOmissions()[0]);
        assertNotSame(mappings.get(0).getConstraint().getAuthorization(), Constraint.Authorization.ALLOWED);
        assertEquals("R1", mappings.get(0).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertEquals("GET", mappings.get(1).getMethod());
        assertNull(mappings.get(1).getMethodOmissions());
        assertThat(mappings.get(1).getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
        assertThat(mappings.get(1).getConstraint().getAuthorization(), is(Constraint.Authorization.ALLOWED));
    }

    /**
     * Equivalent of Servlet Spec 3.1 pg 132, sec 13.4.1.1, Example 13-7
     * <pre>
     * &#064;ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"),
     * httpMethodConstraints = @HttpMethodConstraint(value="TRACE",
     * emptyRoleSemantic = EmptyRoleSemantic.DENY))
     * </pre>
     */
    @Test
    public void testSecurityElementExample137()
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<>();
        methodElements.add(new HttpMethodConstraintElement("TRACE", new HttpConstraintElement(EmptyRoleSemantic.DENY)));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertNotNull(mappings.get(0).getMethodOmissions());
        assertEquals("TRACE", mappings.get(0).getMethodOmissions()[0]);
        assertNotSame(mappings.get(0).getConstraint().getAuthorization(), Constraint.Authorization.ALLOWED);
        assertEquals("R1", mappings.get(0).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertEquals("TRACE", mappings.get(1).getMethod());
        assertNull(mappings.get(1).getMethodOmissions());
        assertThat(mappings.get(1).getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
        Constraint constraint = mappings.get(1).getConstraint();
        assertSame(constraint.getAuthorization(), Constraint.Authorization.FORBIDDEN);
    }

    @Test
    public void testUncoveredHttpMethodDetection() throws Exception
    {
        //Test no methods named
        Constraint.Builder constraint1 = new Constraint.Builder();
        constraint1.authorization(Constraint.Authorization.ANY_USER);
        constraint1.name("** constraint");
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/starstar/*");
        mapping1.setConstraint(constraint1.build());

        _security.setConstraintMappings(Collections.singletonList(mapping1));
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        Set<String> uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertTrue(uncoveredPaths.isEmpty()); //no uncovered methods

        //Test only an explicitly named method, no omissions to cover other methods
        Constraint.Builder constraint2 = new Constraint.Builder();
        constraint2.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint2.name("user constraint");
        constraint2.roles("user");
        ConstraintMapping mapping2 = new ConstraintMapping();
        mapping2.setPathSpec("/user/*");
        mapping2.setMethod("GET");
        mapping2.setConstraint(constraint2.build());

        _security.addConstraintMapping(mapping2);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertEquals(1, uncoveredPaths.size());
        assertThat("/user/*", is(in(uncoveredPaths)));

        //Test an explicitly named method with an http-method-omission to cover all other methods
        Constraint.Builder constraint2a = new Constraint.Builder();
        constraint2a.authorization(Constraint.Authorization.FORBIDDEN);
        constraint2a.name("forbid constraint");
        ConstraintMapping mapping2a = new ConstraintMapping();
        mapping2a.setPathSpec("/user/*");
        mapping2a.setMethodOmissions(new String[]{"GET"});
        mapping2a.setConstraint(constraint2a.build());

        _security.addConstraintMapping(mapping2a);
        uncoveredPaths = _security.getPathsWithUncoveredHttpMethods();
        assertNotNull(uncoveredPaths);
        assertEquals(0, uncoveredPaths.size());

        //Test an http-method-omission only
        Constraint.Builder constraint3 = new Constraint.Builder();
        constraint3.authorization(Constraint.Authorization.FORBIDDEN);
        constraint3.name("omit constraint");
        ConstraintMapping mapping3 = new ConstraintMapping();
        mapping3.setPathSpec("/omit/*");
        mapping3.setMethodOmissions(new String[]{"GET", "POST"});
        mapping3.setConstraint(constraint3.build());

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
                """
                    POST /ctx/auth/info HTTP/1.1\r
                    Host: test\r
                    Content-Length: 10\r
                    \r
                    0123456789""",
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
                """
                    POST /ctx/auth/info HTTP/1.1\r
                    Host: test\r
                    Content-Length: 10\r
                    \r
                    012345""",
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
                HttpStatus.FORBIDDEN_403, response -> assertThat(response.getContent(), containsString("!authorized"))
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

        Constraint.Builder constraint6 = new Constraint.Builder();
        constraint6.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint6.name("omit HEAD and GET");
        constraint6.roles("user");
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/omit/*");
        mapping6.setConstraint(constraint6.build());
        mapping6.setMethodOmissions(new String[]{
            "GET", "HEAD"
        }); //requests for every method except GET and HEAD must be in role "user"
        list.add(mapping6);

        Constraint.Builder constraint7 = new Constraint.Builder();
        constraint7.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint7.name("non-omitted GET");
        constraint7.roles("administrator");
        ConstraintMapping mapping7 = new ConstraintMapping();
        mapping7.setPathSpec("/omit/*");
        mapping7.setConstraint(constraint7.build());
        mapping7.setMethod("GET"); //requests for GET must be in role "admin"
        list.add(mapping7);

        Constraint.Builder constraint8 = new Constraint.Builder();
        constraint8.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint8.name("non specific");
        constraint8.roles("foo");
        ConstraintMapping mapping8 = new ConstraintMapping();
        mapping8.setPathSpec("/omit/*");
        mapping8.setConstraint(constraint8.build()); //requests for all methods must be in role "foo"
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
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
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
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] ha1;
        // calc A1 digest
        md.update("user".getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update("TestRealm".getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update(password.getBytes(ISO_8859_1));
        ha1 = md.digest();
        // calc A2 digest
        md.reset();
        md.update("GET".getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update("/ctx/auth/info".getBytes(ISO_8859_1));
        byte[] ha2 = md.digest();

        // calc digest
        // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":"
        // nc-value ":" unq(cnonce-value) ":" unq(qop-value) ":" H(A2) )
        // <">
        // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":" H(A2)
        // ) > <">

        md.update(TypeUtil.toString(ha1, 16).getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update(nonce.getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update(nc.getBytes(ISO_8859_1));
        md.update((byte)':');
        String cnonce = "1234567890";
        md.update(cnonce.getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update("auth".getBytes(ISO_8859_1));
        md.update((byte)':');
        md.update(TypeUtil.toString(ha2, 16).getBytes(ISO_8859_1));
        byte[] digest = md.digest();

        // check digest
        return TypeUtil.toString(digest, 16);
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

        //wrong password
        String digest = digest(nonce, "WRONG", "1");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=1, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));

        // right password
        digest = digest(nonce, "password", "2");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=2, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // once only
        digest = digest(nonce, "password", "2");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=2, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));

        // increasing
        digest = digest(nonce, "password", "4");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=4, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // out of order
        digest = digest(nonce, "password", "3");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=3, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        // stale
        digest = digest(nonce, "password", "5");
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Digest username=\"user\", qop=auth, cnonce=\"1234567890\", uri=\"/ctx/auth/info\", realm=\"TestRealm\", " +
            "nc=5, " +
            "nonce=\"" + nonce + "\", " +
            "response=\"" + digest + "\"\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("stale=true"));
    }

    @Test
    @Disabled("Dispatch unsupported")
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
        assertThat(response, containsString("!authorized"));
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

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!authorized"));
        assertThat(response, not(containsString("JSESSIONID=" + session)));
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

        response = _connector.getResponse("""
            POST /ctx/auth/info HTTP/1.0\r
            Content-Type: application/x-www-form-urlencoded\r
            Content-Length: 27\r
            \r
            test_parameter=test_value\r
            """);
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
        assertThat(response, containsString("!authorized"));
    }

    @Test
    public void testNonFormPostRedirectHttp10() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response = _connector.getResponse("""
            POST /ctx/auth/info HTTP/1.0\r
            Content-Type: text/plain\r
            Connection: keep-alive\r
            Content-Length: 10\r
            \r
            0123456789\r
            """);
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Connection: keep-alive"));

        response = _connector.getResponse("""
            POST /ctx/auth/info HTTP/1.0\r
            Host: localhost\r
            Content-Type: text/plain\r
            Connection: keep-alive\r
            Content-Length: 10000\r
            \r
            012345\r
            """);
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: keep-alive")));
    }

    @Test
    public void testNonFormPostRedirectHttp11() throws Exception
    {
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.start();

        String response = _connector.getResponse("""
            POST /ctx/auth/info HTTP/1.1\r
            Host: test\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            0123456789\r
            """);
        assertThat(response, containsString(" 303 See Other"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, not(containsString("Connection: close")));

        response = _connector.getResponse("""
            POST /ctx/auth/info HTTP/1.1\r
            Host: test\r
            Content-Type: text/plain\r
            Content-Length: 10\r
            \r
            012345\r
            """);
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
        assertThat(jsession, greaterThan(0));
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
        assertThat(response, containsString("!authorized"));
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

        _servletContextHandler.getServletHandler().getServlet("test").setServlet(new ProgrammaticLoginServlet());
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
        assertThat(response, containsString("!authorized"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("admin:password") + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        response = _connector.getResponse("GET /ctx/admin/relax/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    @Disabled("Dispatch unsupported")
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
        assertThat(response, containsString("!authorized"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!authorized"));

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
        assertThat(response, containsString("!authorized"));

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
        assertThat(response, containsString("http://wibble.com:8888/ctx/testLoginPage"));

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
        assertThat(response, containsString("!authorized"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("!authorized"));

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
        assertThat(response, containsString("!authorized"));

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
    public void testRoleLink() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        ServletHolder holder = _servletContextHandler.getServletHandler().getServlet("test");
        holder.setUserRoleLink("untranslated", "user");
        _server.start();

        String rawResponse = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n" +
            "Authorization: Basic " + authBase64("user2:password") + "\r\n" +
            "\r\n", 100000, TimeUnit.MILLISECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Is in untranslated role"));
    }

    @Test
    public void testDeferredBasic() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());
        _server.start();

        String response;

        response = _connector.getResponse("""
            GET /ctx/noauth/info HTTP/1.0\r
            \r
            """);
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

        response = _connector.getResponse("GET /ctx/forbid/something HTTP/1.0\r\n\r\n");
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
        specificMethod.setConstraint(_forbidConstraint.build());
        _security.addConstraintMapping(specificMethod);
        _security.setAuthenticator(new BasicAuthenticator());
        Logger.getAnonymousLogger().info("Uncovered method for /specific/method is expected");
        _server.start();
        _security.dumpStdErr();

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
        forbidTrace.setConstraint(_forbidConstraint.build());
        ConstraintMapping allowOmitTrace = new ConstraintMapping();
        allowOmitTrace.setMethodOmissions(new String[] {"TRACE"});
        allowOmitTrace.setPathSpec("/");
        allowOmitTrace.setConstraint(_relaxConstraint.build());

        ConstraintMapping forbidOptions = new ConstraintMapping();
        forbidOptions.setMethod("OPTIONS");
        forbidOptions.setPathSpec("/");
        forbidOptions.setConstraint(_forbidConstraint.build());
        ConstraintMapping allowOmitOptions = new ConstraintMapping();
        allowOmitOptions.setMethodOmissions(new String[] {"OPTIONS"});
        allowOmitOptions.setPathSpec("/");
        allowOmitOptions.setConstraint(_relaxConstraint.build());

        ConstraintMapping someConstraint = new ConstraintMapping();
        someConstraint.setPathSpec("/some/constaint/*");
        someConstraint.setConstraint(_noAuthConstraint.build());

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

    @Test
    public void testDefaultConstraint() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());

        ConstraintMapping forbidDefault = new ConstraintMapping();
        forbidDefault.setPathSpec("/");
        forbidDefault.setConstraint(_forbidConstraint.build());
        _security.addConstraintMapping(forbidDefault);

        ConstraintMapping allowRoot = new ConstraintMapping();
        allowRoot.setPathSpec("");
        allowRoot.setConstraint(_relaxConstraint.build());
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

    private static class TestServlet extends HttpServlet
    {
        private final List<String> _acceptableUsers;
        private final List<String> _acceptableRoles;

        public TestServlet()
        {
            this(new String[]{"user", "user4"}, new String[]{"user", "foo"});
        }

        public TestServlet(String[] users, String[] roles)
        {
            _acceptableUsers = Arrays.asList(users);
            _acceptableRoles = Arrays.asList(roles);
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (request.getAuthType() == null || isAcceptableUser(request) || isInAcceptableRole(request))
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().println("URI=" + request.getRequestURI());
                String user = request.getRemoteUser();
                response.getWriter().println("user=" + user);
                if (request.getParameter("test_parameter") != null)
                    response.getWriter().println(request.getParameter("test_parameter"));
                if (request.isUserInRole("untranslated"))
                    response.getWriter().println("Is in untranslated role");
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

    private static class ProgrammaticLoginServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if (StringUtil.isBlank(action))
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().println("user=" + request.getRemoteUser());
            }
            else if ("loginauth".equals(action))
            {
                request.login("admin", "password");
                response.getWriter().println("userPrincipal=" + request.getUserPrincipal());
                response.getWriter().println("remoteUser=" + request.getRemoteUser());
                response.getWriter().println("authType=" + request.getAuthType());
                response.getWriter().println("auth=" + request.authenticate(response));
            }
            else if ("login".equals(action))
            {
                request.login("admin", "password");
            }
            else if ("loginfail".equals(action))
            {
                request.login("admin", "fail");
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
                String ignored = request.getRemoteUser();
                request.login("admin", "password");
            }
            else if ("logout".equals(action))
            {
                request.logout();
            }
            else
            {
                response.sendError(500);
            }
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
