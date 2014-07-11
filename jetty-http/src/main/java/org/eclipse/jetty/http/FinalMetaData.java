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


public class FinalMetaData extends AbstractMetaData
{
    private final HttpVersion _version;
    private final HttpFields _fields;
    
    public FinalMetaData(HttpVersion version,HttpFields fields)
    {
        _fields=fields;
        _version=version;
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        return _version;
    }

    @Override
    public HttpFields getFields()
    {
        return _fields;
    }

    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Request extends AbstractMetaData.Request
    {
        private final HttpVersion _version;
        private final HttpFields _fields;
        private final String _method;
        private final HttpURI _uri;
        private final HostPortHttpField _hostPort;
        private final HttpScheme _scheme;

        public Request(HttpVersion version, String method, HttpURI uri, HttpFields fields, HostPortHttpField hostPort)
        {
            _fields=fields;
            _version=version;
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

        public Request(HttpVersion version, HttpScheme scheme, String method, HostPortHttpField authority, String path, HttpFields fields)
        {
            this(version,scheme,method,authority,new HttpURI(path),fields);
        }
        
        public Request(HttpVersion version, HttpScheme scheme, String method, HostPortHttpField authority, HttpURI path, HttpFields fields)
        {
            _fields=fields;
            _version=version;
            _method=method;
            _uri=path; 
            _hostPort = authority;
            _scheme=scheme;
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return _version;
        }

        @Override
        public HttpFields getFields()
        {
            return _fields;
        }

        @Override
        public String getMethod()
        {
            return _method;
        }

        @Override
        public HttpScheme getScheme()
        {
            return _scheme;
        }

        @Override
        public String getHost()
        {
            return _hostPort==null?null:_hostPort.getHost();
        }

        @Override
        public int getPort()
        {
            return _hostPort==null?0:_hostPort.getPort();
        }

        @Override
        public HttpURI getURI()
        {
            return _uri;
        }
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Response extends AbstractMetaData.Response
    {
        private final HttpVersion _version;
        private final HttpFields _fields;
        private final int _status;

        public Response(HttpVersion version, int status, HttpFields fields)
        {
            _fields=fields;
            _version=version;
            _status=status;
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return _version;
        }

        @Override
        public HttpFields getFields()
        {
            return _fields;
        }
        
        public int getStatus()
        {
            return _status;
        }
    }
}
