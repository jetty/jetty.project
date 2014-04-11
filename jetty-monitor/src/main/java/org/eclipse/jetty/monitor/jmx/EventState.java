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

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/* ------------------------------------------------------------ */
/**
 * EventState
 * 
 * Holds the state of one or more {@link org.eclipse.jetty.monitor.jmx.EventTrigger event trigger}
 * instances to be used when sending notifications as well as executing the actions
 */
public class EventState<TYPE>
{
    
    /* ------------------------------------------------------------ */
    /**
     * State
     * 
     * Holds the state of a single {@link org.eclipse.jetty.monitor.jmx.EventTrigger event trigger}
     */
    public static class TriggerState<TYPE>
    {
        private final String _id;
        private final String _desc;
        private final TYPE _value;
        
        /* ------------------------------------------------------------ */
        /**
         * Construct a trigger state 
         * 
         * @param id unique identification string of the associated event trigger
         * @param desc description of the associated event trigger
         * @param value effective value of the MXBean attribute (if applicable)
         */
        public TriggerState(String id, String desc, TYPE value)
        {
            _id = id;
            _desc = desc;
            _value = value;
        }
        
        /* ------------------------------------------------------------ */
        /**
         * Retrieve the identification string of associated event trigger
         * 
         * @return unique identification string
         */
        public String getID()
        {
            return _id;
        }
        
        /* ------------------------------------------------------------ */
        /**
         * Retrieve the description string set by event trigger
         * 
         * @return description string
         */
        public String getDescription()
        {
            return _desc;
        }
        
        /* ------------------------------------------------------------ */
        /**
         * Retrieve the effective value of the MXBean attribute (if applicable)
         * 
         * @return attribute value
         */
        public TYPE getValue()
        {
            return _value;
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @return string representation of the state
         */
        public String toString()
        {
            StringBuilder result = new StringBuilder();
           
            result.append(_desc);
            result.append('=');
            result.append(_value);
            
            return result.toString();
        }
    }
    
    protected Map<String, TriggerState<TYPE>> _states;
    
    /* ------------------------------------------------------------ */
    /**
     * Constructs an empty event state
     */
    public EventState()
    {
        _states = new ConcurrentHashMap<String, TriggerState<TYPE>>();
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Constructs an event state and adds a specified trigger state to it
     * 
     * @param id unique identification string of the associated event trigger
     * @param desc description of the associated event trigger
     * @param value effective value of the MXBean attribute (if applicable)
     */
    public EventState(String id, String desc, TYPE value)
    {
        this();
        
        add(new TriggerState<TYPE>(id, desc, value));
    }

    /* ------------------------------------------------------------ */
    /**
     * Adds a trigger state to the event state
     * 
     * @param state trigger state to add
     */
    public void add(TriggerState<TYPE> state)
    {
        _states.put(state.getID(), state);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Adds a collection of trigger states to the event state
     * 
     * @param entries collection of trigger states to add
     */
    public void addAll(Collection<TriggerState<TYPE>> entries)
    {
        for (TriggerState<TYPE> entry : entries)
        {
            add(entry);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieves a single trigger state
     * 
     * @param id unique identification string of the event trigger
     * @return requested trigger state or null if not found
     */
    public TriggerState<TYPE> get(String id)
    {
        return _states.get(id);
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieves a collection of all trigger states of the event state
     * 
     * @return collection of the trigger states
     */
    public Collection<TriggerState<TYPE>> values()
    {
        return Collections.unmodifiableCollection(_states.values());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns a string representation of the event state
     * 
     * @return string representation of the event state
     */
    public String toString()
    {
        int cnt = 0;
        StringBuilder result = new StringBuilder();
        
        for (TriggerState<TYPE> value : _states.values())
        {
            result.append(cnt++>0?"#":"");
            result.append(value.toString());
        }
        
        return result.toString();
    }
}
