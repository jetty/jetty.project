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

package org.eclipse.jetty.ee10.jaas;

import java.security.Principal;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;

/**
 * JAASUserPrincipal
 * <p>
 * Implements the JAAS version of the
 * org.eclipse.jetty.security.UserPrincipal interface.
 */
public class JAASUserPrincipal implements Principal
{
    private final String _name;
    private final Subject _subject;
    private final LoginContext _loginContext;

    public JAASUserPrincipal(String name, Subject subject, LoginContext loginContext)
    {
        this._name = name;
        this._subject = subject;
        this._loginContext = loginContext;
    }

    /**
     * Get the name identifying the user
     */
    @Override
    public String getName()
    {
        return _name;
    }

    /**
     * Provide access to the Subject
     *
     * @return subject
     */
    public Subject getSubject()
    {
        return this._subject;
    }

    LoginContext getLoginContext()
    {
        return this._loginContext;
    }

    @Override
    public String toString()
    {
        return getName();
    }
}
