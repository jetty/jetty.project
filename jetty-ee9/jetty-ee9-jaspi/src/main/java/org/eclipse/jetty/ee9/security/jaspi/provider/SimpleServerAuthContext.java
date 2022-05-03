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

package org.eclipse.jetty.ee9.security.jaspi.provider;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;

/**
 * Simple bridge implementation of the Jakarta Authentication {@link ServerAuthContext} interface.
 *
 * This implementation will only delegate to the provided {@link ServerAuthModule} implementation.
 */
class SimpleServerAuthContext implements ServerAuthContext
{
    private final ServerAuthModule serverAuthModule;

    @SuppressWarnings("rawtypes")
    public SimpleServerAuthContext(CallbackHandler callbackHandler, ServerAuthModule serverAuthModule, Map properties) throws AuthException
    {
        this.serverAuthModule = serverAuthModule;
        serverAuthModule.initialize(null, null, callbackHandler, properties);
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
        return serverAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject);
    }

    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
    {
        return serverAuthModule.secureResponse(messageInfo, serviceSubject);
    }

    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
    {
        serverAuthModule.cleanSubject(messageInfo, subject);
    }
}