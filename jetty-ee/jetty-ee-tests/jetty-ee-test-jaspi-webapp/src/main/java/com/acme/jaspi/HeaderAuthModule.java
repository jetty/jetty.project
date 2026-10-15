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

package com.acme.jaspi;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates the caller named by the {@code X-Test-User} header into the {@code user} role,
 * and challenges for it when it is absent. Every resource of this web application is constrained,
 * so the module always challenges rather than asking whether authentication was mandatory.
 */
public class HeaderAuthModule implements ServerAuthModule
{
    public static final String USER_HEADER = "X-Test-User";

    private CallbackHandler _handler;

    @Override
    @SuppressWarnings("rawtypes")
    public Class[] getSupportedMessageTypes()
    {
        return new Class[]{HttpServletRequest.class, HttpServletResponse.class};
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler, Map options)
    {
        _handler = handler;
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
        HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
        String user = request.getHeader(USER_HEADER);
        if (user == null)
        {
            HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
            response.setHeader("WWW-Authenticate", "TestHeader realm=\"jaspi-webapp\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthStatus.SEND_CONTINUE;
        }

        try
        {
            _handler.handle(new Callback[]{
                new CallerPrincipalCallback(clientSubject, user),
                new GroupPrincipalCallback(clientSubject, new String[]{"user"})
            });
        }
        catch (Exception e)
        {
            throw new AuthException(e.getMessage());
        }
        return AuthStatus.SUCCESS;
    }

    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
    {
        return AuthStatus.SEND_SUCCESS;
    }

    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject)
    {
    }
}
