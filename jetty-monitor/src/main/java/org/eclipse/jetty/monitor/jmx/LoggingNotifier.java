//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.monitor.jmx;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * ConsoleNotifier
 * 
 * Provides a way to output notification messages to a log file
 */
public class LoggingNotifier implements EventNotifier
{
    private static final Logger LOG = Log.getLogger(LoggingNotifier.class);

    String _messageFormat;

    /* ------------------------------------------------------------ */
    /**
     * Constructs a new notifier with specified format string
     * 
     * @param format the {@link java.util.Formatter format string}
     * @throws IllegalArgumentException
     */
    public LoggingNotifier(String format)
        throws IllegalArgumentException
    {
        if (format == null)
            throw new IllegalArgumentException("Message format cannot be null");
        
        _messageFormat = format;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.monitor.jmx.EventNotifier#notify(org.eclipse.jetty.monitor.jmx.EventTrigger, org.eclipse.jetty.monitor.jmx.EventState, long)
     */
    public void notify(EventTrigger trigger, EventState<?> state, long timestamp)
    {
        String output = String.format(_messageFormat, state);
        
        LOG.info(output);
    }

}
