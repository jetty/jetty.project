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


package org.eclipse.jetty.http2.hpack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;


/* ------------------------------------------------------------ */
/**
 */
public class MetaData implements Iterable<HttpField>
{
    private final Iterable<HttpField> _fields;
    
    public MetaData(Iterable<HttpField> fields)
    {
        _fields=fields;
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

    public List<HttpField> getFields()
    {
        if (_fields instanceof List)
            return (List<HttpField>)_fields;
        ArrayList<HttpField> list = new ArrayList<>();
        for (HttpField field:_fields)
            list.add(field);
        return list;
    }
    
    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Request extends MetaData
    {
        private final String _method;
        private final HttpScheme _scheme;
        private final String _authority;
        private final String _host;
        private final int _port;
        private final String _path;

        public Request(HttpScheme scheme, String method, String authority, String host, int port, String path, Iterable<HttpField> fields)
        {
            super(fields);
            _authority=authority;
            _host=host;
            _port=port;
            _method=method;
            _path=path;
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

        public String getAuthority()
        {
            return _authority;
        }

        public String getHost()
        {
            return _host;
        }

        public int getPort()
        {
            return _port;
        }
        
        public String getPath()
        {
            return _path;
        }
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Response extends MetaData
    {
        private final int _status;

        public Response(int status, HttpFields fields)
        {
            super(fields);
            _status=status;
        }
        
        public Response(int status, Iterable<HttpField> fields)
        {
            super(fields);
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
    }
}
