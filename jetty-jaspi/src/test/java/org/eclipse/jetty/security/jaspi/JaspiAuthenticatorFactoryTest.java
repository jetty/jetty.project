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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.when;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.management.remote.JMXPrincipal;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.config.AuthConfigProvider;
import javax.security.auth.message.module.ServerAuthModule;
import javax.servlet.ServletContext;
import org.apache.geronimo.components.jaspi.impl.ConfigProviderImpl;
import org.apache.geronimo.components.jaspi.model.AuthModuleType;
import org.apache.geronimo.components.jaspi.model.ServerAuthConfigType;
import org.apache.geronimo.components.jaspi.model.ServerAuthContextType;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Server;
import org.junit.Before;
import org.junit.Test;

public class JaspiAuthenticatorFactoryTest
{

    private JaspiAuthenticatorFactory jaspiAutenticationFactory;

    private Server server;

    private Subject subject;

    private String serverName;

    private IdentityService identityService;

    private LoginService loginService;

    private AuthConfigFactory authConfigFactory;

    private ServletContext context;

    private AuthConfigProvider authConfigProvider;

    private AuthConfiguration configuration;

    @Before
    public void setUp() throws Exception
    {
        jaspiAutenticationFactory = new JaspiAuthenticatorFactory();
    }

    @Test
    public void testGetAuthenticator()
    {
        // given
        server = mock(Server.class);
        identityService = mock(IdentityService.class);
        loginService = mock(LoginService.class);
        authConfigFactory = AuthConfigFactory.getFactory();
        authConfigFactory = spy(authConfigFactory);
        context = mock(ServletContext.class);
        when(context.getContextPath()).thenReturn("");
        authConfigProvider = getAuthConfigProvider();
        configuration = getAuthConfiguration();
        jaspiAutenticationFactory.setServerName("server");
        jaspiAutenticationFactory.setServiceSubject(new Subject());
        authConfigFactory.registerConfigProvider(authConfigProvider, "HTTP", "server ", "test");

        // when
        Authenticator authenticator = jaspiAutenticationFactory.getAuthenticator(server, context, configuration, identityService, loginService);

        // then
        assertNotNull("method getauthenitcator must not return null authenticator on successful completion", authenticator);

        // cleanup
        // reset to null as this object internally uses some system resources
        AuthConfigFactory.setFactory(null);
        authConfigFactory = null;
    }

    private AuthConfigProvider getAuthConfigProvider()
    {
        AuthModuleType<ServerAuthModule> serverAuthModuleType = new AuthModuleType<>();
        serverAuthModuleType.setClassName("org.eclipse.jetty.security.jaspi.HttpHeaderAuthModule");
        List<AuthModuleType<ServerAuthModule>> serverAuthModules = new ArrayList<>();
        serverAuthModules.add(serverAuthModuleType);
        ServerAuthContextType serverAuthContextType = new ServerAuthContextType("HTTP", "server ", "authenticationContextID1", serverAuthModuleType);
        ServerAuthConfigType serverAuthConfigType = new ServerAuthConfigType(serverAuthContextType, true);
        List<ServerAuthConfigType> serverAuthConfigTypes = new ArrayList<>();
        serverAuthConfigTypes.add(serverAuthConfigType);
        AuthConfigProvider authConfigProvider = new ConfigProviderImpl(null, serverAuthConfigTypes);
        return authConfigProvider;
    }

    private AuthConfiguration getAuthConfiguration()
    {
        Set<String> initParamNames = new HashSet<>();
        initParamNames.add("k1");
        initParamNames.add("k2");
        AuthConfiguration configuration = mock(AuthConfiguration.class);
        when(configuration.getInitParameterNames()).thenReturn(initParamNames);
        when(configuration.getInitParameter("k1")).thenReturn("v1");
        when(configuration.getInitParameter("k2")).thenReturn("v2");
        return configuration;
    }

    private CallbackHandler getCallBackHandler()
    {
        CallbackHandler callbackHandler = new CallbackHandler()
        {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
            {
                // A no op call back implementaion
            }
        };
        return callbackHandler;
    }

    @Test
    public void testBasicMethods()
    {
        // given
        serverName = "MOMSQL0";
        subject = new Subject();

        // when
        jaspiAutenticationFactory.setServerName(serverName);
        jaspiAutenticationFactory.setServiceSubject(subject);

        // then
        assertEquals("Server name must be MOMSQL0", serverName, jaspiAutenticationFactory.getServerName() );
        assertEquals("Subject instances must be equal", subject, jaspiAutenticationFactory.getServiceSubject() );
    }

    @Test
    public void testFindServiceSubject() 
    {
        // given
        server = new Server();
        subject = new Subject();
        assertNull("This method must return null as the server doesn't contain any registered bean", jaspiAutenticationFactory.findServiceSubject(server) );
        Principal principal = new JMXPrincipal("userName");
        subject.getPrincipals().add(principal);

        /// when
        server.addBean(subject);

        // then
        assertEquals("Subject must match with subject instance", subject, jaspiAutenticationFactory.findServiceSubject(server) );

        // when
        jaspiAutenticationFactory.setServiceSubject(subject);

        // then
        assertEquals("Subject must match with subject instance", subject, jaspiAutenticationFactory.findServiceSubject(server) );
    }

    @Test
    public void testFindServerNameForPinciple()
    {
        // given
        server = mock(Server.class);
        serverName = jaspiAutenticationFactory.findServerName(server, subject);
        assertEquals("Server name must be equal to default value(server) as there is no match found for a given subject", "server", serverName);
        subject = new Subject();
        Principal principal = new JMXPrincipal("userName");
        subject.getPrincipals().add(principal);

        // when
        serverName = jaspiAutenticationFactory.findServerName(server, subject);

        // then
        assertEquals("Server name must be equal to value(server1) ", "userName", serverName);
    }

    @Test
    public void testFindServerNameForSubject()
    {
        // given
        subject = new Subject();

        // when
        serverName = jaspiAutenticationFactory.findServerName(server, subject);

        // then
        assertEquals("Server name must be equal to value(server1) ", "server", serverName);
    }

    @Test
    public void testFindServerNameForSetServer()
    {
        // given
        serverName = "server1";
        jaspiAutenticationFactory.setServerName(serverName);

        // when
        serverName = jaspiAutenticationFactory.findServerName(server, subject);

        // then
        assertEquals("Server name must be equal to value(server1) ", "server1", serverName);
    }
}