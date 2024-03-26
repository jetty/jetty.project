package org.eclipse.jetty.ee10.log4j2;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(LogServlet.class);

    @Override
    public void init() throws ServletException
    {
        LOG.info("#### init()");
    }
}
