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

package org.eclipse.jetty.http;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Supplier;

public class MetaData implements Iterable<HttpField>
{
    private HttpVersion _httpVersion;
    private final HttpFields _fields;
    private long _contentLength;
    private Supplier<HttpFields> _trailers;

    public MetaData(HttpVersion version, HttpFields fields)
    {
        this(version, fields, Long.MIN_VALUE);
    }

    public MetaData(HttpVersion version, HttpFields fields, long contentLength)
    {
        _httpVersion = version;
        _fields = fields;
        _contentLength = contentLength;
    }

    protected void recycle()
    {
        _httpVersion = null;
        if (_fields != null)
            _fields.clear();
        _contentLength = Long.MIN_VALUE;
    }

    public boolean isRequest()
    {
        return false;
    }

    public boolean isResponse()
    {
        return false;
    }

    /**
     * @return the HTTP version of this MetaData object
     * @deprecated use {@link #getHttpVersion()} instead
     */
    @Deprecated
    public HttpVersion getVersion()
    {
        return getHttpVersion();
    }

    /**
     * @return the HTTP version of this MetaData object
     */
    public HttpVersion getHttpVersion()
    {
        return _httpVersion;
    }

    /**
     * @param httpVersion the HTTP version to set
     */
    public void setHttpVersion(HttpVersion httpVersion)
    {
        _httpVersion = httpVersion;
    }

    /**
     * @return the HTTP fields of this MetaData object
     */
    public HttpFields getFields()
    {
        return _fields;
    }

    public Supplier<HttpFields> getTrailerSupplier()
    {
        return _trailers;
    }

    public void setTrailerSupplier(Supplier<HttpFields> trailers)
    {
        _trailers = trailers;
    }

    /**
     * @return the content length if available, otherwise {@link Long#MIN_VALUE}
     */
    public long getContentLength()
    {
        if (_contentLength == Long.MIN_VALUE)
        {
            if (_fields != null)
            {
                HttpField field = _fields.getField(HttpHeader.CONTENT_LENGTH);
                _contentLength = field == null ? -1 : field.getLongValue();
            }
        }
        return _contentLength;
    }

    public void setContentLength(long contentLength)
    {
        _contentLength = contentLength;
    }

    /**
     * @return an iterator over the HTTP fields
     * @see #getFields()
     */
    @Override
    public Iterator<HttpField> iterator()
    {
        HttpFields fields = getFields();
        return fields == null ? Collections.emptyIterator() : fields.iterator();
    }

    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field : this)
        {
            out.append(field).append(System.lineSeparator());
        }
        return out.toString();
    }

    public static class Request extends MetaData
    {
        private String _method;
        private HttpURI _uri;

        public Request(HttpFields fields)
        {
            this(null, null, null, fields);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields)
        {
            this(method, uri, version, fields, Long.MIN_VALUE);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            super(version, fields, contentLength);
            _method = method;
            _uri = uri;
        }

        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields)
        {
            this(method, new HttpURI(scheme == null ? null : scheme.asString(),
                hostPort == null ? null : hostPort.getHost(),
                hostPort == null ? -1 : hostPort.getPort(),
                uri), version, fields);
        }

        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            this(method, new HttpURI(scheme == null ? null : scheme.asString(),
                hostPort == null ? null : hostPort.getHost(),
                hostPort == null ? -1 : hostPort.getPort(),
                uri), version, fields, contentLength);
        }

        public Request(String method, String scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            this(method, new HttpURI(scheme,
                hostPort == null ? null : hostPort.getHost(),
                hostPort == null ? -1 : hostPort.getPort(),
                uri), version, fields, contentLength);
        }

        public Request(Request request)
        {
            this(request.getMethod(), new HttpURI(request.getURI()), request.getHttpVersion(), new HttpFields(request.getFields()), request.getContentLength());
        }

        @Override
        public void recycle()
        {
            super.recycle();
            _method = null;
            if (_uri != null)
                _uri.clear();
        }

        @Override
        public boolean isRequest()
        {
            return true;
        }

        /**
         * @return the HTTP method
         */
        public String getMethod()
        {
            return _method;
        }

        /**
         * @param method the HTTP method to set
         */
        public void setMethod(String method)
        {
            _method = method;
        }

        /**
         * @return the HTTP URI
         */
        public HttpURI getURI()
        {
            return _uri;
        }

        /**
         * @return the HTTP URI in string form
         */
        public String getURIString()
        {
            return _uri == null ? null : _uri.toString();
        }

        /**
         * @param uri the HTTP URI to set
         */
        public void setURI(HttpURI uri)
        {
            _uri = uri;
        }

        @Override
        public String toString()
        {
            HttpFields fields = getFields();
            return String.format("%s{u=%s,%s,h=%d,cl=%d}",
                getMethod(), getURI(), getHttpVersion(), fields == null ? -1 : fields.size(), getContentLength());
        }
    }

    public static class Response extends MetaData
    {
        private int _status;
        private String _reason;

        public Response()
        {
            this(null, 0, null);
        }

        public Response(HttpVersion version, int status, HttpFields fields)
        {
            this(version, status, fields, Long.MIN_VALUE);
        }

        public Response(HttpVersion version, int status, HttpFields fields, long contentLength)
        {
            super(version, fields, contentLength);
            _status = status;
        }

        public Response(HttpVersion version, int status, String reason, HttpFields fields, long contentLength)
        {
            super(version, fields, contentLength);
            _reason = reason;
            _status = status;
        }

        @Override
        public boolean isResponse()
        {
            return true;
        }

        /**
         * @return the HTTP status
         */
        public int getStatus()
        {
            return _status;
        }

        /**
         * @return the HTTP reason
         */
        public String getReason()
        {
            return _reason;
        }

        /**
         * @param status the HTTP status to set
         */
        public void setStatus(int status)
        {
            _status = status;
        }

        /**
         * @param reason the HTTP reason to set
         */
        public void setReason(String reason)
        {
            _reason = reason;
        }

        @Override
        public String toString()
        {
            HttpFields fields = getFields();
            return String.format("%s{s=%d,h=%d,cl=%d}", getHttpVersion(), getStatus(), fields == null ? -1 : fields.size(), getContentLength());
        }
    }
}
