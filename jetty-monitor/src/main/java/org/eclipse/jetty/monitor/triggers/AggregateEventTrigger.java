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

package org.eclipse.jetty.monitor.triggers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.monitor.jmx.EventState;
import org.eclipse.jetty.monitor.jmx.EventTrigger;


/* ------------------------------------------------------------ */
/**
 * AggregateEventTrigger
 * 
 * EventTrigger aggregation that executes every aggregated event
 * triggers in left to right order, and returns match if any one
 * of them have returned match.   
 */
public class AggregateEventTrigger extends EventTrigger
{
    protected final List<EventTrigger> _triggers;

    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger
     */
    public AggregateEventTrigger()
    {
        _triggers = new ArrayList<EventTrigger>();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger and associate the list 
     * of event triggers to be aggregated by this trigger
     * 
     * @param triggers list of event triggers to add
     */
    public AggregateEventTrigger(List<EventTrigger> triggers)
    {
        _triggers = new ArrayList<EventTrigger>(triggers);
    }

    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger and associate the array 
     * of event triggers to be aggregated by this trigger
     * 
     * @param triggers list of event triggers to add
     */
    public AggregateEventTrigger(EventTrigger... triggers)
    {
        _triggers = Arrays.asList(triggers);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param trigger
     */
    public void add(EventTrigger trigger)
    {
        _triggers.add(trigger);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param triggers
     */
    public void addAll(List<EventTrigger> triggers)
    {
        _triggers.addAll(triggers);
    }
        
    /* ------------------------------------------------------------ */
    /**
     * @param triggers
     */
    public void addAll(EventTrigger... triggers)
    {
        _triggers.addAll(Arrays.asList(triggers));
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the event state associated with specified invocation
     * of the event trigger match method. This event trigger retrieves
     * the combined event state of all aggregated event triggers.
     * 
     * @param timestamp time stamp associated with invocation
     * @return event state or null if not found
     *
     * @see org.eclipse.jetty.monitor.jmx.EventTrigger#getState(long)
     */
    @Override
    public EventState getState(long timestamp)
    {
        EventState state = new EventState();
        
        for (EventTrigger trigger : _triggers)
        {
            EventState subState = trigger.getState(timestamp);
            if (subState != null)
            {
                state.addAll(subState.values());
            }
        }
        
        return state;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.monitor.jmx.EventTrigger#match(long)
     */
    @Override
    public boolean match(long timestamp) throws Exception
    {
        boolean result = false;
        for(EventTrigger trigger : _triggers)
        {
            result = trigger.match(timestamp) ? true : result;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "AND(triger1,trigger2,...)". 
     * 
     * @return string representation of the event trigger
     * 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        int cnt = 0;
        StringBuilder result = new StringBuilder();
        
        result.append("ANY(");
        for (EventTrigger trigger : _triggers)
        {
            result.append(cnt++ > 0 ? "," : "");
            result.append(trigger);
        }
        result.append(')');
        
        return result.toString();
    }
}
