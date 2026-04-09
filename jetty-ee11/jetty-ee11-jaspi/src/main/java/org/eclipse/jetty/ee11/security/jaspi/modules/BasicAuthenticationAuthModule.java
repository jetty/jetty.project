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

package org.eclipse.jetty.ee11.security.jaspi.modules;

import java.util.Map;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.module.ServerAuthModule;

/** 
 * A {@link ServerAuthModule} implementation of HTTP Basic Authentication.  
 */
public class BasicAuthenticationAuthModule extends org.eclipse.jetty.ee.security.jaspi.modules.BasicAuthenticationAuthModule
{
    private static final String REALM_KEY = "org.eclipse.jetty.ee11.security.jaspi.modules.RealmName";

    public BasicAuthenticationAuthModule()
    {
        super();
    }

    public BasicAuthenticationAuthModule(CallbackHandler callbackHandler, String realmName)
    {
        super(callbackHandler, realmName);
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler callbackHandler, Map options) throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, callbackHandler, options);
        _realmName = (String)options.get(REALM_KEY);
    }
}
