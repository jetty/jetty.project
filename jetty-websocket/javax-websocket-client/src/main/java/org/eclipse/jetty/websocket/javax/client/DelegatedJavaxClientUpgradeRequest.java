//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.javax.client;

import java.net.URI;
import java.security.Principal;

import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.javax.common.UpgradeRequest;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link org.eclipse.jetty.client.HttpRequest}
 * in the {@link UpgradeRequest} interface.
 */
public class DelegatedJavaxClientUpgradeRequest implements UpgradeRequest
{
    private final ClientUpgradeRequest delegate;

    public DelegatedJavaxClientUpgradeRequest(ClientUpgradeRequest delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public Principal getUserPrincipal()
    {
        // User Principal not available from Client API
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return delegate.getURI();
    }
}
