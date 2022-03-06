package org.eclipse.jetty.ee10.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

public class ErrorHandler extends Handler.Abstract
{
    public static final String ERROR_CONTEXT = "org.eclipse.jetty.server.error_context";

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        return null;
    }

    public void setServer(Server server)
    {

    }

    public interface ErrorPageMapper
    {
        String getErrorPage(HttpServletRequest request);
    }
} // TODO
