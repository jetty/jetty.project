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

package org.eclipse.jetty.ee11.servlet.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.servlet.SessionHandler;
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
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Password;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstraintTest
{
    private static final String TEST_REALM = "TestRealm";
    private Server _server;
    private LocalConnector _connector;
    private ConstraintSecurityHandler _security;
    private HttpConfiguration _config;
    private SessionHandler _sessionHandler;
    private ServletContextHandler _servletContextHandler;

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

        _sessionHandler = _servletContextHandler.getSessionHandler();
        _servletContextHandler.setHandler(_sessionHandler);

        _security = (ConstraintSecurityHandler)_servletContextHandler.getSecurityHandler();
        _sessionHandler.setHandler(_security);

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

    private TestLoginService newTestLoginService()
    {
        TestLoginService loginService = new TestLoginService(TEST_REALM);
        loginService.putUser("user0", new Password("password"), new String[]{});
        loginService.putUser("user", new Password("password"), new String[]{"user"});
        loginService.putUser("user2", new Password("password"), new String[]{"user"});
        loginService.putUser("admin", new Password("password"), new String[]{"user", "administrator"});
        loginService.putUser("user3", new Password("password"), new String[]{"foo"});
        loginService.putUser("user4", new Password("password"), new String[]{"A", "B", "C", "D"});
        return loginService;
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
        _server.addBean(newTestLoginService());
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

    private List<ConstraintMapping> getDurableConstraintMappings()
    {
        List<ConstraintMapping> durableMappings = new ArrayList<>();

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        durableMappings.add(mappingForbid);

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuth = new ConstraintMapping();
        mappingAuth.setPathSpec("/auth/*");
        mappingAuth.setConstraint(authAnyRoleConstraint.build());
        durableMappings.add(mappingAuth);

        Constraint.Builder authAdminConstraint = new Constraint.Builder();
        authAdminConstraint.name("admin");
        authAdminConstraint.roles("administrator");
        ConstraintMapping mappingAdmin = new ConstraintMapping();
        mappingAdmin.setPathSpec("/admin/*");
        mappingAdmin.setConstraint(authAdminConstraint.build());
        mappingAdmin.setMethod("GET");
        ConstraintMapping mappingAdminOmission = new ConstraintMapping();
        mappingAdminOmission.setPathSpec("/admin/*");
        mappingAdminOmission.setConstraint(forbidConstraint.build());
        mappingAdminOmission.setMethodOmissions(new String[]{"GET"});
        durableMappings.add(mappingAdmin);
        durableMappings.add(mappingAdminOmission);

        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping mappingAdminRelax = new ConstraintMapping();
        mappingAdminRelax.setPathSpec("/admin/relax/*");
        mappingAdminRelax.setConstraint(relaxConstraint.build());
        durableMappings.add(mappingAdminRelax);

        Constraint.Builder loginPageConstraint = new Constraint.Builder();
        loginPageConstraint.name("loginpage");
        loginPageConstraint.roles("administrator");
        ConstraintMapping mappingTestLoginPage = new ConstraintMapping();
        mappingTestLoginPage.setPathSpec("/testLoginPage");
        mappingTestLoginPage.setConstraint(loginPageConstraint.build());
        durableMappings.add(mappingTestLoginPage);

        Constraint.Builder noAuthConstraint = new Constraint.Builder();
        noAuthConstraint.authorization(Constraint.Authorization.ALLOWED);
        noAuthConstraint.name("allow forbidden");
        ConstraintMapping mappingAllowForbiddenPost = new ConstraintMapping();
        mappingAllowForbiddenPost.setPathSpec("/forbid/post");
        mappingAllowForbiddenPost.setConstraint(noAuthConstraint.build());
        mappingAllowForbiddenPost.setMethod("POST");
        ConstraintMapping mappingAllowForbiddenPostOmission = new ConstraintMapping();
        mappingAllowForbiddenPostOmission.setPathSpec("/forbid/post");
        mappingAllowForbiddenPostOmission.setConstraint(forbidConstraint.build());
        mappingAllowForbiddenPostOmission.setMethodOmissions(new String[]{"POST"});
        durableMappings.add(mappingAllowForbiddenPost);
        durableMappings.add(mappingAllowForbiddenPostOmission);

        Constraint.Builder confidentialDataConstraint = new Constraint.Builder();
        confidentialDataConstraint.authorization(Constraint.Authorization.ALLOWED);
        confidentialDataConstraint.name("data constraint");
        confidentialDataConstraint.transport(Constraint.Transport.SECURE);
        ConstraintMapping mappingData = new ConstraintMapping();
        mappingData.setPathSpec("/data/*");
        mappingData.setConstraint(confidentialDataConstraint.build());
        durableMappings.add(mappingData);

        Constraint.Builder anyUserAuthConstraint = new Constraint.Builder();
        anyUserAuthConstraint.authorization(Constraint.Authorization.ANY_USER);
        anyUserAuthConstraint.name("** constraint");
        ConstraintMapping mappingStarStar = new ConstraintMapping();
        mappingStarStar.setPathSpec("/starstar/*");
        mappingStarStar.setConstraint(anyUserAuthConstraint.build());
        durableMappings.add(mappingStarStar);

        return durableMappings;
    }

    /**
     * Test that constraint mappings added before the context starts are
     * retained, but those that are added after the context starts are not.
     */
    @Test
    public void testDurableConstraints() throws Exception
    {
        _security.setConstraintMappings(getDurableConstraintMappings(), Set.of("user", "administrator"));

        List<ConstraintMapping> mappings =  _security.getConstraintMappings();
        assertThat("before start", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.start();
        
        mappings =  _security.getConstraintMappings();
        assertThat("after start", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.stop();
        
        //After a stop, just the durable mappings are left
        mappings = _security.getConstraintMappings();
        assertThat("after stop", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        _server.start();
        
        //Verify the constraints are just the durables
        mappings = _security.getConstraintMappings();
        assertThat("after restart", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
        //Add a non-durable constraint
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/xxxx/*");
        Constraint.Builder constraint = new Constraint.Builder();
        constraint.authorization(Constraint.Authorization.ALLOWED);
        constraint.name("transient");
        mapping.setConstraint(constraint.build());
        
        _security.addConstraintMapping(mapping);
        
        mappings = _security.getConstraintMappings();
        assertThat("after addition", getDurableConstraintMappings().size() + 1, Matchers.equalTo(mappings.size()));
        
        _server.stop();
        _server.start();
        
        //After a stop, only the durable mappings remain
        mappings = _security.getConstraintMappings();
        assertThat("after addition", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        
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
        _security.setConstraintMappings(getDurableConstraintMappings());
        mappings = _security.getConstraintMappings();
        assertThat("durables lost", getDurableConstraintMappings().size(), Matchers.equalTo(mappings.size()));
        _server.stop();
        mappings = _security.getConstraintMappings();
        assertThat("no mappings", 0, Matchers.equalTo(mappings.size()));
    }
    
    /**
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-1</p>
     *
     * <pre>{@code
     *     @ServletSecurity
     *     public class Example1 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_1()
    {
        ServletSecurityElement element = new ServletSecurityElement();
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertTrue(mappings.isEmpty());
    }

    /**
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-2</p>
     *
     * <pre>{@code
     *     @ServletSecurity(@HttpConstraint(transportGuarantee = TransportGuarantee.CONFIDENTIAL))
     *     public class Example2 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_2()
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
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-3</p>
     *
     * <pre>{@code
     *     @ServletSecurity(@HttpConstraint(EmptyRoleSemantic.DENY))
     *     public class Example3 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_3()
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(EmptyRoleSemantic.DENY);
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        Constraint constraint = mapping.getConstraint();
        assertSame(Constraint.Authorization.FORBIDDEN, constraint.getAuthorization());
    }

    /**
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-4</p>
     *
     * <pre>{@code
     *     @ServletSecurity(@HttpConstraint(rolesAllowed = "R1"))
     *     public class Example4 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_4()
    {
        HttpConstraintElement httpConstraintElement = new HttpConstraintElement(TransportGuarantee.NONE, "R1");
        ServletSecurityElement element = new ServletSecurityElement(httpConstraintElement);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(1, mappings.size());
        ConstraintMapping mapping = mappings.get(0);
        assertNotSame(Constraint.Authorization.ALLOWED, mapping.getConstraint().getAuthorization());
        assertNotNull(mapping.getConstraint().getRoles());
        assertEquals("R1", mapping.getConstraint().getRoles().stream().findFirst().orElse(null));
        assertThat(mapping.getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
    }

    /**
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-5</p>
     *
     * <pre>{@code
     *     @ServletSecurity((httpMethodConstraints = {
     *         @HttpMethodConstraint(value = "GET", rolesAllowed = "R1"),
     *         @HttpMethodConstraint(value = "POST", rolesAllowed = "R1",
     *                               transportGuarantee = TransportGuarantee.CONFIDENTIAL)
     *     })
     *     public class Example5 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_5()
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
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-6</p>
     *
     * <pre>{@code
     *     @ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"),
     *                      httpMethodConstraints = @HttpMethodConstraint("GET"))
     *     public class Example6 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_6()
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<>();
        methodElements.add(new HttpMethodConstraintElement("GET"));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertNotNull(mappings.get(0).getMethodOmissions());
        assertEquals("GET", mappings.get(0).getMethodOmissions()[0]);
        assertNotSame(Constraint.Authorization.ALLOWED, mappings.get(0).getConstraint().getAuthorization());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertEquals("GET", mappings.get(1).getMethod());
        assertNull(mappings.get(1).getMethodOmissions());
        assertThat(mappings.get(1).getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
        assertThat(mappings.get(1).getConstraint().getAuthorization(), is(Constraint.Authorization.ALLOWED));
    }

    /**
     * <p>Equivalent of Jakarta Servlet Spec, sec 13.4.1.1, Example 13-7</p>
     *
     * <pre>{@code
     *     @ServletSecurity(value = @HttpConstraint(rolesAllowed = "R1"),
     *                      httpMethodConstraints = @HttpMethodConstraint(value="TRACE",
     *                      emptyRoleSemantic = EmptyRoleSemantic.DENY))
     *     public class Example7 extends HttpServlet { }
     * }</pre>
     *
     * @see <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#examples">Jakarta Servlet Spec 6.1, sec 13.4.1.1: Examples</a>
     */
    @Test
    public void testSecurityElementExample13_7()
    {
        List<HttpMethodConstraintElement> methodElements = new ArrayList<>();
        methodElements.add(new HttpMethodConstraintElement("TRACE", new HttpConstraintElement(EmptyRoleSemantic.DENY)));
        ServletSecurityElement element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "R1"), methodElements);
        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath("foo", "/foo/*", element);
        assertFalse(mappings.isEmpty());
        assertEquals(2, mappings.size());
        assertNotNull(mappings.get(0).getMethodOmissions());
        assertEquals("TRACE", mappings.get(0).getMethodOmissions()[0]);
        assertNotSame(Constraint.Authorization.ALLOWED, mappings.get(0).getConstraint().getAuthorization());
        assertEquals("R1", mappings.get(0).getConstraint().getRoles().stream().findFirst().orElse(null));
        assertEquals("TRACE", mappings.get(1).getMethod());
        assertNull(mappings.get(1).getMethodOmissions());
        assertThat(mappings.get(1).getConstraint().getTransport(), not(is(Constraint.Transport.SECURE)));
        Constraint constraint = mappings.get(1).getConstraint();
        assertSame(Constraint.Authorization.FORBIDDEN, constraint.getAuthorization());
    }

    @Test
    public void testUncoveredHttpMethodDetection() throws Exception
    {
        // Test no methods named
        Constraint.Builder constraint1 = new Constraint.Builder();
        constraint1.authorization(Constraint.Authorization.ANY_USER);
        constraint1.name("** constraint");
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/starstar/*");
        mapping1.setConstraint(constraint1.build());

        _security.setConstraintMappings(List.of(mapping1));
        _security.setAuthenticator(new BasicAuthenticator());
        _server.addBean(newTestLoginService());
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
                HttpStatus.FORBIDDEN_403, response -> assertThat(response.getContent(), containsString("Forbidden"))
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
        List<ConstraintMapping> list = new ArrayList<>();

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        list.add(mappingForbid);

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuth = new ConstraintMapping();
        mappingAuth.setPathSpec("/auth/*");
        mappingAuth.setConstraint(authAnyRoleConstraint.build());
        list.add(mappingAuth);

        Constraint.Builder authAdminConstraint = new Constraint.Builder();
        authAdminConstraint.name("admin");
        authAdminConstraint.roles("administrator");
        ConstraintMapping mappingAdmin = new ConstraintMapping();
        mappingAdmin.setPathSpec("/admin/*");
        mappingAdmin.setConstraint(authAdminConstraint.build());
        mappingAdmin.setMethod("GET");
        ConstraintMapping mappingAdminOmission = new ConstraintMapping();
        mappingAdminOmission.setPathSpec("/admin/*");
        mappingAdminOmission.setConstraint(forbidConstraint.build());
        mappingAdminOmission.setMethodOmissions(new String[]{"GET"});
        list.add(mappingAdmin);
        list.add(mappingAdminOmission);

        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping mappingAdminRelax = new ConstraintMapping();
        mappingAdminRelax.setPathSpec("/admin/relax/*");
        mappingAdminRelax.setConstraint(relaxConstraint.build());
        list.add(mappingAdminRelax);

        Constraint.Builder constraint6 = new Constraint.Builder();
        constraint6.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint6.name("omit HEAD and GET");
        constraint6.roles("user");
        ConstraintMapping mappingOmitMethodOmission = new ConstraintMapping();
        mappingOmitMethodOmission.setPathSpec("/omit/*");
        mappingOmitMethodOmission.setConstraint(constraint6.build());
        mappingOmitMethodOmission.setMethodOmissions(new String[]{
            "GET", "HEAD"
        }); //requests for every method except GET and HEAD must be in role "user"
        list.add(mappingOmitMethodOmission);

        Constraint.Builder constraint7 = new Constraint.Builder();
        constraint7.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint7.name("non-omitted GET");
        constraint7.roles("administrator");
        ConstraintMapping mappingOmitGet = new ConstraintMapping();
        mappingOmitGet.setPathSpec("/omit/*");
        mappingOmitGet.setConstraint(constraint7.build());
        mappingOmitGet.setMethod("GET"); //requests for GET must be in role "admin"
        list.add(mappingOmitGet);

        Constraint.Builder constraint8 = new Constraint.Builder();
        constraint8.authorization(Constraint.Authorization.SPECIFIC_ROLE);
        constraint8.name("non specific");
        constraint8.roles("foo");
        ConstraintMapping mappingOmit = new ConstraintMapping();
        mappingOmit.setPathSpec("/omit/*");
        mappingOmit.setConstraint(constraint8.build()); //requests for all methods must be in role "foo"
        list.add(mappingOmit);

        Set<String> knownRoles = Set.of("user", "administrator", "foo");

        _security.setConstraintMappings(list, knownRoles);

        _server.addBean(newTestLoginService());
        _security.setAuthenticator(new BasicAuthenticator());
        try
        {
            _server.start();
            String rawResponse = _connector.getResponse(scenario.rawRequest);
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(rawResponse), scenario.rawRequest.startsWith("HEAD "));
            assertNotNull(response);
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

        String rsp = TypeUtil.toString(ha1, 16) + ":" + nonce + ":" + nc +
            ":1234567890:auth:" + TypeUtil.toString(ha2, 16);
        return TypeUtil.toString(md.digest(rsp.getBytes(UTF_8)), 16);
    }

    @Test
    public void testDigest() throws Exception
    {
        List<ConstraintMapping> constraintMappings = new ArrayList<>();

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        constraintMappings.add(mappingForbid);

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuth = new ConstraintMapping();
        mappingAuth.setPathSpec("/auth/*");
        mappingAuth.setConstraint(authAnyRoleConstraint.build());
        constraintMappings.add(mappingAuth);

        Set<String> knownRoles = Set.of("user", "administrator");
        _security.setConstraintMappings(constraintMappings, knownRoles);

        DigestAuthenticator authenticator = new DigestAuthenticator();
        authenticator.setMaxNonceCount(5);
        _security.setAuthenticator(authenticator);
        _server.addBean(newTestLoginService());
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

    private void setupTestFormConstraintMappings()
    {
        Constraint.Builder loginPageConstraint = new Constraint.Builder();
        loginPageConstraint.name("loginpage");
        loginPageConstraint.roles("administrator");
        ConstraintMapping mappingTestLoginPage = new ConstraintMapping();
        mappingTestLoginPage.setPathSpec("/testLoginPage");
        mappingTestLoginPage.setConstraint(loginPageConstraint.build());

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuth = new ConstraintMapping();
        mappingAuth.setPathSpec("/auth/*");
        mappingAuth.setConstraint(authAnyRoleConstraint.build());

        Constraint.Builder authAdminConstraint = new Constraint.Builder();
        authAdminConstraint.name("admin");
        authAdminConstraint.roles("administrator");
        ConstraintMapping mappingAdmin = new ConstraintMapping();
        mappingAdmin.setPathSpec("/admin/*");
        mappingAdmin.setConstraint(authAdminConstraint.build());
        mappingAdmin.setMethod("GET");
        ConstraintMapping mappingAdminOmission = new ConstraintMapping();
        mappingAdminOmission.setPathSpec("/admin/*");
        mappingAdminOmission.setConstraint(forbidConstraint.build());
        mappingAdminOmission.setMethodOmissions(new String[]{"GET"});

        Constraint.Builder noAuthConstraint = new Constraint.Builder();
        noAuthConstraint.authorization(Constraint.Authorization.ALLOWED);
        noAuthConstraint.name("allow forbidden");
        ConstraintMapping mappingAllowForbidPost = new ConstraintMapping();
        mappingAllowForbidPost.setPathSpec("/forbid/post");
        mappingAllowForbidPost.setConstraint(noAuthConstraint.build());
        mappingAllowForbidPost.setMethod("POST");
        ConstraintMapping mappingAllowForbidPostOmission = new ConstraintMapping();
        mappingAllowForbidPostOmission.setPathSpec("/forbid/post");
        mappingAllowForbidPostOmission.setConstraint(forbidConstraint.build());
        mappingAllowForbidPostOmission.setMethodOmissions(new String[]{"POST"});

        Constraint.Builder anyUserAuthConstraint = new Constraint.Builder();
        anyUserAuthConstraint.authorization(Constraint.Authorization.ANY_USER);
        anyUserAuthConstraint.name("** constraint");
        ConstraintMapping mappingStarStar = new ConstraintMapping();
        mappingStarStar.setPathSpec("/starstar/*");
        mappingStarStar.setConstraint(anyUserAuthConstraint.build());

        List<ConstraintMapping> mappings = List.of(
            mappingTestLoginPage,
            mappingForbid,
            mappingAuth,
            mappingAdmin,
            mappingAdminOmission,
            mappingAllowForbidPost,
            mappingAllowForbidPostOmission,
            mappingStarStar);
        Set<String> knownRoles = Set.of("user", "administrator");
        _security.setConstraintMappings(mappings, knownRoles);
    }

    @Test
    public void testFormDispatch() throws Exception
    {
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", true));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));
    }

    @Test
    public void testFormRedirect() throws Exception
    {
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));
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

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mapping1 = new ConstraintMapping();
        mapping1.setPathSpec("/auth/*");
        mapping1.setConstraint(authAnyRoleConstraint.build());

        Set<String> knownRoles = Set.of("user", "administrator", "foo");
        _security.setConstraintMappings(List.of(mapping1), knownRoles);

        _server.addBean(newTestLoginService());
        // Use a FormAuthenticator as an example of session authentication
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));

        _sessionHandler.setMaxInactiveInterval(UNAUTH_SECONDS);
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
            assertNull(_sessionHandler.getManagedSession(sessionId));
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

        ManagedSession session = _sessionHandler.getManagedSession(sessionId);
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
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));
    }

    @Test
    public void testNonFormPostRedirectHttp10() throws Exception
    {
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));
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

        setupTestFormConstraintMappings();

        _servletContextHandler.getServletHandler().getServlet("test").setServlet(new ProgrammaticLoginServlet());
        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        List<ConstraintMapping> constraintMappings = new ArrayList<>();

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuthAnyRole = new ConstraintMapping();
        mappingAuthAnyRole.setPathSpec("/auth/*");
        mappingAuthAnyRole.setConstraint(authAnyRoleConstraint.build());
        constraintMappings.add(mappingAuthAnyRole);

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        constraintMappings.add(mappingForbid);

        Constraint.Builder authAdminConstraint = new Constraint.Builder();
        authAdminConstraint.name("admin");
        authAdminConstraint.roles("administrator");
        ConstraintMapping mappingAuthAdmin = new ConstraintMapping();
        mappingAuthAdmin.setPathSpec("/admin/*");
        mappingAuthAdmin.setConstraint(authAdminConstraint.build());
        mappingAuthAdmin.setMethod("GET");
        ConstraintMapping mappingAuthAdminOmission = new ConstraintMapping();
        mappingAuthAdminOmission.setPathSpec("/admin/*");
        mappingAuthAdminOmission.setConstraint(forbidConstraint.build());
        mappingAuthAdminOmission.setMethodOmissions(new String[]{"GET"});
        constraintMappings.add(mappingAuthAdmin);
        constraintMappings.add(mappingAuthAdminOmission);

        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping mappingAdminRelax = new ConstraintMapping();
        mappingAdminRelax.setPathSpec("/admin/relax/*");
        mappingAdminRelax.setConstraint(relaxConstraint.build());
        constraintMappings.add(mappingAdminRelax);

        Set<String> knownRoles = Set.of("user", "administrator");
        _security.setConstraintMappings(constraintMappings, knownRoles);

        _security.setAuthenticator(new BasicAuthenticator());
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));

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
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", true));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("Forbidden"));

        // log in again as user2
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
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
        assertThat(response, containsString("Forbidden"));

        // log in again as admin
        response = _connector.getResponse("GET /ctx/auth/info HTTP/1.0\r\n\r\n");
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
        setupTestFormConstraintMappings();

        _security.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _server.addBean(newTestLoginService());
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
        assertThat(response, containsString("Forbidden"));

        response = _connector.getResponse("GET /ctx/admin/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + session + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("Forbidden"));

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
        assertThat(response, containsString("Forbidden"));

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
        Constraint.Builder confidentialDataConstraint = new Constraint.Builder();
        confidentialDataConstraint.authorization(Constraint.Authorization.ALLOWED);
        confidentialDataConstraint.name("data constraint");
        confidentialDataConstraint.transport(Constraint.Transport.SECURE);
        ConstraintMapping mapping6 = new ConstraintMapping();
        mapping6.setPathSpec("/data/*");
        mapping6.setConstraint(confidentialDataConstraint.build());

        Set<String> knownRoles = Set.of("user", "administrator");
        _security.setConstraintMappings(List.of(mapping6), knownRoles);

        _security.setAuthenticator(new BasicAuthenticator());
        _server.addBean(newTestLoginService());
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
        _server.addBean(newTestLoginService());
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
        _server.addBean(newTestLoginService());
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
        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(mappingForbid);

        Constraint.Builder noAuthConstraint = new Constraint.Builder();
        noAuthConstraint.authorization(Constraint.Authorization.ALLOWED);
        noAuthConstraint.name("allow forbidden");
        ConstraintMapping mappingAllowForbidPost = new ConstraintMapping();
        mappingAllowForbidPost.setPathSpec("/forbid/post");
        mappingAllowForbidPost.setConstraint(noAuthConstraint.build());
        mappingAllowForbidPost.setMethod("POST");
        ConstraintMapping mappingAllowForbidPostOmission = new ConstraintMapping();
        mappingAllowForbidPostOmission.setPathSpec("/forbid/post");
        mappingAllowForbidPostOmission.setConstraint(forbidConstraint.build());
        mappingAllowForbidPostOmission.setMethodOmissions(new String[]{"POST"});
        _security.addConstraintMapping(mappingAllowForbidPost);
        _security.addConstraintMapping(mappingAllowForbidPostOmission);

        _security.setAuthenticator(new BasicAuthenticator());
        _server.addBean(newTestLoginService());
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
        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(mappingForbid);

        ConstraintMapping mappingSpecificMethod = new ConstraintMapping();
        mappingSpecificMethod.setMethod("GET");
        mappingSpecificMethod.setPathSpec("/specific/method");
        mappingSpecificMethod.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(mappingSpecificMethod);

        _security.setAuthenticator(new BasicAuthenticator());
        LoggerFactory.getLogger(ConstraintTest.class).info("Uncovered method for /specific/method is expected");
        _server.addBean(newTestLoginService());
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
        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(mappingForbid);

        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping mappingAdminRelax = new ConstraintMapping();
        mappingAdminRelax.setPathSpec("/admin/relax/*");
        mappingAdminRelax.setConstraint(relaxConstraint.build());
        _security.addConstraintMapping(mappingAdminRelax);

        Constraint.Builder noAuthConstraint = new Constraint.Builder();
        noAuthConstraint.authorization(Constraint.Authorization.ALLOWED);
        noAuthConstraint.name("allow forbidden");
        ConstraintMapping mappingAllowForbidPost = new ConstraintMapping();
        mappingAllowForbidPost.setPathSpec("/forbid/post");
        mappingAllowForbidPost.setConstraint(noAuthConstraint.build());
        mappingAllowForbidPost.setMethod("POST");
        ConstraintMapping mappingAllowForbidPostOmission = new ConstraintMapping();
        mappingAllowForbidPostOmission.setPathSpec("/forbid/post");
        mappingAllowForbidPostOmission.setConstraint(forbidConstraint.build());
        mappingAllowForbidPostOmission.setMethodOmissions(new String[]{"POST"});
        _security.addConstraintMapping(mappingAllowForbidPost);
        _security.addConstraintMapping(mappingAllowForbidPostOmission);

        ConstraintMapping forbidTrace = new ConstraintMapping();
        forbidTrace.setMethod("TRACE");
        forbidTrace.setPathSpec("/");
        forbidTrace.setConstraint(forbidConstraint.build());
        ConstraintMapping allowOmitTrace = new ConstraintMapping();
        allowOmitTrace.setMethodOmissions(new String[] {"TRACE"});
        allowOmitTrace.setPathSpec("/");
        allowOmitTrace.setConstraint(relaxConstraint.build());

        ConstraintMapping forbidOptions = new ConstraintMapping();
        forbidOptions.setMethod("OPTIONS");
        forbidOptions.setPathSpec("/");
        forbidOptions.setConstraint(forbidConstraint.build());
        ConstraintMapping allowOmitOptions = new ConstraintMapping();
        allowOmitOptions.setMethodOmissions(new String[] {"OPTIONS"});
        allowOmitOptions.setPathSpec("/");
        allowOmitOptions.setConstraint(relaxConstraint.build());

        ConstraintMapping someConstraint = new ConstraintMapping();
        someConstraint.setPathSpec("/some/constaint/*");
        someConstraint.setConstraint(noAuthConstraint.build());

        // Intentionally using older setConstraintMappings(ConstraintMapping[]) to maintain test coverage.
        // Do not convert to List<ConstraintMapping> here.
        _security.setConstraintMappings(new ConstraintMapping[] {forbidTrace, allowOmitTrace, forbidOptions, allowOmitOptions, someConstraint});

        _security.setAuthenticator(new BasicAuthenticator());
        _server.addBean(newTestLoginService());
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

    public static Stream<Arguments> jakartaSpecCombinedConstraintExampleCases()
    {
        return Stream.of(
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/*"
            Arguments.of("PUT", "/foo", true, is(empty()), is(Constraint.Authorization.FORBIDDEN), is(not(Constraint.Transport.SECURE))),
            Arguments.of("GET", "/foo", false, null, null, null),
            Arguments.of("POST", "/foo", false, null, null, null),
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/acme/wholesale/*"
            Arguments.of("PUT", "/acme/wholesale/foo", true, contains("SALESCLERK"), is(Constraint.Authorization.SPECIFIC_ROLE), is(not(Constraint.Transport.SECURE))),
            Arguments.of("GET", "/acme/wholesale/foo", true, containsInAnyOrder("CONTRACTOR", "SALESCLERK"), is(Constraint.Authorization.SPECIFIC_ROLE), is(not(Constraint.Transport.SECURE))),
            Arguments.of("POST", "/acme/wholesale/foo", true, contains("CONTRACTOR"), is(Constraint.Authorization.SPECIFIC_ROLE), is(Constraint.Transport.SECURE)),
            // Test the Table 13-4 "Security Constraint Table" url-pattern of "/acme/retail/*"
            Arguments.of("PUT", "/acme/retail/foo", true, is(empty()), is(Constraint.Authorization.FORBIDDEN), is(not(Constraint.Transport.SECURE))),
            Arguments.of("GET", "/acme/retail/foo", true, containsInAnyOrder("CONTRACTOR", "HOMEOWNER"), is(Constraint.Authorization.SPECIFIC_ROLE), is(not(Constraint.Transport.SECURE))),
            Arguments.of("POST", "/acme/retail/foo", true, containsInAnyOrder("CONTRACTOR", "HOMEOWNER"), is(Constraint.Authorization.SPECIFIC_ROLE), is(not(Constraint.Transport.SECURE)))
        );
    }

    /**
     * Replication of Constraint combination from
     * <a href="https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1#combining-constraints">Jakarta Servlet Spec 13.8.1 : Combining Constraints</a>.
     */
    @ParameterizedTest
    @MethodSource("jakartaSpecCombinedConstraintExampleCases")
    public void testJakartaSpecCombinedConstraintsExamples(String httpMethod, String requestPath,
                                                           boolean coveredByConstraint,
                                                           org.hamcrest.Matcher<Set<String>> rolesMatcher,
                                                           org.hamcrest.Matcher<Constraint.Authorization> authorizationMatcher,
                                                           org.hamcrest.Matcher<Constraint.Transport> transportMatcher) throws Exception
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
        for (String pathSpec: List.of("/*", "/acme/wholesale/*", "/acme/retail/*"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec(pathSpec);
            mapping.setMethodOmissions(new String[]{"GET", "POST"});
            mapping.setConstraint(Constraint.FORBIDDEN);
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
        Constraint.Builder wholesaleConstraint = new Constraint.Builder();
        wholesaleConstraint.name("salesclerk");
        wholesaleConstraint.roles("SALESCLERK");
        for (String wholesaleMethod: List.of("GET", "PUT"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/wholesale/*");
            mapping.setMethod(wholesaleMethod);
            mapping.setConstraint(wholesaleConstraint.build());
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
        Constraint.Builder wholesale2Constraint = new Constraint.Builder();
        wholesale2Constraint.name("contractor");
        wholesale2Constraint.roles("CONTRACTOR");
        wholesale2Constraint.transport(Constraint.Transport.SECURE);
        for (String wholesale2Method: List.of("GET", "POST"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/wholesale/*");
            mapping.setMethod(wholesale2Method);
            mapping.setConstraint(wholesale2Constraint.build());
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
        Constraint.Builder retailConstraint = new Constraint.Builder();
        retailConstraint.roles("CONTRACTOR", "HOMEOWNER");
        for (String retailMethod: List.of("GET", "POST"))
        {
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setPathSpec("/acme/retail/*");
            mapping.setMethod(retailMethod);
            mapping.setConstraint(retailConstraint.build());
            mappings.add(mapping);
        }

        Set<String> knownRoles = Set.of("SALESCLERK", "CONTRACTOR", "HOMEOWNER");
        _security.setConstraintMappings(mappings, knownRoles);
        _server.start();

        Constraint constraint = _security.getConstraint(requestPath, httpMethod);
        if (!coveredByConstraint)
            assertThat("%s %s constraint not covered".formatted(httpMethod, requestPath), constraint, nullValue());
        else
        {
            assertThat("%s %s roles".formatted(httpMethod, requestPath), constraint.getRoles(), rolesMatcher);
            assertThat("%s %s authorization".formatted(httpMethod, requestPath), constraint.getAuthorization(), authorizationMatcher);
            assertThat("%s %s transport".formatted(httpMethod, requestPath), constraint.getTransport(), transportMatcher);
        }
    }

    public static Stream<Arguments> singleForbiddenMethodOmissionCases()
    {
        return Stream.of(
            // Try some RFC9110 standardized method declarations
            Arguments.of("GET", "GET", "/test/foo", null),
            Arguments.of("GET", "PUT", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("GET", "POST", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("GET", "DELETE", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            // Try some WebDav methods
            Arguments.of("PATCH", "PATCH", "/test/foo", null),
            Arguments.of("PATCH", "PROPPATCH", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("PATCH", "ORDERPATCH", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("PATCH", "PROPFIND", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("PATCH", "MKCOL", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("MKCOL", "MKCOL", "/test/foo", null),
            Arguments.of("PROPPATCH", "PATCH", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("PROPPATCH", "ORDERPATCH", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("PROPFIND", "PROPFIND", "/test/foo", null),
            // Try some non-standard method declarations
            Arguments.of("CorpQuery", "CorpQuery", "/test/foo", null),
            Arguments.of("CorpQuery", "GET", "/test/foo", is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("CorpQuery", "POST", "/test/foo", is(Constraint.Authorization.FORBIDDEN))
        );
    }

    /**
     * Tests forbidden http-method-omission
     */
    @ParameterizedTest
    @MethodSource("singleForbiddenMethodOmissionCases")
    public void testSingleForbiddenMethodOmissionConstraint(String httpMethodOmission, String requestHttpMethod, String requestPath,
                                     org.hamcrest.Matcher<Constraint.Authorization> authorizationMatcher) throws Exception
    {
        boolean coveredByConstraint = !httpMethodOmission.equalsIgnoreCase(requestHttpMethod);

        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{httpMethodOmission});
        forbiddenMapping.setConstraint(Constraint.FORBIDDEN);
        _security.setConstraintMappings(List.of(forbiddenMapping));
        _server.start();

        Constraint constraint = _security.getConstraint(requestPath, requestHttpMethod);
        if (!coveredByConstraint)
            assertThat("%s %s constraint not covered".formatted(requestHttpMethod, requestPath), constraint, nullValue());
        else
            assertThat("%s %s authorization".formatted(requestHttpMethod, requestPath), constraint.getAuthorization(), authorizationMatcher);
    }

    public static Stream<Arguments> singleAllowedMethodOmissionCases()
    {
        return Stream.of(
            // Try some RFC9110 standardized method declarations
            Arguments.of("GET", "GET", "/test/foo", null),
            Arguments.of("GET", "PUT", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("GET", "POST", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("GET", "DELETE", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            // Try some WebDav methods
            Arguments.of("PATCH", "PATCH", "/test/foo", null),
            Arguments.of("PATCH", "PROPPATCH", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("PATCH", "ORDERPATCH", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("PATCH", "PROPFIND", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("PATCH", "MKCOL", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("MKCOL", "MKCOL", "/test/foo", null),
            Arguments.of("PROPPATCH", "PATCH", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("PROPPATCH", "ORDERPATCH", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("PROPFIND", "PROPFIND", "/test/foo", null),
            // Try some non-standard method declarations
            Arguments.of("CorpQuery", "CorpQuery", "/test/foo", null),
            Arguments.of("CorpQuery", "GET", "/test/foo", is(Constraint.Authorization.ALLOWED)),
            Arguments.of("CorpQuery", "POST", "/test/foo", is(Constraint.Authorization.ALLOWED))
        );
    }

    /**
     * Tests allowed http-method-omission
     */
    @ParameterizedTest
    @MethodSource("singleAllowedMethodOmissionCases")
    public void testSingleAllowedMethodOmissionConstraint(String httpMethodOmission, String requestHttpMethod, String requestPath,
                                                          org.hamcrest.Matcher<Constraint.Authorization> authorizationMatcher) throws Exception
    {
        boolean coveredByConstraint = !httpMethodOmission.equalsIgnoreCase(requestHttpMethod);

        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{httpMethodOmission});
        allowedMapping.setConstraint(Constraint.ALLOWED);
        _security.setConstraintMappings(List.of(allowedMapping));
        _server.start();

        Constraint constraint = _security.getConstraint(requestPath, requestHttpMethod);
        if (!coveredByConstraint)
            assertThat("%s %s constraint not covered".formatted(requestHttpMethod, requestPath), constraint, nullValue());
        else
            assertThat("%s %s authorization".formatted(requestHttpMethod, requestPath), constraint.getAuthorization(), authorizationMatcher);
    }

    public static Stream<Arguments> combinedAllowedForbiddenMethodOmissionConstraintsCases()
    {
        return Stream.of(
            // Neither constraint covers GET
            Arguments.of("GET", "/test/foo", false, null),
            // The forbidden constraint wins
            Arguments.of("PUT", "/test/foo", true, is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("POST", "/test/foo", true, is(Constraint.Authorization.FORBIDDEN)),
            Arguments.of("DELETE", "/test/foo", true, is(Constraint.Authorization.FORBIDDEN))
        );
    }

    /**
     * Test of two constraints that have the same path, same http-method-omission, no roles, but different auth-constraint settings.
     */
    @ParameterizedTest
    @MethodSource("combinedAllowedForbiddenMethodOmissionConstraintsCases")
    public void testCombinedAllowedForbiddenMethodOmissionConstraints(String httpMethod, String requestPath,
                                                                      boolean coveredByConstraint,
                                                                      org.hamcrest.Matcher<Constraint.Authorization> authorizationMatcher) throws Exception
    {
        // Note: these two constraints are known to produce the "has uncovered HTTP methods" warning, do not try to fix by changing these constraints.
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{"GET"});
        forbiddenMapping.setConstraint(Constraint.FORBIDDEN);

        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{"GET"});
        allowedMapping.setConstraint(Constraint.ALLOWED);

        // This is the reverse order from testCombinedForbiddenAllowedMethodOmissionConstraints
        _security.setConstraintMappings(List.of(allowedMapping, forbiddenMapping));
        _server.start();

        Constraint constraint = _security.getConstraint(requestPath, httpMethod);
        if (!coveredByConstraint)
            assertThat("%s %s constraint not covered".formatted(httpMethod, requestPath), constraint, nullValue());
        else
            assertThat("%s %s authorization".formatted(httpMethod, requestPath), constraint.getAuthorization(), authorizationMatcher);
    }

    /**
     * Test of two constraints that have the same path, same http-method-omission, no roles, but different auth-constraint settings.
     */
    @ParameterizedTest
    @MethodSource("combinedAllowedForbiddenMethodOmissionConstraintsCases")
    public void testCombinedForbiddenAllowedMethodOmissionConstraints(String httpMethod, String requestPath,
                                                                      boolean coveredByConstraint,
                                                                      org.hamcrest.Matcher<Constraint.Authorization> authorizationMatcher) throws Exception
    {
        // Note: these two constraints are known to produce the "has uncovered HTTP methods" warning, do not try to fix by changing these constraints.
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/test/*");
        forbiddenMapping.setMethodOmissions(new String[]{"GET"});
        forbiddenMapping.setConstraint(Constraint.FORBIDDEN);

        ConstraintMapping allowedMapping = new ConstraintMapping();
        allowedMapping.setPathSpec("/test/*");
        allowedMapping.setMethodOmissions(new String[]{"GET"});
        allowedMapping.setConstraint(Constraint.ALLOWED);

        // This is the reverse order from testCombinedAllowedForbiddenMethodOmissionConstraints
        _security.setConstraintMappings(List.of(forbiddenMapping, allowedMapping));
        _server.start();

        Constraint constraint = _security.getConstraint(requestPath, httpMethod);
        if (!coveredByConstraint)
            assertThat("%s %s constraint not covered".formatted(httpMethod, requestPath), constraint, nullValue());
        else
            assertThat("%s %s authorization".formatted(httpMethod, requestPath), constraint.getAuthorization(), authorizationMatcher);
    }

    @Test
    public void testDefaultConstraint() throws Exception
    {
        _security.setAuthenticator(new BasicAuthenticator());

        Constraint.Builder authAnyRoleConstraint = new Constraint.Builder();
        authAnyRoleConstraint.authorization(Constraint.Authorization.KNOWN_ROLE);
        authAnyRoleConstraint.name("auth");
        ConstraintMapping mappingAuth = new ConstraintMapping();
        mappingAuth.setPathSpec("/auth/*");
        mappingAuth.setConstraint(authAnyRoleConstraint.build());
        _security.addConstraintMapping(mappingAuth);

        Constraint.Builder forbidConstraint = new Constraint.Builder();
        forbidConstraint.authorization(Constraint.Authorization.FORBIDDEN);
        forbidConstraint.name("forbid");
        ConstraintMapping mappingForbid = new ConstraintMapping();
        mappingForbid.setPathSpec("/forbid/*");
        mappingForbid.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(mappingForbid);

        ConstraintMapping forbidDefault = new ConstraintMapping();
        forbidDefault.setPathSpec("/");
        forbidDefault.setConstraint(forbidConstraint.build());
        _security.addConstraintMapping(forbidDefault);

        Constraint.Builder relaxConstraint = new Constraint.Builder();
        relaxConstraint.authorization(Constraint.Authorization.ALLOWED);
        relaxConstraint.name("relax");
        ConstraintMapping mappingAdminRelax = new ConstraintMapping();
        mappingAdminRelax.setPathSpec("/admin/relax/*");
        mappingAdminRelax.setConstraint(relaxConstraint.build());
        _security.addConstraintMapping(mappingAdminRelax);

        ConstraintMapping allowRoot = new ConstraintMapping();
        allowRoot.setPathSpec("");
        allowRoot.setConstraint(relaxConstraint.build());
        _security.addConstraintMapping(allowRoot);

        _server.addBean(newTestLoginService());
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
