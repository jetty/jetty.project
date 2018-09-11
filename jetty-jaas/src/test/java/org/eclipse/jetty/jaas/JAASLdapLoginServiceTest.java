//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.jaas;

import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ApplyLdifs;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.apache.directory.server.ldap.LdapServer;
import org.eclipse.jetty.jaas.spi.LdapLoginModule;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * JAASLdapLoginServiceTest
 *
 *
 */
@RunWith( FrameworkRunner.class)
@CreateLdapServer( transports = { @CreateTransport(protocol = "LDAP" ) } )
@CreateDS(allowAnonAccess = false, partitions = {
    @CreatePartition(name = "Users Partition", suffix = "ou=people,dc=jetty,dc=org"),
    @CreatePartition(name = "Groups Partition", suffix = "ou=groups,dc=jetty,dc=org")})
@ApplyLdifs({
    // Entry 1
    "dn: ou=people,dc=jetty,dc=org",
    "objectClass: organizationalunit",
    "objectClass: top",
    "ou: people",
    // Entry # 2
    "dn:uid=someone, ou=people,dc=jetty,dc=org",
    "objectClass: inetOrgPerson",
    "cn: someone",
    "sn: sn test",
    "userPassword: complicatedpassword",
    // Entry # 3
    "dn:uid=someoneelse, ou=people,dc=jetty,dc=org",
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
    "dn: cn=developers,ou=groups,dc=jetty,dc=org",
    "objectClass: groupOfUniqueNames",
    "objectClass: top",
    "ou: groups",
    "description: People who try to build good software",
    "uniquemember: uid=someone, ou=people, dc=jetty,dc=org",
    "cn: developers",
    // Entry 6
    "dn: cn=admin,ou=groups,dc=jetty,dc=org",
    "objectClass: groupOfUniqueNames",
    "objectClass: top",
    "ou: groups",
    "description: People who try to run software build by developers",
    "uniquemember: uid=someone, ou=people, dc=jetty,dc=org",
    "uniquemember: uid=someoneelse, ou=people, dc=jetty,dc=org",
    "cn: admin"
})
public class JAASLdapLoginServiceTest
{
    private static LdapServer _ldapServer;

    public static LdapServer getLdapServer() {
        return _ldapServer;
    }

    public static void setLdapServer(LdapServer ldapServer) {
        _ldapServer = ldapServer;
    }

    public static class TestConfiguration extends Configuration
    {
        private boolean forceBindingLogin;

        public TestConfiguration( boolean forceBindingLogin )
        {
            this.forceBindingLogin = forceBindingLogin;
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name)
        {
            Map<String,String> options = new HashMap<>( );
            options.put( "hostname", "localhost" );
            options.put( "port",  Integer.toString(_ldapServer.getTransports()[0].getPort()));
            options.put( "contextFactory", "com.sun.jndi.ldap.LdapCtxFactory" );
            options.put( "bindDn", "uid=admin,ou=system");
            options.put( "bindPassword", "secret");
            options.put( "userBaseDn", "ou=people,dc=jetty,dc=org" );
            options.put( "roleBaseDn","ou=groups,dc=jetty,dc=org");
            options.put( "roleNameAttribute", "cn" );
            options.put( "forceBindingLogin", Boolean.toString( forceBindingLogin ) );
            AppConfigurationEntry entry = new AppConfigurationEntry( LdapLoginModule.class.getCanonicalName(), LoginModuleControlFlag.REQUIRED, options);

            return new AppConfigurationEntry[] {entry};
        }
        
    }


    @Test
    public void testLdapUserIdentity() throws Exception
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(false));
        Request request = new Request(null, null);
        UserIdentity userIdentity = ls.login( "someone", "complicatedpassword", request);
        assertNotNull( userIdentity );
        assertTrue( userIdentity.isUserInRole( "developers", null) );
        assertTrue( userIdentity.isUserInRole( "admin", null) );
        assertFalse( userIdentity.isUserInRole( "blabla", null) );

        userIdentity = ls.login( "someoneelse", "verycomplicatedpassword", request);
        assertNotNull( userIdentity );
        assertFalse( userIdentity.isUserInRole( "developers", null) );
        assertTrue( userIdentity.isUserInRole( "admin", null) );
        assertFalse( userIdentity.isUserInRole( "blabla", null) );
    }

    @Test
    public void testLdapUserIdentityBindingLogin() throws Exception
    {
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(new TestConfiguration(true));
        Request request = new Request(null, null);
        UserIdentity userIdentity = ls.login( "someone", "complicatedpassword", request);
        assertNotNull( userIdentity );
        assertTrue( userIdentity.isUserInRole( "developers", null) );
        assertTrue( userIdentity.isUserInRole( "admin", null) );
        assertFalse( userIdentity.isUserInRole( "blabla", null) );

        userIdentity = ls.login( "someone", "wrongpassword", request);
        assertNull( userIdentity );

    }

}
