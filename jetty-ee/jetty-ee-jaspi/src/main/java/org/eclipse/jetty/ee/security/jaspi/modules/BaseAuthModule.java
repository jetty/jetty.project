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

package org.eclipse.jetty.ee.security.jaspi.modules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.jaspi.JaspiMessageInfo;
import org.eclipse.jetty.ee.security.jaspi.ServletCallbackHandler;
import org.eclipse.jetty.ee.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;

/**
 * Simple abstract module implementing a Jakarta Authentication {@link ServerAuthModule} and {@link ServerAuthContext}.
 * To be used as a building block for building more sophisticated auth modules.
 */
public abstract class BaseAuthModule implements ServerAuthModule, ServerAuthContext
{
    private static final Class<?>[] SUPPORTED_MESSAGE_TYPES = new Class<?>[]{HttpServletRequest.class, HttpServletResponse.class};

    protected CallbackHandler callbackHandler;

    @Override
    public Class<?>[] getSupportedMessageTypes()
    {
        return SUPPORTED_MESSAGE_TYPES.clone();
    }

    public BaseAuthModule()
    {
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler, Map<String, Object> options) throws AuthException
    {
        this.callbackHandler = handler;
    }

    /**
     * Removes the caller and group principals this module established through the callback handler.
     */
    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
    {
        if (subject != null)
            ServletCallbackHandler.cleanSubject(subject);
    }

    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
    {
        // servlets do not need secured responses
        return AuthStatus.SEND_SUCCESS;
    }

    protected boolean isMandatory(MessageInfo messageInfo)
    {
        return messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY) instanceof String mandatory && Boolean.parseBoolean(mandatory);
    }

    /**
     * <p>Establish the container's representation of the unauthenticated caller in the
     * {@code clientSubject}, which the Servlet Container Profile requires of a module that
     * returns {@link AuthStatus#SUCCESS} without having satisfied the authentication policy.</p>
     *
     * @param clientSubject the subject of the request validation
     * @throws IOException if the callback handler fails
     * @throws UnsupportedCallbackException if the callback handler does not support the callback
     */
    protected void unauthenticated(Subject clientSubject) throws IOException, UnsupportedCallbackException
    {
        callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, (Principal)null)});
    }

    /**
     * Authenticates an HTTP Basic {@code Authorization} header value, where a header that is not Basic or is malformed is not authenticated.
     */
    protected boolean login(Subject clientSubject, String credentials, String authenticationType, MessageInfo messageInfo) throws IOException, UnsupportedCallbackException
    {
        int space = credentials.indexOf(' ');
        if (space <= 0 || !"Basic".equalsIgnoreCase(credentials.substring(0, space)))
            return false;

        byte[] decoded;
        try
        {
            decoded = Base64.getDecoder().decode(credentials.substring(space + 1));
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }

        credentials = new String(decoded, StandardCharsets.ISO_8859_1);
        int i = credentials.indexOf(':');
        if (i <= 0)
            return false;

        String userName = credentials.substring(0, i);
        String password = credentials.substring(i + 1);
        return login(clientSubject, userName, new Password(password), authenticationType, messageInfo);
    }

    protected boolean login(Subject clientSubject, String username, Credential credential, String authenticationType, MessageInfo messageInfo) throws IOException, UnsupportedCallbackException
    {
        CredentialValidationCallback credValidationCallback = new CredentialValidationCallback(clientSubject, username, credential);
        callbackHandler.handle(new Callback[]{credValidationCallback});
        if (credValidationCallback.getResult())
        {
            callbackHandler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, username)});
            messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, authenticationType);
        }
        return credValidationCallback.getResult();
    }
}
