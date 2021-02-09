//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
