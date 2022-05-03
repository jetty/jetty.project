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

package org.eclipse.jetty.ee10.security.jaspi;

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
 * Example JASPI Auth Module based on http://www.trajano.net/2014/06/creating-a-simple-jaspic-auth-module/
 */
public class HttpHeaderAuthModule implements ServerAuthModule
{

    /**
     * Supported message types. For our case we only need to deal with HTTP servlet request and responses. On Java EE 7 this will handle WebSockets as well.
     */
    private static final Class<?>[] SUPPORTED_MESSAGE_TYPES = new Class<?>[]
        {HttpServletRequest.class, HttpServletResponse.class};

    /**
     * Callback handler that is passed in initialize by the container. This processes the callbacks which are objects that populate the "subject".
     */
    private CallbackHandler handler;

    /**
     * Does nothing of note for what we need.
     */
    @Override
    public void cleanSubject(final MessageInfo messageInfo, final Subject subject) throws AuthException
    {
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class[] getSupportedMessageTypes()
    {
        return SUPPORTED_MESSAGE_TYPES;
    }

    /**
     * Initializes the module. Allows you to pass in options.
     *
     * @param requestPolicy request policy, ignored
     * @param responsePolicy response policy, ignored
     * @param h callback handler
     * @param options options
     */
    @Override
    public void initialize(final MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler h, Map options) throws AuthException
    {
        handler = h;
    }

    /**
     * @return AuthStatus.SEND_SUCCESS
     */
    @Override
    public AuthStatus secureResponse(final MessageInfo paramMessageInfo, final Subject subject) throws AuthException
    {
        return AuthStatus.SEND_SUCCESS;
    }

    /**
     * Validation occurs here.
     */
    @Override
    public AuthStatus validateRequest(final MessageInfo messageInfo, final Subject client, final Subject serviceSubject) throws AuthException
    {

        // Take the request from the messageInfo structure.
        final HttpServletRequest req = (HttpServletRequest)messageInfo.getRequestMessage();
        try
        {
            // Get the user name from the header. If not there then fail authentication.
            final String userName = req.getHeader("X-Forwarded-User");
            if (userName == null)
            {
                return AuthStatus.FAILURE;
            }

            // Store the user name that was in the header and also set a group.
            handler.handle(new Callback[]
                {
                    new CallerPrincipalCallback(client, userName),
                    new GroupPrincipalCallback(client, new String[]
                        {"users"})
                });
            return AuthStatus.SUCCESS;
        }
        catch (final Exception e)
        {
            throw new AuthException(e.getMessage());
        }
    }
}
