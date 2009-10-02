package org.eclipse.jetty.webapp.logging;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.eclipse.jetty.logging.impl.Formatter;
import org.eclipse.jetty.logging.impl.Severity;
import org.slf4j.MDC;

public class WebappContextLogFormatter implements Formatter
{
    private String dateFormat = "yyyy-MM-dd HH:mm:ss.SSS";

    public String format(Date date, Severity severity, String name, String message)
    {
        // Take the information out of the NDC/MDC and log it, along with the standard log message
        StringBuilder buf = new StringBuilder();

        buf.append(severity.name()).append(' ');
        buf.append(new SimpleDateFormat(dateFormat).format(date)).append(' ');
        buf.append('[').append(name).append("] ");

        String target = MDC.get("target");
        String userAddr = MDC.get("remoteAddr");
        String userName = MDC.get("remoteUser");
        String principal = MDC.get("principal");
        String contextPath = MDC.get("contextPath");
        if ((target != null) || (contextPath != null) || (userAddr != null) || (userName != null) || (principal != null))
        {
            buf.append('[');
            // The user info
            if (principal != null)
            {
                buf.append(principal).append(':');
            }
            if (userName != null)
            {
                buf.append(userName).append(':');
            }
            if (userAddr != null)
            {
                buf.append(userAddr).append(':');
            }
            // The path requested
            if (contextPath != null)
            {
                buf.append(contextPath);
            }
            buf.append(target).append("] ");
        }

        buf.append(message);
        return buf.toString();
    }

    public String getDateFormat()
    {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat)
    {
        this.dateFormat = dateFormat;
    }
}
