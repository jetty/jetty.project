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

import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;

/**
 * @deprecated use {@link org.eclipse.jetty.security.jaspi.provider.JaspiAuthConfigProvider}.
 */
@Deprecated
public class SimpleAuthConfig implements ServerAuthConfig
{
    public static final String HTTP_SERVLET = "HttpServlet";

    private final String _appContext;

    private final ServerAuthContext _serverAuthContext;

    public SimpleAuthConfig(String appContext, ServerAuthContext serverAuthContext)
    {
        this._appContext = appContext;
        this._serverAuthContext = serverAuthContext;
    }

    @Override
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject, Map properties)
    {
        return _serverAuthContext;
    }

    // supposed to be of form host-name<space>context-path
    @Override
    public String getAppContext()
    {
        return _appContext;
    }

    // not used yet
    @Override
    public String getAuthContextID(MessageInfo messageInfo) throws IllegalArgumentException
    {
        return null;
    }

    @Override
    public String getMessageLayer()
    {
        return HTTP_SERVLET;
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
