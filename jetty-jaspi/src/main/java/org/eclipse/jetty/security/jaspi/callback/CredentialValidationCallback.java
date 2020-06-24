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

package org.eclipse.jetty.security.jaspi.callback;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;

import org.eclipse.jetty.util.security.Credential;

/**
 * CredentialValidationCallback
 *
 * Store a jetty Credential for a user so that it can be
 * validated by jaspi
 */
public class CredentialValidationCallback implements Callback
{
    private Credential _credential;
    private boolean _result;
    private Subject _subject;
    private String _userName;

    public CredentialValidationCallback(Subject subject, String userName, Credential credential)
    {
        _subject = subject;
        _userName = userName;
        _credential = credential;
    }

    public Credential getCredential()
    {
        return _credential;
    }

    public void clearCredential()
    {
        _credential = null;
    }

    public boolean getResult()
    {
        return _result;
    }

    public javax.security.auth.Subject getSubject()
    {
        return _subject;
    }

    public java.lang.String getUsername()
    {
        return _userName;
    }

    public void setResult(boolean result)
    {
        _result = result;
    }
}
