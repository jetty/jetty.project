//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.openid;

import java.io.Serializable;
import java.security.Principal;

public class OpenIdUserPrincipal implements Principal, Serializable
{
    private static final long serialVersionUID = 1521094652756670469L;
    private final OpenIdCredentials _credentials;

    public OpenIdUserPrincipal(OpenIdCredentials credentials)
    {
        _credentials = credentials;
    }

    public OpenIdCredentials getCredentials()
    {
        return _credentials;
    }

    @Override
    public String getName()
    {
        return _credentials.getUserId();
    }

    @Override
    public String toString()
    {
        return _credentials.getUserId();
    }
}
