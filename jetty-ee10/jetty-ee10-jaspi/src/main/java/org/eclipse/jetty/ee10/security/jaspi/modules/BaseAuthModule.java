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

package org.eclipse.jetty.ee10.security.jaspi.modules;

import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;

/**
 * Simple abstract module implementing a Jakarta Authentication {@link ServerAuthModule} and {@link ServerAuthContext}.
 * To be used as a building block for building more sophisticated auth modules.
 */
public abstract class BaseAuthModule extends org.eclipse.jetty.ee.security.jaspi.modules.BaseAuthModule
{
    public BaseAuthModule()
    {
        super();
    }

    public BaseAuthModule(CallbackHandler callbackHandler)
    {
        super(callbackHandler);
    }
}
