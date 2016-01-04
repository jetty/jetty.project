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

package org.eclipse.jetty.jaas.callback;

import java.io.IOException;
import java.util.Arrays;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.security.Password;

/** 
 * DefaultUsernameCredentialCallbackHandler
 */
public class DefaultCallbackHandler extends AbstractCallbackHandler
{
    private Request _request;

    public void setRequest (Request request)
    {
        this._request = request;
    }

    public void handle (Callback[] callbacks)
        throws IOException, UnsupportedCallbackException
    {
        for (int i=0; i < callbacks.length; i++)
        {
            if (callbacks[i] instanceof NameCallback)
            {
                ((NameCallback)callbacks[i]).setName(getUserName());
            }
            else if (callbacks[i] instanceof ObjectCallback)
            {
                ((ObjectCallback)callbacks[i]).setObject(getCredential());
            }
            else if (callbacks[i] instanceof PasswordCallback)
            {
                if (getCredential() instanceof Password)
                    ((PasswordCallback)callbacks[i]).setPassword (((Password)getCredential()).toString().toCharArray());
                else if (getCredential() instanceof String)
                {
                    ((PasswordCallback)callbacks[i]).setPassword (((String)getCredential()).toCharArray());
                }
                else
                    throw new UnsupportedCallbackException (callbacks[i], "User supplied credentials cannot be converted to char[] for PasswordCallback: try using an ObjectCallback instead");
            }
            else if (callbacks[i] instanceof RequestParameterCallback)
            {
                RequestParameterCallback callback = (RequestParameterCallback)callbacks[i];
                callback.setParameterValues(Arrays.asList(_request.getParameterValues(callback.getParameterName())));
            }
            else
                throw new UnsupportedCallbackException(callbacks[i]);
        }

    }

}

