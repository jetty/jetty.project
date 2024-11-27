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

package org.eclipse.jetty.docs.programming.security.siwe;

import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.server.Handler;

public class SignInWithEthereum
{
    public static SecurityHandler createSecurityHandler(Handler handler)
    {
        // tag::configureSecurityHandler[]
        // This uses jetty-core, but you can configure a ConstraintSecurityHandler for use with EE11.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.setHandler(handler);
        securityHandler.put("/*", Constraint.ANY_USER);

        // Add the EthereumAuthenticator to the securityHandler.
        EthereumAuthenticator authenticator = new EthereumAuthenticator();
        securityHandler.setAuthenticator(authenticator);

        // In embedded you can configure via EthereumAuthenticator APIs.
        authenticator.setLoginPath("/login.html");

        // Or you can configure with parameters on the SecurityHandler.
        securityHandler.setParameter(EthereumAuthenticator.LOGIN_PATH_PARAM, "/login.html");
        // end::configureSecurityHandler[]
        return securityHandler;
    }
}