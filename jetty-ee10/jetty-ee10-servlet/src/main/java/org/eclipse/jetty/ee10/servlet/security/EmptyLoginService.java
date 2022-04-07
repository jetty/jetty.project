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

import jakarta.servlet.ServletRequest;

/**
 * LoginService implementation which always denies any attempt to login.
 */
public class EmptyLoginService implements LoginService
{
    @Override
    public String getName()
    {
        return null;
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        return null;
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        return false;
    }

    @Override
    public IdentityService getIdentityService()
    {
        return null;
    }

    @Override
    public void setIdentityService(IdentityService service)
    {
    }

    @Override
    public void logout(UserIdentity user)
    {
    }
}
