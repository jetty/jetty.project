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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifs;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.ApacheDSTestExtension;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.jaas.spi.LdapLoginModule;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JAASLdapLoginServiceTest
 */
@ExtendWith(ApacheDSTestExtension.class)
@CreateLdapServer(transports = {@CreateTransport(protocol = "LDAP")})
@CreateDS(name = "JAASLdapLoginServiceTest-class", partitions = {
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
public class JAASLdapLoginServiceTest extends AbstractLdapTestUnit
{
    private JAASLoginService jaasLoginService(String name)
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.security.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(true));
        return ls;
    }

    private UserIdentity doLogin(String username, String password) throws Exception
    {
        JAASLoginService ls = jaasLoginService("foo");
        Request request = new MockCoreRequest();
        return ls.login(username, password, request, null);
    }

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
            options.put("port", Integer.toString(ldapServer.getTransports()[0].getPort()));
            options.put("contextFactory", "com.sun.jndi.ldap.LdapCtxFactory");
            options.put("bindDn", "uid=admin,ou=system");
            options.put("bindPassword", "OBF:1yta1t331v8w1v9q1t331ytc");
            options.put("userBaseDn", "ou=people,dc=jetty,dc=org");
            options.put("roleBaseDn", "ou=groups,dc=jetty,dc=org");
            options.put("roleNameAttribute", "cn");
            options.put("forceBindingLogin", Boolean.toString(forceBindingLogin));
            AppConfigurationEntry entry = new AppConfigurationEntry(LdapLoginModule.class.getCanonicalName(), LoginModuleControlFlag.REQUIRED, options);

            return new AppConfigurationEntry[]{entry};
        }
    }

    @Test
    public void testLdapUserIdentity() throws Exception
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.security.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(false));
        Request request = new MockCoreRequest();
        UserIdentity userIdentity = ls.login("someone", "complicatedpassword", request, null);
        assertNotNull(userIdentity);
        assertTrue(userIdentity.isUserInRole("developers"));
        assertTrue(userIdentity.isUserInRole("admin"));
        assertFalse(userIdentity.isUserInRole("blabla"));

        userIdentity = ls.login("someoneelse", "verycomplicatedpassword", request, null);
        assertNotNull(userIdentity);
        assertFalse(userIdentity.isUserInRole("developers"));
        assertTrue(userIdentity.isUserInRole("admin"));
        assertFalse(userIdentity.isUserInRole("blabla"));
    }

    @Test
    public void testLdapUserIdentityBindingLogin() throws Exception
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.security.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(true));
        Request request = new MockCoreRequest();
        UserIdentity userIdentity = ls.login("someone", "complicatedpassword", request, null);
        assertNotNull(userIdentity);
        assertTrue(userIdentity.isUserInRole("developers"));
        assertTrue(userIdentity.isUserInRole("admin"));
        assertFalse(userIdentity.isUserInRole("blabla"));

        userIdentity = ls.login("someone", "wrongpassword", request, null);
        assertNull(userIdentity);
    }

    @Test
    public void testLdapBindingSubdirUniqueUserName() throws Exception
    {
        UserIdentity userIdentity = doLogin("uniqueuser", "hello123");
        assertNotNull(userIdentity);
        assertTrue(userIdentity.isUserInRole("developers"));
        assertTrue(userIdentity.isUserInRole("admin"));
        assertFalse(userIdentity.isUserInRole("blabla"));
    }

    @Test
    public void testLdapBindingAmbiguousUserName() throws Exception
    {
        UserIdentity userIdentity = doLogin("ambiguousone", "foobar");
        assertNull(userIdentity);
    }

    @Test
    public void testLdapBindingSubdirAmbiguousUserName() throws Exception
    {
        UserIdentity userIdentity = doLogin("ambiguousone", "barfoo");
        assertNull(userIdentity);
    }

    private static class MockCoreRequest implements Request
    {

        @Override
        public Object removeAttribute(String name)
        {
            return null;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return null;
        }

        @Override
        public Object getAttribute(String name)
        {
            return null;
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return null;
        }

        @Override
        public void clearAttributes()
        {
        }

        @Override
        public String getId()
        {
            return null;
        }

        @Override
        public Components getComponents()
        {
            return null;
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return null;
        }

        @Override
        public String getMethod()
        {
            return null;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return null;
        }

        @Override
        public Context getContext()
        {
            return null;
        }

        @Override
        public HttpFields getHeaders()
        {
            return null;
        }

        @Override
        public HttpFields getTrailers()
        {
            return null;
        }

        public List<HttpCookie> getCookies()
        {
            return null;
        }

        @Override
        public long getBeginNanoTime()
        {
            return 0;
        }

        @Override
        public long getHeadersNanoTime()
        {
            return 0;
        }

        @Override
        public boolean isSecure()
        {
            return false;
        }

        @Override
        public long getLength()
        {
            return 0;
        }

        @Override
        public Content.Chunk read()
        {
            return null;
        }

        @Override
        public boolean consumeAvailable()
        {
            return false;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
        }

        @Override
        public void fail(Throwable failure)
        {
        }

        @Override
        public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
        {
        }

        @Override
        public void addFailureListener(Consumer<Throwable> onFailure)
        {
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return null;
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
        }

        @Override
        public Session getSession(boolean create)
        {
            return null;
        }
    }
}
