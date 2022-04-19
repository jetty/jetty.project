//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.jaas;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifs;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.apache.directory.server.ldap.LdapServer;
import org.eclipse.jetty.ee10.jaas.spi.LdapLoginModule;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.DefaultIdentityService;
import org.eclipse.jetty.ee10.servlet.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.Test;
import org.junit.runner.RunWith;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;

/**
 * JAASLdapLoginServiceTest
 */
@RunWith(FrameworkRunner.class)
@CreateLdapServer(transports = {@CreateTransport(protocol = "LDAP")})
@CreateDS(allowAnonAccess = false, partitions = {
    @CreatePartition(name = "Users Partition", suffix = "ou=people,dc=jetty,dc=org"),
    @CreatePartition(name = "Groups Partition", suffix = "ou=groups,dc=jetty,dc=org")
})
@ApplyLdifs({
    // Entry 1
    "dn: ou=people,dc=jetty,dc=org",
    "objectClass: organizationalunit",
    "objectClass: top",
    "ou: people",
    // Entry # 2
    "dn:uid=someone,ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: someone",
    "sn: sn test",
    "userPassword: complicatedpassword",
    // Entry # 3
    "dn:uid=someoneelse,ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: someoneelse",
    "sn: sn test",
    "userPassword: verycomplicatedpassword",
    // Entry 4
    "dn: ou=groups,dc=jetty,dc=org",
    "objectClass: organizationalunit",
    "objectClass: top",
    "ou: groups",
    // Entry 5
    "dn: ou=subdir,ou=people,dc=jetty,dc=org",
    "objectClass: organizationalunit",
    "objectClass: top",
    "ou: subdir",
    // Entry # 6
    "dn:uid=uniqueuser,ou=subdir,ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: uniqueuser",
    "sn: unique user",
    "userPassword: hello123",
    // Entry # 7
    "dn:uid=ambiguousone,ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: ambiguous1",
    "sn: ambiguous user",
    "userPassword: foobar",
    // Entry # 8
    "dn:uid=ambiguousone,ou=subdir,ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: ambiguous2",
    "sn: ambiguous subdir user",
    "userPassword: barfoo",
    // Entry 9
    "dn: cn=developers,ou=groups,dc=jetty,dc=org",
    "objectClass: groupOfUniqueNames",
    "objectClass: top",
    "ou: groups",
    "description: People who try to build good software",
    "uniquemember: uid=someone,ou=people,dc=jetty,dc=org",
    "uniquemember: uid=uniqueuser,ou=subdir,ou=people,dc=jetty,dc=org",
    "cn: developers",
    // Entry 10
    "dn: cn=admin,ou=groups,dc=jetty,dc=org",
    "objectClass: groupOfUniqueNames",
    "objectClass: top",
    "ou: groups",
    "description: People who try to run software build by developers",
    "uniquemember: uid=someone,ou=people,dc=jetty,dc=org",
    "uniquemember: uid=someoneelse,ou=people,dc=jetty,dc=org",
    "uniquemember: uid=uniqueuser,ou=subdir,ou=people,dc=jetty,dc=org",
    "cn: admin"
})
public class JAASLdapLoginServiceTest
{
    public static class TestConfiguration extends Configuration
    {
        private boolean forceBindingLogin;

        public TestConfiguration(boolean forceBindingLogin)
        {
            this.forceBindingLogin = forceBindingLogin;
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name)
        {
            Map<String, String> options = new HashMap<>();
            options.put("hostname", "localhost");
            options.put("port", Integer.toString(_ldapServer.getTransports()[0].getPort()));
            options.put("contextFactory", "com.sun.jndi.ldap.LdapCtxFactory");
            options.put("bindDn", "uid=admin,ou=system");
            options.put("bindPassword", "secret");
            options.put("userBaseDn", "ou=people,dc=jetty,dc=org");
            options.put("roleBaseDn", "ou=groups,dc=jetty,dc=org");
            options.put("roleNameAttribute", "cn");
            options.put("forceBindingLogin", Boolean.toString(forceBindingLogin));
            AppConfigurationEntry entry = new AppConfigurationEntry(LdapLoginModule.class.getCanonicalName(), LoginModuleControlFlag.REQUIRED, options);

            return new AppConfigurationEntry[]{entry};
        }
    }

