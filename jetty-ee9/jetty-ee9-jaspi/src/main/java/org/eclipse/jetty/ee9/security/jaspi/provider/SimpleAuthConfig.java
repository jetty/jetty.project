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
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;

/**
 * Simple implementation of the {@link ServerAuthConfig} interface.
 * 
 * This implementation wires up the given {@link ServerAuthModule} to the appropriate Jakarta Authentication {@link ServerAuthContext} responsible 
 * for providing it.
 */
@SuppressWarnings("rawtypes")
class SimpleAuthConfig implements ServerAuthConfig
{
    private final String _messageLayer;
    private final String _appContext;
    private final CallbackHandler _callbackHandler;
    private final Map _properties;
    private final ServerAuthModule _serverAuthModule;

    public SimpleAuthConfig(String messageLayer, String appContext, CallbackHandler callbackHandler, Map properties, ServerAuthModule serverAuthModule)
    {
        _messageLayer = messageLayer;
        _appContext = appContext;
        _callbackHandler = callbackHandler;
        _properties = properties;
        _serverAuthModule = serverAuthModule;
    }

    @Override
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject, Map properties) throws AuthException
    {
        return new SimpleServerAuthContext(_callbackHandler, _serverAuthModule, _properties);
    }

    @Override
    public String getAppContext()
    {
        return _appContext;
    }
    
    @Override
    public String getAuthContextID(MessageInfo messageInfo)
    {
        return null;
    }

    @Override
    public String getMessageLayer()
    {
        return _messageLayer;
    }

    @Override
    public boolean isProtected()
    {
        return true;
    }

    @Override
    public void refresh()
    {
    }
}