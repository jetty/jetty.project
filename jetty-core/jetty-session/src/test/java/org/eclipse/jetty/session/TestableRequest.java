package org.eclipse.jetty.session;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;

public class TestableRequest implements Request
{
    @Override
    public Object removeAttribute(String name)
    {
        return null;
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return null;
    }

    @Override
    public Object getAttribute(String name)
    {
        return null;
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return null;
    }

    @Override
    public void clearAttributes()
    {
    }

    @Override
    public String getId()
    {
        return null;
    }

    @Override
    public ConnectionMetaData getConnectionMetaData()
    {
        return null;
    }

    @Override
    public String getMethod()
    {
        return null;
    }

    @Override
    public HttpURI getHttpURI()
    {
        return null;
    }

    @Override
    public Context getContext()
    {
        return null;
    }

    @Override
    public String getPathInContext()
    {
        return null;
    }

    @Override
    public HttpFields getHeaders()
    {
        return null;
    }

    public List<HttpCookie> getCookies()
    {
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
        return false;
    }

    @Override
    public long getContentLength()
    {
        return 0;
    }

    @Override
    public Content readContent()
    {
        return null;
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
    }

    @Override
    public boolean addErrorListener(Predicate<Throwable> onError)
    {
        return false;
    }

    @Override
    public void push(org.eclipse.jetty.http.MetaData.Request request)
    {
    }

    @Override
    public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
    {
    }
}
