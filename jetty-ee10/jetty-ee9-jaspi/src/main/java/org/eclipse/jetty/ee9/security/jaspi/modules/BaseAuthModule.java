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

package org.eclipse.jetty.ee9.security.jaspi.modules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.security.authentication.LoginCallbackImpl;
import org.eclipse.jetty.ee9.security.jaspi.JaspiMessageInfo;
import org.eclipse.jetty.ee9.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;

/**
 * Simple abstract module implementing a Jakarta Authentication {@link ServerAuthModule} and {@link ServerAuthContext}.
 * To be used as a building block for building more sophisticated auth modules.
 */
public abstract class BaseAuthModule implements ServerAuthModule, ServerAuthContext
{
    private static final Class[] SUPPORTED_MESSAGE_TYPES = new Class[]{HttpServletRequest.class, HttpServletResponse.class};

    protected static final String LOGIN_SERVICE_KEY = "org.eclipse.jetty.security.jaspi.modules.LoginService";

    protected CallbackHandler callbackHandler;

    @Override
    public Class[] getSupportedMessageTypes()
    {
        return SUPPORTED_MESSAGE_TYPES;
    }

    public BaseAuthModule()
    {
    }

    public BaseAuthModule(CallbackHandler callbackHandler)
    {
        this.callbackHandler = callbackHandler;
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler, Map options) throws AuthException
    {
        this.callbackHandler = handler;
    }

    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
    {
        // TODO apparently we either get the LoginCallback or the LoginService
        // but not both :-(
        // Set<LoginCallback> loginCallbacks =
        // subject.getPrivateCredentials(LoginCallback.class);
        // if (!loginCallbacks.isEmpty()) {
        // LoginCallback loginCallback = loginCallbacks.iterator().next();
        // }
        // try {
        // loginService.logout(subject);
        // } catch (ServerAuthException e) {
        // throw new AuthException(e.getMessage());
        // }
    }

    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
    {
        // servlets do not need secured responses
        return AuthStatus.SEND_SUCCESS;
    }

    /**
     * @param messageInfo message info to examine for mandatory flag
     * @return whether authentication is mandatory or optional
     */
    protected boolean isMandatory(MessageInfo messageInfo)
    {
        String mandatory = (String)messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY);
        if (mandatory == null)
            return false;
        return Boolean.parseBoolean(mandatory);
    }

    protected boolean login(Subject clientSubject, String credentials, String authMethod, MessageInfo messageInfo) throws IOException, UnsupportedCallbackException
    {
        credentials = credentials.substring(credentials.indexOf(' ') + 1);
        credentials = new String(Base64.getDecoder().decode(credentials), StandardCharsets.ISO_8859_1);
        int i = credentials.indexOf(':');
        String userName = credentials.substring(0, i);
        String password = credentials.substring(i + 1);
        return login(clientSubject, userName, new Password(password), authMethod, messageInfo);
    }

    protected boolean login(Subject clientSubject, String username, Credential credential, String authMethod, MessageInfo messageInfo) throws IOException, UnsupportedCallbackException
    {
        CredentialValidationCallback credValidationCallback = new CredentialValidationCallback(clientSubject, username, credential);
        callbackHandler.handle(new Callback[]{credValidationCallback});
        if (credValidationCallback.getResult())
        {
            Set<LoginCallbackImpl> loginCallbacks = clientSubject.getPrivateCredentials(LoginCallbackImpl.class);
            if (!loginCallbacks.isEmpty())
            {
                LoginCallbackImpl loginCallback = loginCallbacks.iterator().next();
                CallerPrincipalCallback callerPrincipalCallback = new CallerPrincipalCallback(clientSubject, loginCallback.getUserPrincipal());
                GroupPrincipalCallback groupPrincipalCallback = new GroupPrincipalCallback(clientSubject, loginCallback.getRoles());
                callbackHandler.handle(new Callback[]{callerPrincipalCallback, groupPrincipalCallback});
            }
            messageInfo.getMap().put(JaspiMessageInfo.AUTH_METHOD_KEY, authMethod);
        }
        return credValidationCallback.getResult();
    }
}
