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

import org.eclipse.jetty.ee10.handler.UserIdentity;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class UserAuthentication extends AbstractUserAuthentication
{

    public UserAuthentication(String method, UserIdentity userIdentity)
    {
        super(method, userIdentity);
    }

    @Override
    public String toString()
    {
        return "{User," + getAuthMethod() + "," + _userIdentity + "}";
    }
}
