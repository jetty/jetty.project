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

import java.security.InvalidParameterException;

/* ------------------------------------------------------------ */
/**
 * MonitorAction
 * 
 * Abstract base class for all MonitorAction implementations. 
 * Receives notification when an associated EventTrigger is matched.
 */
public abstract class MonitorAction
    extends NotifierGroup
{
    public static final int DEFAULT_POLL_INTERVAL = 5000;
    
    private final String _id;
    private final EventTrigger _trigger;
    private final EventNotifier _notifier;
    private final long _pollInterval;
    private final long _pollDelay;

    /* ------------------------------------------------------------ */
    /**
     * Creates a new monitor action 
     * 
     * @param trigger event trigger to be associated with this action
     * @throws InvalidParameterException
     */
    public MonitorAction(EventTrigger trigger)
        throws InvalidParameterException
    {
        this(trigger, null, 0, 0);
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Creates a new monitor action 
     * 
     * @param trigger event trigger to be associated with this action
     * @param notifier event notifier to be associated with this action
     * @throws InvalidParameterException
     */
    public MonitorAction(EventTrigger trigger, EventNotifier notifier)
        throws InvalidParameterException
    {
        this(trigger, notifier, 0);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Creates a new monitor action 
     * 
     * @param trigger event trigger to be associated with this action
     * @param notifier event notifier to be associated with this action
     * @param pollInterval interval for polling of the JMX server
     * @throws InvalidParameterException
     */
    public MonitorAction(EventTrigger trigger, EventNotifier notifier, long pollInterval)
        throws InvalidParameterException
    {
        this(trigger, notifier, pollInterval, 0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Creates a new monitor action 
     * 
     * @param trigger event trigger to be associated with this action
     * @param notifier event notifier to be associated with this action
     * @param pollInterval interval for polling of the JMX server
     * @param pollDelay delay before starting to poll the JMX server
     * @throws InvalidParameterException
     */
    public MonitorAction(EventTrigger trigger, EventNotifier notifier, long pollInterval, long pollDelay)
        throws InvalidParameterException
    {
        if (trigger == null)
            throw new InvalidParameterException("Trigger cannot be null");
        
        _id = randomUUID().toString();
        _trigger = trigger;
        _notifier = notifier;
        _pollInterval = pollInterval > 0 ? pollInterval : DEFAULT_POLL_INTERVAL;
        _pollDelay = pollDelay > 0 ? pollDelay : _pollInterval;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the identification string of the monitor action
     * 
     * @return unique identification string
     */

    public final String getID()
    {
        return _id;
    }
    
   
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the event trigger of the monitor action
     * 
     * @return associated event trigger
     */
    public EventTrigger getTrigger()
    {
        return _trigger;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the poll interval
     * 
     * @return interval value (in milliseconds)
     */
    public long getPollInterval()
    {
        return _pollInterval;
    }
    

    /* ------------------------------------------------------------ */
    /** Retrieve the poll delay
     * @return delay value (in milliseconds)
     */
    public long getPollDelay()
    {
        return _pollDelay;
    }

    /* ------------------------------------------------------------ */
    /**
     * This method will be called when event trigger associated
     * with this monitor action matches its conditions.
     * 
     * @param timestamp time stamp of the event
     */
    public final void doExecute(long timestamp)
    {
        EventState<?> state =_trigger.getState(timestamp);
        if (_notifier != null)
            _notifier.notify(_trigger, state, timestamp);
        execute(_trigger, state, timestamp);
    }

    /* ------------------------------------------------------------ */
    /**
     * This method will be called to allow subclass to execute
     * the desired action in response to the event.
     * 
     * @param trigger event trigger associated with this monitor action
     * @param state event state associated with current invocation of event trigger
     * @param timestamp time stamp of the current invocation of event trigger
     */
    public abstract void execute(EventTrigger trigger, EventState<?> state, long timestamp);
 }
