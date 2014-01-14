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

import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.monitor.jmx.EventState;
import org.eclipse.jetty.monitor.jmx.EventTrigger;



/* ------------------------------------------------------------ */
/**
 * AndEventTrigger 
 *
 * EventTrigger aggregation using logical AND operation 
 * that executes matching of the aggregated event triggers
 * in left to right order  
 */
public class AndEventTrigger extends EventTrigger
{
    protected final List<EventTrigger> _triggers;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger and associate the list 
     * of event triggers to be aggregated by this trigger
     * 
     * @param triggers list of event triggers to add
     */
    public AndEventTrigger(List<EventTrigger> triggers)
    {
        _triggers = triggers;
    }

    /* ------------------------------------------------------------ */
    /**
     * Construct an event trigger and associate the array 
     * of event triggers to be aggregated by this trigger
     * 
     * @param triggers array of event triggers to add
     */
    public AndEventTrigger(EventTrigger... triggers)
    {
        _triggers = Arrays.asList(triggers);
    }

    /* ------------------------------------------------------------ */
    /**
     * Verify if the event trigger conditions are in the 
     * appropriate state for an event to be triggered.
     * This event trigger will match if all aggregated 
     * event triggers would return a match.
     * 
     * @see org.eclipse.jetty.monitor.jmx.EventTrigger#match(long)
     */
    public boolean match(long timestamp)
        throws Exception
    {
        for(EventTrigger trigger : _triggers)
        {
            if (!trigger.match(timestamp))
                return false;
        }
        return true;
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
           state.addAll(subState.values());
       }
       
       return state;
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
        
        result.append("AND(");
        for (EventTrigger trigger : _triggers)
        {
            result.append(cnt++ > 0 ? "," : "");
            result.append(trigger);
        }
        result.append(')');
        
        return result.toString();
    }
}
