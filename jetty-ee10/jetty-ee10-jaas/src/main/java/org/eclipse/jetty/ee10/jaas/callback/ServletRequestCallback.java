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

package org.eclipse.jetty.ee10.jaas.callback;

import javax.security.auth.callback.Callback;

import jakarta.servlet.ServletRequest;

/**
 * ServletRequestCallback
 *
 * Provides access to the request associated with the authentication.
 */
public class ServletRequestCallback implements Callback
{
    protected ServletRequest _request;

    public void setRequest(ServletRequest request)
    {
        _request = request;
    }

    public ServletRequest getRequest()
    {
        return _request;
    }
}