    public static LdapServer getLdapServer()
    {
        return _ldapServer;
    }

    public static void setLdapServer(LdapServer ldapServer)
    {
        _ldapServer = ldapServer;
    }

    private static LdapServer _ldapServer;
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;
    
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setContextPath("/ctx");
        _server.setHandler(_context);
        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setAuthenticator(new BasicAuthenticator());
        _context.setSecurityHandler(security);
    }

    private JAASLoginService jaasLoginService(String name)
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.ee10.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(true));
        return ls;
    }

    private String doLogin(String username, String password, List<String> hasRoles, List<String> hasntRoles) throws Exception
    {
        JAASLoginService ls = jaasLoginService("foo");
        _server.addBean(ls, true);
        
        _context.setServletHandler(new ServletHandler());
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new TestServlet(hasRoles, hasntRoles));
        _context.getServletHandler().addServletWithMapping(holder, "/");
        
        _server.start();
        
        return _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("someone:complicatedpassword".getBytes(ISO_8859_1)) + "\n\n");
    }

    @Test
    public void testLdapUserIdentity() throws Exception
    {
        setUp();
        _context.setServletHandler(new ServletHandler());
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new TestServlet(Arrays.asList("developers", "admin"), Arrays.asList("blabla")));
        _context.getServletHandler().addServletWithMapping(holder, "/");
        
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.ee10.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(false));
        
        _server.addBean(ls, true);
        _server.start();
        
        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("someone:complicatedpassword".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        
        _server.stop();
        
        _context.setServletHandler(new ServletHandler());
        holder = new ServletHolder();
        holder.setServlet(new TestServlet(Arrays.asList("admin"), Arrays.asList("developers, blabla")));
        _context.getServletHandler().addServletWithMapping(holder, "/");
        
        _server.start();
        
        response = _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("someoneelse:verycomplicatedpassword".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testLdapUserIdentityBindingLogin() throws Exception
    {
        setUp();
        _context.setServletHandler(new ServletHandler());
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new TestServlet(Arrays.asList("developers", "admin"), Arrays.asList("blabla")));
        _context.getServletHandler().addServletWithMapping(holder, "/");
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.ee10.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(true));
        _server.addBean(ls, true);
        _server.start();
        
        
        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("someone:complicatedpassword".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        _server.stop();
        _context.setServletHandler(new ServletHandler());
        _context.addServlet(new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
                {
                    //check authentication status
                    if (req.getUserPrincipal() == null)
                        req.authenticate(resp);
                }
            
            }, "/");
        _server.start();
        
        //TODO this test shows response already committed!
        response = _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("someone:wrongpassword".getBytes(ISO_8859_1)) + "\n\n");
        System.err.println(response);
        assertThat(response, startsWith("HTTP/1.1 " + HttpServletResponse.SC_UNAUTHORIZED));
    }

    @Test
    public void testLdapBindingSubdirUniqueUserName() throws Exception
    {
        setUp();
        String response = doLogin("uniqueuser", "hello123", Arrays.asList("developers", "admin"), Arrays.asList("blabla"));
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    //TODO test is failing, needs more work
    @Test
    public void testLdapBindingAmbiguousUserName() throws Exception
    {
        setUp();
        String response = doLogin("ambiguousone", "foobar", null, null);
        assertThat(response, startsWith("HTTP/1.1 " + HttpServletResponse.SC_UNAUTHORIZED));
    }

    //TODO test is failing, needs more work
    @Test
    public void testLdapBindingSubdirAmbiguousUserName() throws Exception
    {
        setUp();
        String response = doLogin("ambiguousone", "barfoo", null, null);
        assertThat(response, startsWith("HTTP/1.1 " + HttpServletResponse.SC_UNAUTHORIZED));
    }
}
