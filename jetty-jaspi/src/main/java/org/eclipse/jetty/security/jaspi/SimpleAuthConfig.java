//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;

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
    public ServerAuthContext getAuthContext(String authContextID, Subject serviceSubject, Map properties) throws AuthException
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
