// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.client;

import java.io.IOException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Buffer;

/**
 * An exchange that caches response status and fields for later use.
 * 
 * 
 *
 */
public class CachedExchange extends HttpExchange
{
    int _responseStatus;
    HttpFields _responseFields;

    public CachedExchange(boolean cacheFields)
    {
        if (cacheFields)
            _responseFields = new HttpFields();
    }

    /* ------------------------------------------------------------ */
    public int getResponseStatus()
    {
        if (_status < HttpExchange.STATUS_PARSING_HEADERS)
            throw new IllegalStateException("Response not received");
        return _responseStatus;
    }

    /* ------------------------------------------------------------ */
    public HttpFields getResponseFields()
    {
        if (_status < HttpExchange.STATUS_PARSING_CONTENT)
            throw new IllegalStateException("Headers not complete");
        return _responseFields;
    }

    /* ------------------------------------------------------------ */
    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
        _responseStatus = status;
        super.onResponseStatus(version,status,reason);
    }

    /* ------------------------------------------------------------ */
    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
        if (_responseFields != null)
            _responseFields.add(name,value);
        super.onResponseHeader(name,value);
    }

}
