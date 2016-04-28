//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.config.ServerAuthContext;
import javax.security.auth.message.module.ServerAuthModule;

import org.apache.geronimo.components.jaspi.impl.ServerAuthContextImpl;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

public class SimpleAuthConfigTest
{

    private static ServerAuthContext serverAuthContext;

    private SimpleAuthConfig simpleAuthConfig;

    private MessageInfo messageInfo;

    private String authContextID;

    private Subject serviceSubject;

    private Map properties;

    private ServerAuthContext serverAuthContextExpected;

    private ServerAuthContext serverAuthContextActual;

    private String appContext;

    @BeforeClass
    public static void createServerAuthContext()
    {
        List<ServerAuthModule> serverAuthModules = new ArrayList<ServerAuthModule>();
        ServerAuthModule serverAuthModule1 = Mockito.mock(ServerAuthModule.class);
        serverAuthModules.add(serverAuthModule1);
        ServerAuthModule serverAuthModule2 = Mockito.mock(ServerAuthModule.class);
        serverAuthModules.add(serverAuthModule2);
        serverAuthContext = new ServerAuthContextImpl(serverAuthModules);
    }

    @Test
    public void testSimpleAuthConfig() throws AuthException
    {
        // given
        setAuthContextAndAuthConfig();
        serverAuthContextExpected = simpleAuthConfig.getAuthContext(authContextID, serviceSubject, properties);

        // when
        serverAuthContextActual = getServerAuthContext();
        appContext = simpleAuthConfig.getAppContext();

        // then
        assertEquals("Authcontext instances should be equal", serverAuthContextExpected, serverAuthContextActual);
        assertEquals("Appcontext instances should be equal", getAppContext(), appContext);
    }

    @Test
    public void testGetAuthContext() throws AuthException
    {
        // given
        setAuthContextAndAuthConfig();

        // when
        serverAuthContextExpected = simpleAuthConfig.getAuthContext(authContextID, serviceSubject, properties);

        // then
        assertEquals("Authcontext instances should be equal", serverAuthContextExpected, getServerAuthContext());
    }

    @Test
    public void testGetAppContext()
    {
        // when
        simpleAuthConfig = new SimpleAuthConfig(getAppContext(), getServerAuthContext());

        // then
        assertEquals("Appcontext instances should be equal", getAppContext(), simpleAuthConfig.getAppContext());
    }

    @Test
    public void testGetAuthContextID()
    {
        // when
        setSimpleAuthConfig();
        messageInfo = mock(MessageInfo.class);

        // then
        assertNull("Actual code is implemented in such a way that this method should always return null", simpleAuthConfig.getAuthContextID(messageInfo) );
    }

    @Test
    public void testGetMessageLayer()
    {
        // when
        setSimpleAuthConfig();

        // then
        assertEquals("Message layer should be HttpServlet", SimpleAuthConfig.HTTP_SERVLET, simpleAuthConfig.getMessageLayer() );
    }

    @Test
    public void testIsProtected()
    {
        // when
        setSimpleAuthConfig();

        // then
        assertTrue("Actual code is implemented in such a way that this method should always return true", simpleAuthConfig.isProtected() );
    }

    @Test
    public void testRefresh()
    {
        // given
        setSimpleAuthConfig();

        // this method is a no op method in actual code, thats why we are
        // not testing any thing here
        // when
        simpleAuthConfig.refresh();
    }

    private void setAuthContextAndAuthConfig()
    {
        setSimpleAuthConfig();
        authContextID = "authcontextId";
        serviceSubject = new Subject();
        properties = new HashMap();
    }

    private void setSimpleAuthConfig()
    {
        simpleAuthConfig = new SimpleAuthConfig(getAppContext(), getServerAuthContext() );
    }

    private String getAppContext()
    {
        // hostname<space>context path
        String appContext = "hostNameOfComputer /WEB_INF/classes";
        return appContext;
    }

    private ServerAuthContext getServerAuthContext()
    {
        return serverAuthContext;
    }
}