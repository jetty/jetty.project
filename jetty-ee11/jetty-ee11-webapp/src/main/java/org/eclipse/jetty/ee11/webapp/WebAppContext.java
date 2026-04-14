package org.eclipse.jetty.ee11.webapp;

import org.eclipse.jetty.ee.servlet.ErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletHandler;
import org.eclipse.jetty.ee.servlet.SessionHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.util.resource.Resource;

public class WebAppContext
    extends org.eclipse.jetty.ee.webapp.WebAppContext
{
    public WebAppContext()
    {
    }

    public WebAppContext(String webApp, String contextPath)
    {
        super(webApp, contextPath);
    }

    public WebAppContext(Resource webApp, String contextPath)
    {
        super(webApp, contextPath);
    }

    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public WebAppContext(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
    }
}
