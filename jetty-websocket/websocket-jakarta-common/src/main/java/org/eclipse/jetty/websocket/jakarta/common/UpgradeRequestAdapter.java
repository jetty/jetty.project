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

package org.eclipse.jetty.websocket.jakarta.common;

import java.net.URI;
import java.security.Principal;

public class UpgradeRequestAdapter implements UpgradeRequest
{
    private final URI requestURI;
    private final String pathInContext;

    public UpgradeRequestAdapter()
    {
        /* anonymous, no requestURI, upgrade request */
        this(null, null);
    }

    public UpgradeRequestAdapter(URI uri, String pathInContext)
    {
        this.requestURI = uri;
        this.pathInContext = pathInContext;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return requestURI;
    }

    @Override
    public String getPathInContext()
    {
        return pathInContext;
    }
}
