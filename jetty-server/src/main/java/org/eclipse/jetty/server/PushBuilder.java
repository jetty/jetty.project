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

package org.eclipse.jetty.server;

import java.util.Collection;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.URIUtil;


/* ------------------------------------------------------------ */
/** 
 * 
 */
public class PushBuilder
{    
    private final Request _request;
    private final HttpFields _fields;
    private String _method;
    private String _queryString;
    private String _sessionId;
    private boolean _conditional;
    
    public PushBuilder(Request request, HttpFields fields, String method, String queryString, String sessionId, boolean conditional)
    {
        super();
        _request = request;
        _fields = fields;
        _method = method;
        _queryString = queryString;
        _sessionId = sessionId;
        _conditional = conditional;
    }

    public String getMethod()
    {
        return _method;
    }
    
    public void setMethod(String method)
    {
        _method = method;
    }
    public String getQueryString()
    {
        return _queryString;
    }
    public void setQueryString(String queryString)
    {
        _queryString = queryString;
    }
    public String getSessionId()
    {
        return _sessionId;
    }
    public void setSessionId(String sessionId)
    {
        _sessionId = sessionId;
    }
    public boolean isConditional()
    {
        return _conditional;
    }
    public void setConditional(boolean conditional)
    {
        _conditional = conditional;
    }
    
    public Collection<String> getHeaderNames()
    {
        return _fields.getFieldNamesCollection();
    }
    
    public String getHeader(String name)
    {
        return _fields.get(name);
    }
    
    public void setHeader(String name,String value)
    {
        _fields.put(name,value);
    }
    
    public void addHeader(String name,String value)
    {
        _fields.add(name,value);
    }
    
    /* ------------------------------------------------------------ */
    /** Push a resource.
     * Push a resource based on the current state of the PushBuilder.  If {@link #isConditional()}
     * is true and an etag or lastModified value is provided, then an appropriate conditional header
     * will be generated. If an etag and lastModified value are provided only an If-None-Match header
     * will be generated. If the builder has a session ID, then the pushed request
     * will include the session ID either as a Cookie or as a URI parameter as appropriate.The builders
     * query string is merged with any passed query string.
     * @param uriInContext The URI within the current context of the resource to push.
     * @param etag The etag for the resource or null if not available
     * @param lastModified The last modified date of the resource or null if not available
     * @throws IllegalArgumentException if the method set expects a request 
     * body (eg POST)
     */
    public void push(String uriInContext,String etag,String lastModified)
    {
        if (HttpMethod.POST.is(_method) || HttpMethod.PUT.is(_method))
            throw new IllegalStateException("Bad Method "+_method);
        
        String query=_queryString;
        int q=uriInContext.indexOf('?');
        if (q>=0)
        {
            query=uriInContext.substring(q+1)+'&'+query;
            uriInContext=uriInContext.substring(0,q);
        }
        
        String path = URIUtil.addPaths(_request.getContextPath(),uriInContext);
        
        String param=null;
        if (_sessionId!=null && _request.isRequestedSessionIdFromURL())
            param="jsessionid="+_sessionId;
        
        if (_conditional)
        {
            if (etag!=null)
                _fields.add(HttpHeader.IF_NONE_MATCH,etag);
            else if (lastModified!=null)
                _fields.add(HttpHeader.IF_MODIFIED_SINCE,lastModified);
        }
        
        HttpURI uri = HttpURI.createHttpURI(_request.getScheme(),_request.getServerName(),_request.getServerPort(),path,param,query,null);
        MetaData.Request push = new MetaData.Request(_method,uri,_request.getHttpVersion(),_fields);
        _request.getHttpChannel().getHttpTransport().push(push);
        
    }
    
    
}
