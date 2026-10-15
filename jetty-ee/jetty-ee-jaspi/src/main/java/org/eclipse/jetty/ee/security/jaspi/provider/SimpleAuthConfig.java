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

package org.eclipse.jetty.ee.security.jaspi.provider;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee.security.jaspi.JaspiMessageInfo;

/**
 * A {@link ServerAuthConfig} with a {@link ServerAuthContext} for each value of the mandatory message
 * policy, each wrapping a {@link ServerAuthModule}, or one context for both values when the two would
 * wrap the same module instance.
 */
class SimpleAuthConfig implements ServerAuthConfig
{
    private final String _messageLayer;
    private final String _appContext;
    private final ServerAuthContext _sharedContext;
    private final ServerAuthContext _mandatoryContext;
    private final ServerAuthContext _optionalContext;

    /**
     * @param mandatoryModule the module of the context for a message whose authentication is mandatory.
     * @param optionalModule the module of the context for a message whose authentication is optional,
     * which is the same instance as {@code mandatoryModule} if the provider was given a module to use
     * rather than a class to instantiate.
     */
    public SimpleAuthConfig(String messageLayer, String appContext, CallbackHandler callbackHandler, Map<String, Object> properties,
                            ServerAuthModule mandatoryModule, ServerAuthModule optionalModule) throws AuthException
    {
        _messageLayer = messageLayer;
        _appContext = appContext;

        boolean sharedModule = mandatoryModule == optionalModule;
        _sharedContext = sharedModule ? new SimpleServerAuthContext(callbackHandler, mandatoryModule, properties) : null;
        _mandatoryContext = sharedModule ? null : new SimpleServerAuthContext(callbackHandler, mandatoryModule, properties, true);
        _optionalContext = sharedModule ? null : new SimpleServerAuthContext(callbackHandler, optionalModule, properties, false);
    }

    /**
     * <p>The contexts are built with this configuration, which {@link JaspiAuthConfigProvider} keeps
     * for an application context, so the module of a context is initialized once with the request
     * policy of that context and cannot have it replaced by a concurrent message of the other one.</p>
     * <p>A module the two contexts share is initialized once as well, by the one context both
     * identifiers are served with, whose request policy reports the message being processed.</p>
     */
    @Override
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject, Map<String, Object> properties)
    {
        if (_sharedContext != null)
            return _sharedContext;
        return Boolean.parseBoolean(authContextID) ? _mandatoryContext : _optionalContext;
    }

    @Override
    public String getAppContext()
    {
        return _appContext;
    }
    
    /**
     * The identifier is whether authentication is mandatory for the message, which is all
     * {@link #getAuthContext} needs to pick the context whose module carries the matching request
     * policy.
     *
     * @param messageInfo the message being processed
     * @return the identifier of the context to process the message
     */
    @Override
    public String getAuthContextID(MessageInfo messageInfo)
    {
        return String.valueOf(isMandatory(messageInfo));
    }

    /**
     * @param messageInfo the message being processed
     * @return whether authentication is mandatory for it, as the authenticator declared with
     * {@link JaspiMessageInfo#MANDATORY_KEY}
     */
    static boolean isMandatory(MessageInfo messageInfo)
    {
        Object mandatory = messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY);
        return mandatory instanceof String value && Boolean.parseBoolean(value);
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