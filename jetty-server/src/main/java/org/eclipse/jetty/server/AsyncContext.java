
package org.eclipse.jetty.server;

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/* temporary interface in anticipation of servlet 3.0 */
public interface AsyncContext 
{
    static final String ASYNC_REQUEST_URI = "javax.servlet.async.request_uri";
    static final String ASYNC_CONTEXT_PATH = "javax.servlet.async.context_path";
    static final String ASYNC_PATH_INFO = "javax.servlet.async.path_info";
    static final String ASYNC_SERVLET_PATH = "javax.servlet.async.servlet_path";
    static final String ASYNC_QUERY_STRING = "javax.servlet.async.query_string";

    public ServletRequest getRequest();
    public ServletResponse getResponse();
    public boolean hasOriginalRequestAndResponse();
    public void dispatch();
    public void dispatch(String path);
    public void dispatch(ServletContext context, String path);
    public void complete();
    public void start(Runnable run);
}


