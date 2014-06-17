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
    private final HttpVersion _version;
    private final HttpFields _fields;
    
    public MetaData(HttpVersion version,HttpFields fields)
    {
        _fields=fields;
        _version=version;
    }
    
    public HttpVersion getHttpVersion()
    {
        return _version;
    }
    
    public boolean isRequest()
    {
        return false;
    }
    
    public boolean isResponse()
    {
        return false;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return _fields.iterator();
    }

    public HttpFields getFields()
    {
        return _fields;
    }
    
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
        private final String _method;
        private final HttpURI _uri;
        private final HostPortHttpField _hostPort;
        private final HttpScheme _scheme;

        public Request(HttpVersion version, String method, HttpURI uri, HttpFields fields, HostPortHttpField hostPort)
        {
            super(version,fields);
            _method=method;
            _uri=uri;
            _hostPort = hostPort;
            String scheme = uri.getScheme();
            if (scheme == null)
            {
                _scheme = HttpScheme.HTTP;
            }
            else
            {
                HttpScheme s = HttpScheme.CACHE.get(scheme);
                _scheme = s == null ? HttpScheme.HTTP : s;
            }
        }

        // TODO: review this constructor: host/port parameters are ignored, and code duplication should be avoided.
        public Request(HttpVersion version, HttpScheme scheme, String method, String authority, String host, int port, String path, HttpFields fields)
        {
            super(version,fields);
            _method=method;
            _uri=new HttpURI(path); // TODO - this is not so efficient!
            _hostPort = new HostPortHttpField(authority);
            _scheme=scheme;
        }

        @Override
        public boolean isRequest()
        {
            return true;
        }

        @Override
        public boolean isResponse()
        {
            return false;
        }
        
        public String getMethod()
        {
            return _method;
        }

        public HttpScheme getScheme()
        {
            return _scheme;
        }

        public String getHost()
        {
            return _hostPort.getHost();
        }

        public int getPort()
        {
            return _hostPort.getPort();
        }
        
        public HttpURI getURI()
        {
            return _uri;
        }

        @Override
        public String toString()
        {
            return String.format("%s %s://%s:%d%s HTTP/2%s%s",
                    getMethod(), getScheme(), getHost(), getPort(), getURI(), System.lineSeparator(), super.toString());
        }
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Response extends MetaData
    {
        private final int _status;

        public Response(HttpVersion version, int status, HttpFields fields)
        {
            super(version,fields);
            _status=status;
        }
        
        @Override
        public boolean isRequest()
        {
            return false;
        }

        @Override
        public boolean isResponse()
        {
            return true;
        }

        public int getStatus()
        {
            return _status;
        }

        @Override
        public String toString()
        {
            return String.format("HTTP/2 %d%s%s", getStatus(), System.lineSeparator(), super.toString());
        }
    }
}
