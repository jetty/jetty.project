//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
    
    
    public CredentialValidationCallback (Subject subject, String userName, Credential credential)
    {
        _subject = subject;
        _userName = userName;
        _credential = credential;
    }
    
    public Credential getCredential ()
    {
        return _credential;
    }
    
    public void clearCredential ()
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
