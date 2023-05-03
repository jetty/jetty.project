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

package org.eclipse.jetty.security;

import java.util.Collection;

import org.eclipse.jetty.security.Authenticator.Configuration;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SPNEGOAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.security.authentication.SslClientCertAuthenticator;
import org.eclipse.jetty.security.internal.DeferredAuthenticationState;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * The Default Authenticator Factory.
 * Uses the {@link Configuration#getAuthenticationType()} to select an {@link Authenticator} from: <ul>
 * <li>{@link BasicAuthenticator}</li>
 * <li>{@link DigestAuthenticator}</li>
 * <li>{@link FormAuthenticator}</li>
 * <li>{@link SslClientCertAuthenticator}</li>
 * <li>{@link SPNEGOAuthenticator}</li>
 * </ul>
 * All authenticators derived from {@link LoginAuthenticator} are
 * wrapped with a {@link DeferredAuthenticationState}
 * instance, which is used if authentication is not mandatory.
 *
 * The Authentications from the {@link FormAuthenticator} are always wrapped in a
 * {@link SessionAuthentication}
 * <p>
 * If a {@link LoginService} has not been set on this factory, then
 * the service is selected by searching the {@link Server#getBeans(Class)} results for
 * a service that matches the realm name, else the first LoginService found is used.
 */
public class DefaultAuthenticatorFactory implements Authenticator.Factory
{
    @Override
    public Authenticator getAuthenticator(Server server, Context context, Configuration configuration)
    {
        String auth = configuration.getAuthenticationType();
        Authenticator authenticator = null;

        if (Authenticator.BASIC_AUTH.equalsIgnoreCase(auth))
            authenticator = new BasicAuthenticator();
        else if (Authenticator.DIGEST_AUTH.equalsIgnoreCase(auth))
            authenticator = new DigestAuthenticator();
        else if (Authenticator.FORM_AUTH.equalsIgnoreCase(auth))
            authenticator = new FormAuthenticator();
        else if (Authenticator.SPNEGO_AUTH.equalsIgnoreCase(auth))
            authenticator = new SPNEGOAuthenticator();
        else if (Authenticator.NEGOTIATE_AUTH.equalsIgnoreCase(auth)) // see Bug #377076
            authenticator = new SPNEGOAuthenticator(Authenticator.NEGOTIATE_AUTH);
        if (Authenticator.CERT_AUTH.equalsIgnoreCase(auth))
        {
            Collection<SslContextFactory> sslContextFactories = server.getBeans(SslContextFactory.class);
            if (sslContextFactories.size() != 1)
                throw new IllegalStateException("SslClientCertAuthenticator requires a single SslContextFactory instances.");
            authenticator = new SslClientCertAuthenticator(sslContextFactories.iterator().next());
        }

        return authenticator;
    }
}
