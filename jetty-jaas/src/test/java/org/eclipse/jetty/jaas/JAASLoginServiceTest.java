//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
                            LoginModuleControlFlag.REQUIRED, 
                            Collections.emptyMap())
                    };
                }
            };
        
        //Test with the DefaultCallbackHandler
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(config);
        Request request = new Request(null, null);
        ls.login("aaardvaark", "aaa", request);

        //Test with the fallback CallbackHandler
        ls = new JAASLoginService("foo");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(config);
        ls.login("aaardvaark", "aaa", request);
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
