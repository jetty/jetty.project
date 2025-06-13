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

package org.eclipse.jetty.client.transport;

import java.util.Objects;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;

/**
 * Base class for {@link Response} implementations.
 */
public abstract class AbstractResponse implements Response
{
    private final Request request;

    public AbstractResponse(Request request)
    {
        this.request = Objects.requireNonNull(request);
    }

    @Override
    public Request getRequest()
    {
        return request;
    }

    /**
     * <p>Creates a new instance of this response,
     * with the given {@link Request} instance.</p>
     *
     * @param request the request
     * @return a new response instance with the given request
     */
    public abstract Response withRequest(Request request);
}
