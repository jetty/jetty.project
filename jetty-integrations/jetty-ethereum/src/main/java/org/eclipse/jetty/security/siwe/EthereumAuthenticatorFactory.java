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

package org.eclipse.jetty.security.siwe;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Server;

public class EthereumAuthenticatorFactory implements Authenticator.Factory
{
    @Override
    public Authenticator getAuthenticator(Server server, Context context, Authenticator.Configuration configuration)
    {
        String auth = configuration.getAuthenticationType();
        if (Authenticator.SIWE_AUTH.equalsIgnoreCase(auth))
            return new EthereumAuthenticator();
        return null;
    }
}
