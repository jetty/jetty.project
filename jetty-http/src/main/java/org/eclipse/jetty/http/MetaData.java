//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.Objects;

public class MetaData implements Iterable<HttpField>
{
    private HttpVersion _httpVersion;
    private HttpFields _fields;
    private long _contentLength;

    public MetaData()
    {
        this(null, null);
    }

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
     */
    public HttpVersion getVersion()
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

    /**
     * @param fields the HTTP fields to set
     */
    public void setFields(HttpFields fields)
    {
        _fields = fields;
        _contentLength = Long.MIN_VALUE;
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

    /**
     * @return an iterator over the HTTP fields
     * @see #getFields()
     */
    public Iterator<HttpField> iterator()
    {
        HttpFields fields = getFields();
        return fields == null ? Collections.<HttpField>emptyIterator() : fields.iterator();
    }

    @Override
    public int hashCode()
    {
        HttpVersion version = getVersion();
        int hash = version == null ? 0 : version.hashCode();
        HttpFields fields = getFields();
        return 31 * hash + (fields == null ? 0 : fields.hashCode());
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof MetaData))
            return false;

        MetaData that = (MetaData)o;

        if (getVersion() != that.getVersion())
            return false;

        return Objects.equals(getFields(), that.getFields());
    }

    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field : this)
            out.append(field).append(System.lineSeparator());
        return out.toString();
    }

    public static class Request extends MetaData
    {
        private String _method;
        private HttpURI _uri;

        public Request()
        {
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
            this(method, new HttpURI(scheme == null ? null : scheme.asString(), hostPort.getHost(), hostPort.getPort(), uri), version, fields);
        }

        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            this(method, new HttpURI(scheme == null ? null : scheme.asString(), hostPort.getHost(), hostPort.getPort(), uri), version, fields, contentLength);
        }

        public Request(String method, String scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            this(method, new HttpURI(scheme, hostPort.getHost(), hostPort.getPort(), uri), version, fields, contentLength);
        }

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
        public int hashCode()
        {
            int hash = super.hashCode();
            hash = hash * 31 + (_method == null ? 0 : _method.hashCode());
            return hash * 31 + (_uri == null ? 0 : _uri.hashCode());
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof MetaData.Request))
                return false;
            MetaData.Request that = (MetaData.Request)o;
            if (!Objects.equals(getMethod(), that.getMethod()) ||
                    !Objects.equals(getURI(), that.getURI()))
                return false;
            return super.equals(o);
        }

        @Override
        public String toString()
        {
            return String.format("%s %s %s%s%s",
                    getMethod(), getURI(), getVersion(), System.lineSeparator(), super.toString());
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
        public int hashCode()
        {
            return 31 * super.hashCode() + getStatus();
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof MetaData.Response))
                return false;
            MetaData.Response that = (MetaData.Response)o;
            if (getStatus() != that.getStatus())
                return false;
            return super.equals(o);
        }

        @Override
        public String toString()
        {
            return String.format("%s %d%s%s", getVersion(), getStatus(), System.lineSeparator(), super.toString());
        }
    }
}
