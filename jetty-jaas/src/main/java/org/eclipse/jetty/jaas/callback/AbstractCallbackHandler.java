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

package org.eclipse.jetty.jaas.callback;

import java.io.IOException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

public abstract class AbstractCallbackHandler implements CallbackHandler
{
    protected String _userName;
    protected Object _credential;

    public void setUserName(String userName)
    {
        _userName = userName;
    }

    public String getUserName()
    {
        return _userName;
    }

    public void setCredential(Object credential)
    {
        _credential = credential;
    }

    public Object getCredential()
    {
        return _credential;
    }

    @Override
    public void handle(Callback[] callbacks)
        throws IOException, UnsupportedCallbackException
    {
    }
}
