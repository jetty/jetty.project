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
import static org.mockito.Matchers.anyObject;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.remote.JMXPrincipal;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.callback.CallerPrincipalCallback;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;
import javax.security.auth.message.module.ServerAuthModule;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.geronimo.components.jaspi.impl.ServerAuthConfigImpl;
import org.apache.geronimo.components.jaspi.impl.ServerAuthContextImpl;
import org.apache.geronimo.components.jaspi.model.ServerAuthConfigType;
import org.apache.geronimo.components.jaspi.model.ServerAuthContextType;
import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Password;

public class JaspiAuthenticatorBase
{

    protected JaspiAuthenticator jaspiAuthenticator;

    protected static final String SECURE_RESPONSE = "secResp";

    protected static final String VALIDATE_RESPONSE = "valResp";

    protected ServerAuthConfig serverAuthConfig;

    protected IdentityService identityService;

    protected boolean allowLazyAuthentication = false;

    protected Subject serviceSubject;

    protected ServletCallbackHandler callbackHandler;

    protected Map authProperties;

    protected ServletRequest req;

    protected ServletResponse res;

    protected boolean mandatory = true;

    protected User validatedUser;

    protected ServerAuthConfigType serverAuthConfigType;

    protected JaspiMessageInfo jspiMessageInfo;

    protected UserIdentity userIdentity;

    protected AuthConfiguration authConfig;

    protected LoginService loginService;

    protected HttpServletResponse httpServletresp;

    protected HttpServletRequest httpServletreq;

    protected Authentication authentication;

    protected JaspiMessageInfo getJaspiMessageInfo()
    {
        ServletRequest mockRequest = mock(ServletRequest.class);
        ServletResponse mockResponse = mock(ServletResponse.class);
        JaspiMessageInfo jaspiMessageInfo = new JaspiMessageInfo(mockRequest, mockResponse, true);
        return jaspiMessageInfo;
    }

    protected ServerAuthConfig getAuthConfig(JaspiMessageInfo messageInfo, String indicator, AuthStatus status)  throws AuthException
    {
        String authenticationContextID = "authenticationContextID1";
        ServerAuthContextType serverAuthContextType = new ServerAuthContextType("HTTP", "server /ctx", authenticationContextID, null);
        serverAuthConfigType = new ServerAuthConfigType(serverAuthContextType, true);
        List<ServerAuthModule> serverAuthModuleList = new ArrayList<>();
        ServerAuthModule mockServerAuthModule = getServerMockAuthModule(messageInfo, indicator, status);
        serverAuthModuleList.add(mockServerAuthModule);
        ServerAuthContext serverAuthContext = new ServerAuthContextImpl(serverAuthModuleList);
        Map<String, ServerAuthContext> serverAuthContextMap = new HashMap<>();
        serverAuthContextMap.put(authenticationContextID, serverAuthContext);
        ServerAuthConfig serverAuthConfig = new ServerAuthConfigImpl(serverAuthConfigType, serverAuthContextMap);
        return serverAuthConfig;
    }

    protected ServerAuthModule getServerMockAuthModule(JaspiMessageInfo messageInfo, String indicator, AuthStatus status) throws AuthException
    {
        if (messageInfo == null)
        {
            messageInfo = getJaspiMessageInfo();
        }
        ServerAuthModule mockServerAuthModule = mock(ServerAuthModule.class);
        if (SECURE_RESPONSE.equals(indicator))
        {
            when(mockServerAuthModule.secureResponse(messageInfo, null)).thenReturn(status);
        }
        else if (VALIDATE_RESPONSE.equals(indicator))
        {
            when(mockServerAuthModule.validateRequest((MessageInfo)anyObject(), (Subject)anyObject(), (Subject)anyObject())).thenReturn(status);
        }
        return mockServerAuthModule;
    }

    protected Callback[] getCallbacks(String name)
    {
        Subject subject = new Subject();
        Principal principal = new JMXPrincipal("a-valid-user");
        subject.getPrincipals().add(principal);
        Callback callerPrincipal = new CallerPrincipalCallback(subject, name);
        Callback[] callbacks ={ callerPrincipal };
        return callbacks;
    }

    protected ServletCallbackHandler getCallbackHandler()
    {
        HashLoginService loginService = new HashLoginService("TestRealm");
        loginService.putUser("user", new Password("password"), new String[]
        { "users" });
        loginService.putUser("admin", new Password("secret"), new String[]
        { "users", "admins" });
        ServletCallbackHandler callbackHandler = new ServletCallbackHandler(loginService);
        return callbackHandler;
    }

    protected void initiateJaspiAuthenticator(AuthStatus status)throws AuthException
    {
        jspiMessageInfo = getJaspiMessageInfo();
        callbackHandler = getCallbackHandler();
        httpServletresp = mock(HttpServletResponse.class);
        httpServletreq = mock(HttpServletRequest.class);
        serverAuthConfig = getAuthConfig(jspiMessageInfo, VALIDATE_RESPONSE, status);
        identityService = new DefaultIdentityService();
    }
}