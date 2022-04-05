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

package org.eclipse.jetty.ee10.servlet.security;

import java.util.Collection;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee10.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.ee10.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.ClientCertAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.ConfigurableSpnegoAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.ee10.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.FormAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.ee10.security.authentication.SessionAuthentication;
import org.eclipse.jetty.ee10.security.authentication.SslClientCertAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Default Authenticator Factory.
 * Uses the {@link AuthConfiguration#getAuthMethod()} to select an {@link Authenticator} from: <ul>
 * <li>{@link BasicAuthenticator}</li>
 * <li>{@link DigestAuthenticator}</li>
 * <li>{@link FormAuthenticator}</li>
 * <li>{@link ClientCertAuthenticator}</li>
 * <li>{@link SslClientCertAuthenticator}</li>
 * </ul>
 * All authenticators derived from {@link LoginAuthenticator} are
 * wrapped with a {@link DeferredAuthentication}
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

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAuthenticatorFactory.class);

    LoginService _loginService;

    @Override
    public Authenticator getAuthenticator(Server server, ServletContext context, AuthConfiguration configuration, IdentityService identityService, LoginService loginService)
    {
        String auth = configuration.getAuthMethod();
        Authenticator authenticator = null;

        if (Constraint.__BASIC_AUTH.equalsIgnoreCase(auth))
            authenticator = new BasicAuthenticator();
        else if (Constraint.__DIGEST_AUTH.equalsIgnoreCase(auth))
            authenticator = new DigestAuthenticator();
        else if (Constraint.__FORM_AUTH.equalsIgnoreCase(auth))
            authenticator = new FormAuthenticator();
        else if (Constraint.__SPNEGO_AUTH.equalsIgnoreCase(auth))
            authenticator = new ConfigurableSpnegoAuthenticator();
        else if (Constraint.__NEGOTIATE_AUTH.equalsIgnoreCase(auth)) // see Bug #377076
            authenticator = new ConfigurableSpnegoAuthenticator(Constraint.__NEGOTIATE_AUTH);
        if (Constraint.__CERT_AUTH.equalsIgnoreCase(auth) || Constraint.__CERT_AUTH2.equalsIgnoreCase(auth))
        {
            Collection<SslContextFactory> sslContextFactories = server.getBeans(SslContextFactory.class);
            if (sslContextFactories.size() != 1)
            {
                if (sslContextFactories.size() > 1)
                {
                    LOG.info("Multiple SslContextFactory instances discovered. Directly configure a SslClientCertAuthenticator to use one.");
                }
                else
                {
                    LOG.debug("No SslContextFactory instances discovered. Directly configure a SslClientCertAuthenticator to use one.");
                }
                authenticator = new ClientCertAuthenticator();
            }
            else
            {
                authenticator = new SslClientCertAuthenticator(sslContextFactories.iterator().next());
            }
        }

        return authenticator;
    }

    /**
     * @return the loginService
     */
    public LoginService getLoginService()
    {
        return _loginService;
    }

    /**
     * @param loginService the loginService to set
     */
    public void setLoginService(LoginService loginService)
    {
        _loginService = loginService;
    }
}
