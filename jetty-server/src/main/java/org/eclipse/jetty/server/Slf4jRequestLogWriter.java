package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Slf4jLog;

public class Slf4jRequestLogWriter extends AbstractLifeCycle implements RequestLog.Writer
{
    private Slf4jLog logger;
    private String loggerName;

    public Slf4jRequestLogWriter()
    {
        // Default logger name (can be set)
        this.loggerName = "org.eclipse.jetty.server.RequestLog";
    }

    public void setLoggerName(String loggerName)
    {
        this.loggerName = loggerName;
    }

    public String getLoggerName()
    {
        return loggerName;
    }

    protected boolean isEnabled()
    {
        return logger != null;
    }

    @Override
    public void write(String requestEntry) throws IOException
    {
        logger.info(requestEntry);
    }

    @Override
    protected synchronized void doStart() throws Exception
    {
        logger = new Slf4jLog(loggerName);
        super.doStart();
    }
}
