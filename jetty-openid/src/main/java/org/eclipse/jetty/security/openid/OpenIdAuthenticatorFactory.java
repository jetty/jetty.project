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

package org.eclipse.jetty.security.openid;

import javax.servlet.ServletContext;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Constraint;

public class OpenIdAuthenticatorFactory implements Authenticator.Factory
{
    @Override
    public Authenticator getAuthenticator(Server server, ServletContext context, Authenticator.AuthConfiguration configuration, IdentityService identityService, LoginService loginService)
    {
        String auth = configuration.getAuthMethod();
        if (Constraint.__OPENID_AUTH.equalsIgnoreCase(auth))
        {
            // If we have an OpenIdLoginService we can extract the configuration.
            if (loginService instanceof OpenIdLoginService)
                return new OpenIdAuthenticator(((OpenIdLoginService)loginService).getConfiguration());

            // Otherwise we should find an OpenIdConfiguration for this realm on the Server.
            String realmName = configuration.getRealmName();
            OpenIdConfiguration openIdConfiguration = server.getBeans(OpenIdConfiguration.class).stream()
                .filter(c -> c.getIssuer().equals(realmName))
                .findAny()
                .orElseThrow(() -> new IllegalStateException("No OpenIdConfiguration found for realm \"" + realmName + "\""));
            return new OpenIdAuthenticator(openIdConfiguration);
        }

        return null;
    }
}
