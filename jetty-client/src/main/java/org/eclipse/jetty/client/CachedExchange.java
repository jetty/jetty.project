//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Buffer;

/**
 * An exchange that retains response status and response headers for later use.
 */
public class CachedExchange extends HttpExchange
{
    private final HttpFields _responseFields;
    private volatile int _responseStatus;

    /**
     * Creates a new CachedExchange.
     *
     * @param cacheHeaders true to cache response headers, false to not cache them
     */
    public CachedExchange(boolean cacheHeaders)
    {
        _responseFields = cacheHeaders ? new HttpFields() : null;
    }

    public synchronized int getResponseStatus()
    {
        if (getStatus() < HttpExchange.STATUS_PARSING_HEADERS)
            throw new IllegalStateException("Response not received yet");
        return _responseStatus;
    }

    public synchronized HttpFields getResponseFields()
    {
        if (getStatus() < HttpExchange.STATUS_PARSING_CONTENT)
            throw new IllegalStateException("Headers not completely received yet");
        return _responseFields;
    }

    @Override
    protected synchronized void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
        _responseStatus = status;
        super.onResponseStatus(version, status, reason);
    }

    @Override
    protected synchronized void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
        if (_responseFields != null)
        {
            _responseFields.add(name, value.asImmutableBuffer());
        }
        
        super.onResponseHeader(name, value);
    }
}
