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

package org.eclipse.jetty.server.handler.gzip;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;

public class HeaderWrappingRequest extends Request.Wrapper
{
    private final HttpFields _fields;

    public HeaderWrappingRequest(Request request, HttpFields fields)
    {
        super(request);
        _fields = fields;
    }

    @Override
    public HttpFields getHeaders()
    {
        if (_fields == null)
            return super.getHeaders();
        return _fields;
    }
}
