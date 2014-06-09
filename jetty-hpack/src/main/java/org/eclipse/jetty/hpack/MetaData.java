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


package org.eclipse.jetty.hpack;

import java.util.Iterator;

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

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Request extends MetaData
    {
        private final HttpMethod _method;
        private final String _methodString;
        private final HttpScheme _scheme;
        private final String _authority;
        private final String _path;

        public Request(HttpScheme scheme, HttpMethod method, String methodString, String authority, String path, Iterable<HttpField> fields)
        {
            super(fields);
            _authority=authority;
            _method=method;
            _methodString=methodString;
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
        
        public HttpMethod getMethod()
        {
            return _method;
        }
        
        public String getMethodString()
        {
            return _methodString;
        }

        public HttpScheme getScheme()
        {
            return _scheme;
        }

        public String getAuthority()
        {
            return _authority;
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
