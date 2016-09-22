package org.eclipse.jetty.server.handler.gzip;


import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpInput.Content;

public class GzipHttpInputInterceptor implements HttpInput.Interceptor
{
    @Override
    public boolean addContent(Content content)
    {
        return false;
    }
    
}
