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

package org.eclipse.jetty.security.jaas;

import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JAASLoginServiceTest
 */
public class JAASLoginServiceTest
{
    interface SomeRole
    {

    }

    public class TestRole implements Principal, SomeRole
    {
        String _name;

        public TestRole(String name)
        {
            _name = name;
        }

        public String getName()
        {
            return _name;
        }
    }

    public class AnotherTestRole extends TestRole
    {
        public AnotherTestRole(String name)
        {
            super(name);
        }
    }

    public class NotTestRole implements Principal
    {
        String _name;

        public NotTestRole(String n)
        {
            _name = n;
        }

        public String getName()
        {
            return _name;
        }
    }

    public static class TestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/plain");
            Content.Sink.write(response, true, """
                All OK
                httpURI=%s
                """.formatted(request.getHttpURI()), callback);
            return true;
        }
    }

    private Server _server;
    private LocalConnector _connector;
    private ContextHandler _context;
    
    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ContextHandler("/ctx");
        _server.setHandler(_context);
        SecurityHandler.PathMapped security = new SecurityHandler.PathMapped();
        security.setAuthenticator(new BasicAuthenticator());
        Constraint constraint = Constraint.ANY_USER;
        security.put("/jaspi/*", constraint);
        _context.setHandler(security);
        security.setHandler(new TestHandler());
    }
    
    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testServletRequestCallback() throws Exception
    {
        Configuration config = new Configuration()
        {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name)
            {
                return new AppConfigurationEntry[] {
                    new AppConfigurationEntry(TestLoginModule.class.getCanonicalName(), 
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                        Collections.emptyMap())
                };
            }
        };

        //Test with the DefaultCallbackHandler
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.security.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(config);
        _server.addBean(ls, true);
        _server.start();
        
        String response = _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("aaardvaark:aaa".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        
        _server.stop();
        _server.removeBean(ls);

        //Test with the fallback CallbackHandler
        ls = new JAASLoginService("foo");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(config);
        _server.addBean(ls, true);
        _server.start();
        
        response = _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("aaardvaark:aaa".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testLoginServiceRoles() throws Exception
    {
        JAASLoginService ls = new JAASLoginService("foo");

        //test that we always add in the DEFAULT ROLE CLASSNAME
        ls.setRoleClassNames(new String[]{"arole", "brole"});
        String[] roles = ls.getRoleClassNames();
        assertEquals(3, roles.length);
        assertEquals(JAASLoginService.DEFAULT_ROLE_CLASS_NAME, roles[2]);

        ls.setRoleClassNames(new String[]{});
        assertEquals(1, ls.getRoleClassNames().length);
        assertEquals(JAASLoginService.DEFAULT_ROLE_CLASS_NAME, ls.getRoleClassNames()[0]);

        ls.setRoleClassNames(null);
        assertEquals(1, ls.getRoleClassNames().length);
        assertEquals(JAASLoginService.DEFAULT_ROLE_CLASS_NAME, ls.getRoleClassNames()[0]);

        //test a custom role class where some of the roles are subclasses of it
        ls.setRoleClassNames(new String[]{TestRole.class.getName()});
        Subject subject = new Subject();
        subject.getPrincipals().add(new NotTestRole("w"));
        subject.getPrincipals().add(new TestRole("x"));
        subject.getPrincipals().add(new TestRole("y"));
        subject.getPrincipals().add(new AnotherTestRole("z"));

        String[] groups = ls.getGroups(subject);
        assertThat(Arrays.asList(groups), containsInAnyOrder("x", "y", "z"));
        
        //test a custom role class
        ls.setRoleClassNames(new String[]{AnotherTestRole.class.getName()});
        Subject subject2 = new Subject();
        subject2.getPrincipals().add(new NotTestRole("w"));
        subject2.getPrincipals().add(new TestRole("x"));
        subject2.getPrincipals().add(new TestRole("y"));
        subject2.getPrincipals().add(new AnotherTestRole("z"));
        String[] s2groups = ls.getGroups(subject2);
        assertThat(s2groups, is(notNullValue()));
        assertThat(Arrays.asList(s2groups), containsInAnyOrder("z"));

        //test a custom role class that implements an interface
        ls.setRoleClassNames(new String[]{SomeRole.class.getName()});
        Subject subject3 = new Subject();
        subject3.getPrincipals().add(new NotTestRole("w"));
        subject3.getPrincipals().add(new TestRole("x"));
        subject3.getPrincipals().add(new TestRole("y"));
        subject3.getPrincipals().add(new AnotherTestRole("z"));
        String[] s3groups = ls.getGroups(subject3);
        assertThat(s3groups, is(notNullValue()));
        assertThat(Arrays.asList(s3groups), containsInAnyOrder("x", "y", "z"));

        //test a class that doesn't match
        ls.setRoleClassNames(new String[]{NotTestRole.class.getName()});
        Subject subject4 = new Subject();
        subject4.getPrincipals().add(new TestRole("x"));
        subject4.getPrincipals().add(new TestRole("y"));
        subject4.getPrincipals().add(new AnotherTestRole("z"));
        assertEquals(0, ls.getGroups(subject4).length);
    }
}
