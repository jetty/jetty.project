//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;

public class SimpleAuthConfig implements ServerAuthConfig
{

    private final String _messageLayer;
    private final String _appContext;
    private final CallbackHandler _callbackHandler;
    private final Map<String, String> _providerProperties;
    private final ServerAuthModule _serverAuthModule;

    public SimpleAuthConfig(String messageLayer, String appContext, CallbackHandler callbackHandler, Map<String, String> providerProperties,
            ServerAuthModule serverAuthModule) 
    {
        this._messageLayer = messageLayer;
        this._appContext = appContext;
        this._callbackHandler = callbackHandler;
        this._providerProperties = providerProperties;
        this._serverAuthModule = serverAuthModule;
    }

    @Override
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject,
            @SuppressWarnings("rawtypes") Map properties) throws AuthException
    {
        return new SimpleServerAuthContext(_callbackHandler, _serverAuthModule, _providerProperties);
    }

    // supposed to be of form host-name<space>context-path
    @Override
    public String getAppContext()
    {
        return _appContext;
    }
    
    // not used yet
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
        // NOOP
    }

}
