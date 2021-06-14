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

    private final String messageLayer;
    private final String appContext;
    private final CallbackHandler callbackHandler;
    private final Map<String, String> providerProperties;
    private final ServerAuthModule serverAuthModule;

    public SimpleAuthConfig(String messageLayer, String appContext, CallbackHandler callbackHandler, Map<String, String> providerProperties,
            ServerAuthModule serverAuthModule) 
    {
        this.messageLayer = messageLayer;
        this.appContext = appContext;
        this.callbackHandler = callbackHandler;
        this.providerProperties = providerProperties;
        this.serverAuthModule = serverAuthModule;
    }

    @Override
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject,
            @SuppressWarnings("rawtypes") Map properties) throws AuthException
    {
        return new SimpleServerAuthContext(callbackHandler, serverAuthModule, providerProperties);
    }

    @Override
    public String getMessageLayer()
    {
        return messageLayer;
    }

    @Override
    public String getAppContext()
    {
        return appContext;
    }

    @Override
    public String getAuthContextID(MessageInfo messageInfo)
    {
        return appContext;
    }

    @Override
    public void refresh()
    {
        // NOOP
    }

    @Override
    public boolean isProtected()
    {
        return false;
    }
}
