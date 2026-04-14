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

package org.eclipse.jetty.ee10.security.jaspi.provider;

import java.util.Map;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.module.ServerAuthModule;

/** 
 * <p>A Jetty implementation of the {@link AuthConfigProvider} to allow registration of a {@link ServerAuthModule}
 * directly without having to write a custom {@link AuthConfigProvider}.</p>
 * <p>If this is being constructed by an {@link AuthConfigFactory} after being passed in as a className, then
 * you will need to provide the property {@code ServerAuthModule} containing the fully qualified name of
 * the {@link ServerAuthModule} class you wish to use.</p>
 */
@SuppressWarnings("rawtypes")
public class JaspiAuthConfigProvider extends org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider
{
    public JaspiAuthConfigProvider(Map properties, AuthConfigFactory factory)
    {
        super(properties, factory);
    }

    public JaspiAuthConfigProvider(String className)
    {
        super(className);
    }

    public JaspiAuthConfigProvider(String className, Map properties)
    {
        super(className, properties);
    }

    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule)
    {
        super(serverAuthModule);
    }

    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule, Map properties)
    {
        super(serverAuthModule, properties);
    }
}