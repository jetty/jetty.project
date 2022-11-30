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

package org.eclipse.jetty.ee10.servlet.security.authentication;

import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.security.Authentication;
import org.eclipse.jetty.ee10.servlet.security.Authentication.User;
import org.eclipse.jetty.ee10.servlet.security.Authenticator;
import org.eclipse.jetty.ee10.servlet.security.ServerAuthException;
import org.eclipse.jetty.ee10.servlet.security.UserAuthentication;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.SecureRequestCustomizer.SslSessionData;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * CLIENT-CERT authenticator.
 *
 * <p>This {@link Authenticator} implements client certificate authentication.
 * The client certificates available in the request will be verified against the configured {@link SslContextFactory} instance
 * </p>
 */
public class SslClientCertAuthenticator extends LoginAuthenticator
{
    private final SslContextFactory sslContextFactory;
    private boolean validateCerts = true;

    public SslClientCertAuthenticator(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = Objects.requireNonNull(sslContextFactory);
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__CERT_AUTH;
    }

    @Override
    public Authentication validateRequest(Request req, Response res, Callback callback, boolean mandatory) throws ServerAuthException
    {
        if (!mandatory)
            return new DeferredAuthentication(this);

        //TODO this seems fragile, to rely on this name
        //X509Certificate[] certs = (X509Certificate[])req.getAttribute("jakarta.servlet.request.X509Certificate");
        SslSessionData sslSessionData = (SslSessionData)req.getAttribute(SecureRequestCustomizer.DEFAULT_SSL_SESSION_DATA_ATTRIBUTE);
        if (sslSessionData == null)
        {
            req.accept();
            Response.writeError(req, res, callback, HttpServletResponse.SC_FORBIDDEN);
            return Authentication.SEND_FAILURE;
        }
        
        X509Certificate[] certs = sslSessionData.peerCertificates();
        
        try
        {
            // Need certificates.
            if (certs != null && certs.length > 0)
            {

                if (validateCerts)
                {
                    sslContextFactory.validateCerts(certs);
                }

                for (X509Certificate cert : certs)
                {
                    if (cert == null)
                        continue;

                    Principal principal = cert.getSubjectDN();
                    if (principal == null)
                        principal = cert.getIssuerDN();
                    final String username = principal == null ? "clientcert" : principal.getName();

                    UserIdentity user = login(username, "", req);
                    if (user != null)
                    {
                        return new UserAuthentication(getAuthMethod(), user);
                    }
                    // try with null password
                    user = login(username, null, req);
                    if (user != null)
                    {
                        return new UserAuthentication(getAuthMethod(), user);
                    }
                    // try with certs sig against login service as previous behaviour
                    final char[] credential = Base64.getEncoder().encodeToString(cert.getSignature()).toCharArray();
                    user = login(username, credential, req);
                    if (user != null)
                    {
                        return new UserAuthentication(getAuthMethod(), user);
                    }
                }
            }

            if (!DeferredAuthentication.isDeferred(res))
            {
                req.accept();
                Response.writeError(req, res, callback, HttpServletResponse.SC_FORBIDDEN);
                return Authentication.SEND_FAILURE;
            }

            return Authentication.UNAUTHENTICATED;
        }
        catch (Exception e)
        {
            throw new ServerAuthException(e.getMessage());
        }
    }

    @Override
    public boolean secureResponse(Request req, Response res, Callback callback, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return true;
    }

    /**
     * @return true if SSL certificate has to be validated.
     */
    public boolean isValidateCerts()
    {
        return validateCerts;
    }

    /**
     * @param validateCerts true if SSL certificates have to be validated.
     */
    public void setValidateCerts(boolean validateCerts)
    {
        this.validateCerts = validateCerts;
    }
}
