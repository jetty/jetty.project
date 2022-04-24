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

package org.eclipse.jetty.ee9.jaas.callback;

import java.io.IOException;
import java.util.Arrays;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * DefaultCallbackHandler
 *
 * An implementation of the JAAS CallbackHandler. Users can provide
 * their own implementation instead and set the name of its class on the JAASLoginService.
 */
public class DefaultCallbackHandler extends AbstractCallbackHandler
{
    private HttpServletRequest _request;

    public void setRequest(HttpServletRequest request)
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
