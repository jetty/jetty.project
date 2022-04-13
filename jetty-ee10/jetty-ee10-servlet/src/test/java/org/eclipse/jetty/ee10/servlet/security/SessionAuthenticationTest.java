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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SessionAuthenticationTest
 *
 */
public class SessionAuthenticationTest
{
    /**
     * Check that a SessionAuthenticator is serializable, and that
     * the deserialized SessionAuthenticator contains the same authentication
     * and authorization information.
     */
    @Test
    public void testSessionAuthenticationSerialization()
        throws Exception
    {

        ServletContextHandler contextHandler = new ServletContextHandler();
        SecurityHandler securityHandler = new ConstraintSecurityHandler();
        contextHandler.setSecurityHandler(securityHandler);
        TestLoginService loginService = new TestLoginService("SessionAuthTest");
        Password pwd = new Password("foo");
        loginService.putUser("foo", pwd, new String[]{"boss", "worker"});
        securityHandler.setLoginService(loginService);
        securityHandler.setAuthMethod("FORM");
        UserIdentity user = loginService.login("foo", pwd, null);
        assertNotNull(user);
        assertNotNull(user.getUserPrincipal());
        assertEquals("foo", user.getUserPrincipal().getName());
        SessionAuthentication sessionAuth = new SessionAuthentication("FORM", user, pwd);
        assertTrue(sessionAuth.isUserInRole("boss"));
        contextHandler.getContext().run(new Runnable()
        {
            public void run()
            {
                try
                {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    oos.writeObject(sessionAuth);
                    oos.close();
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
                    SessionAuthentication reactivatedSessionAuth = (SessionAuthentication)ois.readObject();
                    assertNotNull(reactivatedSessionAuth);
                    assertNotNull(reactivatedSessionAuth.getUserIdentity());
                    assertNotNull(reactivatedSessionAuth.getUserIdentity().getUserPrincipal());
                    assertEquals("foo", reactivatedSessionAuth.getUserIdentity().getUserPrincipal().getName());
                    assertNotNull(reactivatedSessionAuth.getUserIdentity().getSubject());
                    assertTrue(reactivatedSessionAuth.isUserInRole("boss"));
                }
                catch (Exception e)
                {
                    fail(e);
                }
            }
        });
    }
}
