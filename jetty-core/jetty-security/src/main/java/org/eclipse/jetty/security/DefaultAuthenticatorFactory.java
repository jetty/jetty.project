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
import java.util.List;

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
import org.eclipse.jetty.util.StringUtil;
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
        String auth = StringUtil.asciiToUpperCase(configuration.getAuthenticationType());
        if (auth == null)
            return null;

        return switch (auth)
        {
            case Authenticator.BASIC_AUTH -> new BasicAuthenticator();
            case Authenticator.DIGEST_AUTH -> new DigestAuthenticator();
            case Authenticator.FORM_AUTH -> new FormAuthenticator();
            case Authenticator.SPNEGO_AUTH -> new SPNEGOAuthenticator();
            case Authenticator.NEGOTIATE_AUTH -> new SPNEGOAuthenticator(Authenticator.NEGOTIATE_AUTH);  // see Bug #377076
            case Authenticator.MULTI_AUTH -> getMultiAuthenticator(server, context, configuration);
            case Authenticator.CERT_AUTH, Authenticator.CERT_AUTH2 ->
            {
                Collection<SslContextFactory> sslContextFactories = server.getBeans(SslContextFactory.class);
                if (sslContextFactories.size() != 1)
                    throw new IllegalStateException("SslClientCertAuthenticator requires a single SslContextFactory instances.");
                yield new SslClientCertAuthenticator(sslContextFactories.iterator().next());
            }
            default -> null;
        };
    }

    private Authenticator getMultiAuthenticator(Server server, Context context, Authenticator.Configuration configuration)
    {
        SecurityHandler securityHandler = SecurityHandler.getCurrentSecurityHandler();
        if (securityHandler == null)
            return null;

        String auth = configuration.getAuthenticationType();
        if (Authenticator.MULTI_AUTH.equalsIgnoreCase(auth))
        {
            MultiAuthenticator multiAuthenticator = new MultiAuthenticator();

            String authenticatorConfig = configuration.getParameter("org.eclipse.jetty.security.multi.authenticators");
            for (String config : StringUtil.csvSplit(authenticatorConfig))
            {
                String[] parts = config.split(":");
                if (parts.length != 2)
                    throw new IllegalArgumentException();

                String authType = parts[0].trim();
                String pathSpec = parts[1].trim();

                Authenticator.Configuration.Wrapper authConfig = new Authenticator.Configuration.Wrapper(configuration)
                {
                    @Override
                    public String getAuthenticationType()
                    {
                        return authType;
                    }
                };

                Authenticator authenticator = null;
                List<Authenticator.Factory> authenticatorFactories = securityHandler.getKnownAuthenticatorFactories();
                for (Authenticator.Factory factory : authenticatorFactories)
                {
                    authenticator = factory.getAuthenticator(server, context, authConfig);
                    if (authenticator != null)
                        break;
                }

                if (authenticator == null)
                    throw new IllegalStateException();
                multiAuthenticator.addAuthenticator(pathSpec, authenticator);
            }
            return multiAuthenticator;
        }
        return null;
    }
}
