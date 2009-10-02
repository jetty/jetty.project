package org.eclipse.jetty.logging.impl;

import java.util.Date;

public interface Formatter
{
    String format(Date date, Severity severity, String name, String message);
}
