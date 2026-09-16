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

package org.eclipse.jetty.client;

/**
 * Tests authentication in proxy mode by subclassing {@link HttpClientAuthenticationTest}.
 */
public class HttpClientProxyAuthenticationTest extends HttpClientAuthenticationTest
{
    @Override
    protected void startClient(Scenario scenario) throws Exception
    {
        super.startClient(scenario);
        client.getProxyConfiguration().addProxy(new HttpProxy("localhost", connector.getLocalPort()));
    }

    @Override
    protected int getServerPort()
    {
        return super.getServerPort() + 1;
    }

    @Override
    protected boolean isProxyMode()
    {
        return true;
    }
}
