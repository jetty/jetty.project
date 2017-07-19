//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.Set;

import javax.servlet.http.PushBuilder;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 */
public class PushBuilderImpl implements PushBuilder
{
    private static final Logger LOG = Log.getLogger(PushBuilderImpl.class);

    private final static HttpField JettyPush = new HttpField("x-http2-push","PushBuilder");

    private final Request _request;
    private final HttpFields _fields;
    private String _method;
    private String _queryString;
    private String _sessionId;
    private String _path;
    private String _lastModified;

    public PushBuilderImpl(Request request, HttpFields fields, String method, String queryString, String sessionId)
    {
        super();
        _request = request;
        _fields = fields;
        _method = method;
        _queryString = queryString;
        _sessionId = sessionId;
        _fields.add(JettyPush);
        if (LOG.isDebugEnabled())
            LOG.debug("PushBuilder({} {}?{} s={} c={})",_method,_request.getRequestURI(),_queryString,_sessionId);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getMethod()
    {
        return _method;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder method(String method)
    {
        _method = method;
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getQueryString()
    {
        return _queryString;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder queryString(String queryString)
    {
        _queryString = queryString;
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getSessionId()
    {
        return _sessionId;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder sessionId(String sessionId)
    {
        _sessionId = sessionId;
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<String> getHeaderNames()
    {
        return _fields.getFieldNamesCollection();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getHeader(String name)
    {
        return _fields.get(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder setHeader(String name,String value)
    {
        _fields.put(name,value);
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder addHeader(String name,String value)
    {
        _fields.add(name,value);
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder removeHeader(String name)
    {
        _fields.remove(name);
        return this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getPath()
    {
        return _path;
    }

    /* ------------------------------------------------------------ */
    @Override
    public PushBuilder path(String path)
    {
        _path = path;
        return this;
    }


    /* ------------------------------------------------------------ */
    @Override
    public void push()
    {
        if (HttpMethod.POST.is(_method) || HttpMethod.PUT.is(_method))
            throw new IllegalStateException("Bad Method "+_method);

        if (_path==null || _path.length()==0)
            throw new IllegalStateException("Bad Path "+_path);

        String path=_path;
        String query=_queryString;
        int q=path.indexOf('?');
        if (q>=0)
        {
            query=(query!=null && query.length()>0)?(path.substring(q+1)+'&'+query):path.substring(q+1);
            path=path.substring(0,q);
        }

        if (!path.startsWith("/"))
            path=URIUtil.addPaths(_request.getContextPath(),path);

        String param=null;
        if (_sessionId!=null)
        {
            if (_request.isRequestedSessionIdFromURL())
                param="jsessionid="+_sessionId;
            // TODO else
            //      _rawFields.add("Cookie","JSESSIONID="+_sessionId);
        }

        HttpURI uri = HttpURI.createHttpURI(_request.getScheme(),_request.getServerName(),_request.getServerPort(),path,param,query,null);
        MetaData.Request push = new MetaData.Request(_method,uri,_request.getHttpVersion(),_fields);

        if (LOG.isDebugEnabled())
            LOG.debug("Push {} {} inm={} ims={}",_method,uri,_fields.get(HttpHeader.IF_NONE_MATCH),_fields.get(HttpHeader.IF_MODIFIED_SINCE));

        _request.getHttpChannel().getHttpTransport().push(push);
        _path=null;
        _lastModified=null;
    }

}
