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

import static java.util.UUID.randomUUID;

/* ------------------------------------------------------------ */
/**
 * EventTrigger
 * 
 * Abstract base class for all EventTrigger implementations.
 * Used to determine whether the necessary conditions for  
 * triggering an event are present.
 */
public abstract class EventTrigger
{
    private final String _id;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger
     */
    public EventTrigger()
    {
        _id = randomUUID().toString();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the identification string of the event trigger
     * 
     * @return unique identification string
     */
    public String getID()
    {
        return _id;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Abstract method to verify if the event trigger conditions
     * are in the appropriate state for an event to be triggered
     * 
     * @return true to trigger an event
     */
    public abstract boolean match(long timestamp) throws Exception;

    /* ------------------------------------------------------------ */
    /**
     * Retrieve the event state associated with specified invocation
     * of the event trigger match method
     * 
     * @param timestamp time stamp associated with invocation
     * @return event state or null if not found
     */
    public abstract EventState<?> getState(long timestamp);
}
