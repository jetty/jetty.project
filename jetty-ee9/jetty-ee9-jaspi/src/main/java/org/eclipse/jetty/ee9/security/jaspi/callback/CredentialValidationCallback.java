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

package org.eclipse.jetty.ee9.security.jaspi.callback;

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

    /**
     * @return the credential for validation
     */
    public Credential getCredential()
    {
        return _credential;
    }

    public void clearCredential()
    {
        _credential = null;
    }

    /**
     * @return true if validation was successful, false otherwise
     */
    public boolean getResult()
    {
        return _result;
    }

    /**
     * @return the subject associated with this credential validation
     */
    public javax.security.auth.Subject getSubject()
    {
        return _subject;
    }

    /**
     * @return the username associated with the credential
     */
    public java.lang.String getUsername()
    {
        return _userName;
    }

    public void setResult(boolean result)
    {
        _result = result;
    }
}
