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
import java.util.Arrays;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.eclipse.jetty.server.Request;

/**
 * DefaultCallbackHandler
 *
 * An implementation of the JAAS CallbackHandler. Users can provide
 * their own implementation instead and set the name of its class on the JAASLoginService.
 */
public class DefaultCallbackHandler extends AbstractCallbackHandler
{
    private Request _request;

    public void setRequest(Request request)
    {
        _request = request;
    }

    @Override
    public void handle(Callback[] callbacks)
        throws IOException, UnsupportedCallbackException
    {
        for (Callback callback : callbacks)
        {
            if (callback instanceof NameCallback)
            {
                ((NameCallback)callback).setName(getUserName());
            }
            else if (callback instanceof ObjectCallback)
            {
                ((ObjectCallback)callback).setObject(getCredential());
            }
            else if (callback instanceof PasswordCallback)
            {
                ((PasswordCallback)callback).setPassword(getCredential().toString().toCharArray());
            }
            else if (callback instanceof RequestParameterCallback)
            {
                if (_request != null)
                {
                    RequestParameterCallback rpc = (RequestParameterCallback)callback;
                    rpc.setParameterValues(Arrays.asList(_request.getParameterValues(rpc.getParameterName())));
                }
            }
            else if (callback instanceof ServletRequestCallback)
            {
                ((ServletRequestCallback)callback).setRequest(_request);
            }
            else
                throw new UnsupportedCallbackException(callback);
        }
    }
}

