package org.eclipse.jetty.session;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

public class TestableRequest implements Request
{
    @Override
    public Object removeAttribute(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object getAttribute(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void clearAttributes()
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public String getId()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public HttpChannel getHttpChannel()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ConnectionMetaData getConnectionMetaData()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getMethod()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public HttpURI getHttpURI()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Context getContext()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getPathInContext()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public HttpFields getHeaders()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public List<HttpCookie> getCookies()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getTimeStamp()
    {
        return 0;
    }

    @Override
    public boolean isSecure()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public long getContentLength()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Content readContent()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public boolean addErrorListener(Predicate<Throwable> onError)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void addCompletionListener(Callback onComplete)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void push(org.eclipse.jetty.http.MetaData.Request request)
    {
        // TODO Auto-generated method stub
    }
}