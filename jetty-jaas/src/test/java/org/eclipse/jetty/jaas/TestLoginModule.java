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

package org.eclipse.jetty.jaas;

import javax.security.auth.callback.Callback;
import javax.security.auth.login.LoginException;

import org.eclipse.jetty.jaas.callback.ServletRequestCallback;
import org.eclipse.jetty.jaas.spi.AbstractLoginModule;
import org.eclipse.jetty.jaas.spi.UserInfo;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.security.Password;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLoginModule extends AbstractLoginModule
{
    public ServletRequestCallback _callback = new ServletRequestCallback();

    @Override
    public UserInfo getUserInfo(String username) throws Exception
    {
        return new UserInfo(username, new Password("aaa"));
    }

    @Override
    public Callback[] configureCallbacks()
    {
        return ArrayUtil.addToArray(super.configureCallbacks(), _callback, Callback.class);
    }

    @Override
    public boolean login() throws LoginException
    {
        boolean result = super.login();
        assertNotNull(_callback.getRequest());
        return result;
    }
}
