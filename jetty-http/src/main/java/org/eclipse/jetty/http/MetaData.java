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
import java.util.List;


/* ------------------------------------------------------------ */
/**
 */
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
    public boolean equals(Object o)
    {
        if (!(o instanceof MetaData))
            return false;
        MetaData m = (MetaData)o;

        HttpFields lm=m.getFields();
        int s=0;
        for (HttpField field: this)
        {
            s++;
            if (!lm.contains(field))
                return false;
        }
            
        if (s!=lm.size())
            return false;
            
        return true;
    }
    
    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field: this)
            out.append(field).append('\n');
        return out.toString();
    }
    
    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public static class Request extends MetaData
    {
        private final String _method;
        private final HttpScheme _scheme;
        private final String _host;
        private final int _port;
        private final HttpURI _uri;

        public Request(HttpVersion version, String method, HttpURI uri, HttpFields fields,String host, int port)
        {
            super(version,fields);
            _host=host;
            _port=port;
            _method=method;
            _uri=uri;
            String scheme=uri.getScheme();
            if (scheme==null)
                _scheme=HttpScheme.HTTP;
            else
            {
                HttpScheme s = HttpScheme.CACHE.get(scheme);
                _scheme=s==null?HttpScheme.HTTP:s;
            }
        }
        
        public Request(HttpVersion version, HttpScheme scheme, String method, String authority, String host, int port, String path, HttpFields fields)
        {
            super(version,fields);
            _host=host;
            _port=port;
            _method=method;
            _uri=new HttpURI(path); // TODO - this is not so efficient!
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
            return _host;
        }

        public int getPort()
        {
            return _port;
        }
        
        public HttpURI getURI()
        {
            return _uri;
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Request))
                return false;
            Request r = (Request)o;
            if (!_method.equals(r._method) || 
                !_scheme.equals(r._scheme) ||
                !_uri.equals(r._uri))
                return false;
            return super.equals(o);
        }
        
        @Override
        public String toString()
        {
            return _method+" "+_scheme+"://"+_host+':'+_port+_uri+" HTTP/2\n"+super.toString();
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
        public boolean equals(Object o)
        {
            if (!(o instanceof Response))
                return false;
            Response r = (Response)o;
            if (_status!=r._status)
                return false;
            return super.equals(o);
        }
        @Override
        public String toString()
        {
            return "HTTP/2 "+_status+"\n"+super.toString();
        }
    }
}
