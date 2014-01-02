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


/* ------------------------------------------------------------ */
/**
 * ConsoleNotifier
 * 
 * Provides a way to output notification messages to the server console
 */
public class ConsoleNotifier implements EventNotifier
{
    String _messageFormat;
    

    /* ------------------------------------------------------------ */
    /**
     * Constructs a new notifier with specified format string
     * 
     * @param format the {@link java.util.Formatter format string}
     * @throws IllegalArgumentException
     */
    public ConsoleNotifier(String format)
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
        String output = String.format("%1$tF %1$tT.%1$tL:NOTIFY::", timestamp);
        
        output += String.format(_messageFormat, state);         
        
        System.out.println(output);
    }
}
