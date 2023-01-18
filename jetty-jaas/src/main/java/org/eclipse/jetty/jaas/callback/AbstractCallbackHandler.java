//
// ========================================================================
// Copyright (c) 2019 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
