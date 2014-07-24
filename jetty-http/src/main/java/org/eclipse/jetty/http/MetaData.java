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

package org.eclipse.jetty.http;

import java.util.Iterator;

public class MetaData implements Iterable<HttpField>
{
    private HttpVersion _httpVersion;
    private HttpFields _fields;

    /* ------------------------------------------------------------ */
    public MetaData()
    {
    }
    
    /* ------------------------------------------------------------ */
    public MetaData(HttpVersion version, HttpFields fields)
    {
        _httpVersion = version;
        _fields = fields;
    }

    /* ------------------------------------------------------------ */
    public boolean isRequest()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isResponse()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    /** Get the httpVersion.
     * @return the httpVersion
     */
    public HttpVersion getVersion()
    {
        return _httpVersion;
    }

    /* ------------------------------------------------------------ */
    /** Set the httpVersion.
     * @param httpVersion the httpVersion to set
     */
    public void setHttpVersion(HttpVersion httpVersion)
    {
        _httpVersion = httpVersion;
    }

    /* ------------------------------------------------------------ */
    /** Get the fields.
     * @return the fields
     */
    public HttpFields getFields()
    {
        return _fields;
    }

    /* ------------------------------------------------------------ */
    /** Set the fields.
     * @param fields the fields to set
     */
    public void setFields(HttpFields fields)
    {
        _fields = fields;
    }

    /* ------------------------------------------------------------ */
    public Iterator<HttpField> iterator()
    {
        return getFields().iterator();
    }

    /* ------------------------------------------------------------ */
    @Override
    public int hashCode()
    {
        return 31 * getVersion().hashCode() + getFields().hashCode();
    }

    /* ------------------------------------------------------------ */
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

        return getFields().equals(that.getFields());
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field: this)
            out.append(field).append(System.lineSeparator());
        return out.toString();
    }
    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Request extends MetaData
    {
        private String _method;
        private HttpURI _uri;

        public Request()
        {    
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @param method
         * @param uri
         * @param version
         * @param fields
         */
        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields)
        {
            super(version,fields);
            _method = method;
            _uri = uri;
        }

        /* ------------------------------------------------------------ */
        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields)
        {
            this(method,new HttpURI(scheme==null?null:scheme.asString(),hostPort.getHost(),hostPort.getPort(),uri),version,fields);
        }

        /* ------------------------------------------------------------ */
        public Request(String method, String scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFields fields)
        {
            this(method,new HttpURI(scheme,hostPort.getHost(),hostPort.getPort(),uri),version,fields);
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean isRequest()
        {
            return true;
        }

        /* ------------------------------------------------------------ */
        /** Get the method.
         * @return the method
         */
        public String getMethod()
        {
            return _method;
        }

        /* ------------------------------------------------------------ */
        /** Set the method.
         * @param method the method to set
         */
        public void setMethod(String method)
        {
            _method = method;
        }

        /* ------------------------------------------------------------ */
        /** Get the uri.
         * @return the uri
         */
        public HttpURI getURI()
        {
            return _uri;
        }

        /* ------------------------------------------------------------ */
        /** Set the uri.
         * @param uri the uri to set
         */
        public void setURI(HttpURI uri)
        {
            _uri = uri;
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public int hashCode()
        {
            return ((super.hashCode()*31)+_method.hashCode())*31+_uri.hashCode();
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof MetaData.Request))
                return false;
            MetaData.Request that = (MetaData.Request)o;
            if (!getMethod().equals(that.getMethod()) ||
                !getURI().equals(that.getURI()))
                return false;
            return super.equals(o);
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return String.format("%s %s %s%s%s",
                    getMethod(), getURI(), getVersion(), System.lineSeparator(), super.toString());
        }
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Response extends MetaData
    {
        private int _status;

        public Response()
        {
        }

        /* ------------------------------------------------------------ */
        /**
         * @param version
         * @param fields
         * @param status
         */
        public Response(HttpVersion version, int status, HttpFields fields)
        {
            super(version,fields);
            _status=status;
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean isResponse()
        {
            return true;
        }
        
        /* ------------------------------------------------------------ */
        /** Get the status.
         * @return the status
         */
        public int getStatus()
        {
            return _status;
        }

        /* ------------------------------------------------------------ */
        /** Set the status.
         * @param status the status to set
         */
        public void setStatus(int status)
        {
            _status = status;
        }

        /* ------------------------------------------------------------ */
        @Override
        public int hashCode()
        {
            return 31 * getStatus() + super.hashCode();
        }

        /* ------------------------------------------------------------ */
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

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return String.format("%s %d%s%s",getVersion(), getStatus(), System.lineSeparator(), super.toString());
        }
        
    }
}
