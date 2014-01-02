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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.eclipse.jetty.monitor.JMXMonitor;
import org.eclipse.jetty.monitor.jmx.EventState;
import org.eclipse.jetty.monitor.jmx.EventTrigger;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * AttrEventTrigger
 * 
 * Event trigger that polls a value of an MXBean attribute
 * and matches every invocation of this trigger. It can be
 * used to send notifications of the value of an attribute
 * of the MXBean being polled at a certain interval, or as
 * a base class for the event triggers that match the 
 * value of an attribute of the MXBean being polled against
 * some specified criteria.
 */
public class AttrEventTrigger<TYPE extends Comparable<TYPE>> 
    extends EventTrigger
{
    private static final Logger LOG = Log.getLogger(AttrEventTrigger.class);
   
    private final ObjectName _nameObject;

    protected final String _objectName;
    protected final String _attributeName;
    protected Map<Long, EventState<TYPE>> _states;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct event trigger and specify the MXBean attribute
     * that will be polled by this event trigger.
     * 
     * @param objectName object name of an MBean to be polled
     * @param attributeName name of an MBean attribute to be polled
     * 
     * @throws MalformedObjectNameException
     * @throws IllegalArgumentException
     */
    public AttrEventTrigger(String objectName, String attributeName)
        throws MalformedObjectNameException, IllegalArgumentException
    {
        if (objectName == null)
            throw new IllegalArgumentException("Object name cannot be null");
        if (attributeName == null)
            throw new IllegalArgumentException("Attribute name cannot be null");
        
        _states =  new ConcurrentHashMap<Long,EventState<TYPE>>();
        
        _objectName = objectName;
        _attributeName = attributeName;
        
        _nameObject = new ObjectName(_objectName);
    }

    /* ------------------------------------------------------------ */
    /**
     * Construct event trigger and specify the MXBean attribute
     * that will be polled by this event trigger.
     * 
     * @param nameObject object name of an MBean to be polled
     * @param attributeName name of an MBean attribute to be polled
     * 
     * @throws IllegalArgumentException
     */
    public AttrEventTrigger(ObjectName nameObject, String attributeName)
        throws IllegalArgumentException
    {
        if (nameObject == null)
            throw new IllegalArgumentException("Object name cannot be null");
        if (attributeName == null)
            throw new IllegalArgumentException("Attribute name cannot be null");
        
        _states =  new ConcurrentHashMap<Long,EventState<TYPE>>();
        
        _objectName = nameObject.toString();
        _attributeName = attributeName;
        
        _nameObject = nameObject;
    }

    /* ------------------------------------------------------------ */
    /**
     * Verify if the event trigger conditions are in the 
     * appropriate state for an event to be triggered.
     * This event trigger uses the match(Comparable<TYPE>)
     * method to compare the value of the MXBean attribute
     * to the conditions specified by the subclasses.
     * 
     * @see org.eclipse.jetty.monitor.jmx.EventTrigger#match(long)
     */
    @SuppressWarnings("unchecked")
    public final boolean match(long timestamp) 
        throws Exception
    {
        MBeanServerConnection serverConnection = JMXMonitor.getServiceConnection();

        TYPE value = null;
        try
        {
            int pos = _attributeName.indexOf('.');
            if (pos < 0)
                value = (TYPE)serverConnection.getAttribute(_nameObject,_attributeName);
            else
                value =  getValue((CompositeData)serverConnection.getAttribute(_nameObject, _attributeName.substring(0, pos)),
                                  _attributeName.substring(pos+1));
        }
        catch (Exception ex)
        {
            LOG.debug(ex);
        }

        boolean result = false;
        if (value != null)
        {
            result = match(value);
            
            if (result || getSaveAll())
            {
                _states.put(timestamp, 
                            new EventState<TYPE>(this.getID(), this.getNameString(), value));
            }
        }
            
        return result;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Verify if the event trigger conditions are in the 
     * appropriate state for an event to be triggered.
     * Allows subclasses to override the default behavior
     * that matches every invocation of this trigger
     */
    public boolean match(Comparable<TYPE> value)
    {
        return true;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Retrieve the event state associated with specified invocation
     * of the event trigger match method. 
     * 
     * @param timestamp time stamp associated with invocation
     * @return event state or null if not found
     *
     * @see org.eclipse.jetty.monitor.jmx.EventTrigger#getState(long)
     */
    @Override
    public final EventState<TYPE> getState(long timestamp)
    {
        return _states.get(timestamp);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "[object_name:attribute_name]". 
     * 
     * @return string representation of the event trigger
     * 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        return getNameString();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "[object_name:attribute_name]". Allows
     * subclasses to override the name string used to identify
     * this event trigger in the event state object as well as
     * string representation of the subclasses.
     * 
     * @return string representation of the event trigger
     */
    protected String getNameString()
    {
        StringBuilder result = new StringBuilder();
        
        result.append('[');
        result.append(_objectName);
        result.append(":");
        result.append(_attributeName);
        result.append("]");
        
        return result.toString();
    }

    protected boolean getSaveAll()
    {
        return true;
    }
    
    protected TYPE getValue(CompositeData compValue, String fieldName)
    {
        int pos = fieldName.indexOf('.');
        if (pos < 0)
            return (TYPE)compValue.get(fieldName);
        else
            return getValue((CompositeData)compValue.get(fieldName.substring(0, pos)), 
                            fieldName.substring(pos+1));
          
    }
}
