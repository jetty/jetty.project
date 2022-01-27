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

package org.eclipse.jetty.security;

import java.security.Principal;
import java.util.Base64;

public class SpnegoUserPrincipal implements Principal
{
    private final String _name;
    private byte[] _token;
    private String _encodedToken;

    public SpnegoUserPrincipal(String name, String encodedToken)
    {
        _name = name;
        _encodedToken = encodedToken;
    }

    public SpnegoUserPrincipal(String name, byte[] token)
    {
        _name = name;
        _token = token;
    }

    @Override
    public String getName()
    {
        return _name;
    }

    public byte[] getToken()
    {
        if (_token == null)
            _token = Base64.getDecoder().decode(_encodedToken);
        return _token;
    }

    public String getEncodedToken()
    {
        if (_encodedToken == null)
            _encodedToken = new String(Base64.getEncoder().encode(_token));
        return _encodedToken;
    }
}
