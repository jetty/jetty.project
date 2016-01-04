//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
    private boolean _conditional;
    private String _path;
    private String _etag;
    private String _lastModified;
    
    public PushBuilderImpl(Request request, HttpFields fields, String method, String queryString, String sessionId, boolean conditional)
    {
        super();
        _request = request;
        _fields = fields;
        _method = method;
        _queryString = queryString;
        _sessionId = sessionId;
        _conditional = conditional;
        _fields.add(JettyPush);
        if (LOG.isDebugEnabled())
            LOG.debug("PushBuilder({} {}?{} s={} c={})",_method,_request.getRequestURI(),_queryString,_sessionId,_conditional);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getMethod()
     */
    @Override
    public String getMethod()
    {
        return _method;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#method(java.lang.String)
     */
    @Override
    public PushBuilder method(String method)
    {
        _method = method;
        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getQueryString()
     */
    @Override
    public String getQueryString()
    {
        return _queryString;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#queryString(java.lang.String)
     */
    @Override
    public PushBuilder queryString(String queryString)
    {
        _queryString = queryString;
        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getSessionId()
     */
    @Override
    public String getSessionId()
    {
        return _sessionId;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#sessionId(java.lang.String)
     */
    @Override
    public PushBuilder sessionId(String sessionId)
    {
        _sessionId = sessionId;
        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#isConditional()
     */
    @Override
    public boolean isConditional()
    {
        return _conditional;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#conditional(boolean)
     */
    @Override
    public PushBuilder conditional(boolean conditional)
    {
        _conditional = conditional;
        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getHeaderNames()
     */
    @Override
    public Set<String> getHeaderNames()
    {
        return _fields.getFieldNamesCollection();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getHeader(java.lang.String)
     */
    @Override
    public String getHeader(String name)
    {
        return _fields.get(name);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#setHeader(java.lang.String, java.lang.String)
     */
    @Override
    public PushBuilder setHeader(String name,String value)
    {
        _fields.put(name,value);
        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#addHeader(java.lang.String, java.lang.String)
     */
    @Override
    public PushBuilder addHeader(String name,String value)
    {
        _fields.add(name,value);
        return this;
    }

    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getPath()
     */
    @Override
    public String getPath()
    {
        return _path;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#path(java.lang.String)
     */
    @Override
    public PushBuilder path(String path)
    {
        _path = path;
        return this;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getEtag()
     */
    @Override
    public String getEtag()
    {
        return _etag;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#etag(java.lang.String)
     */
    @Override
    public PushBuilder etag(String etag)
    {
        _etag = etag;
        return this;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#getLastModified()
     */
    @Override
    public String getLastModified()
    {
        return _lastModified;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#lastModified(java.lang.String)
     */
    @Override
    public PushBuilder lastModified(String lastModified)
    {
        _lastModified = lastModified;
        return this;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.PushBuilder#push()
     */
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
            query=(query!=null && query.length()>0)?(_path.substring(q+1)+'&'+query):_path.substring(q+1);
            path=_path.substring(0,q);
        }
        
        if (!path.startsWith("/"))
            path=URIUtil.addPaths(_request.getContextPath(),path);
        
        String param=null;
        if (_sessionId!=null)
        {
            if (_request.isRequestedSessionIdFromURL())
                param="jsessionid="+_sessionId;
            // TODO else 
            //      _fields.add("Cookie","JSESSIONID="+_sessionId);
        }
        
        if (_conditional)
        {
            if (_etag!=null)
                _fields.add(HttpHeader.IF_NONE_MATCH,_etag);
            else if (_lastModified!=null)
                _fields.add(HttpHeader.IF_MODIFIED_SINCE,_lastModified);
        }
        
        HttpURI uri = HttpURI.createHttpURI(_request.getScheme(),_request.getServerName(),_request.getServerPort(),_path,param,query,null);
        MetaData.Request push = new MetaData.Request(_method,uri,_request.getHttpVersion(),_fields);
        
        if (LOG.isDebugEnabled())
            LOG.debug("Push {} {} inm={} ims={}",_method,uri,_fields.get(HttpHeader.IF_NONE_MATCH),_fields.get(HttpHeader.IF_MODIFIED_SINCE));
        
        _request.getHttpChannel().getHttpTransport().push(push);
        _path=null;
        _etag=null;
        _lastModified=null;
    }
}
