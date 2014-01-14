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

import javax.management.MalformedObjectNameException;


/* ------------------------------------------------------------ */
/**
 * LessThanOrEqualToAttrEventTrigger
 * 
 * Event trigger that polls a value of an MXBean attribute and
 * checks if it is less than or equal to specified max value. 
 */
public class LessThanOrEqualToAttrEventTrigger<TYPE extends Comparable<TYPE>> extends AttrEventTrigger<TYPE>
{
    protected final TYPE _max;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct event trigger and specify the MXBean attribute
     * that will be polled by this event trigger as well as max
     * value of the attribute.
     * 
     * @param objectName object name of an MBean to be polled
     * @param attributeName name of an MBean attribute to be polled
     * @param max maximum value of the attribute
     * 
     * @throws MalformedObjectNameException
     * @throws IllegalArgumentException
     */
    public LessThanOrEqualToAttrEventTrigger(String objectName, String attributeName, TYPE max)
        throws MalformedObjectNameException, IllegalArgumentException
    {
        super(objectName,attributeName);
        
        if (max == null)
            throw new IllegalArgumentException("Value cannot be null");

        _max = max;
    }

    /* ------------------------------------------------------------ */
    /**
     * Compare the value of the MXBean attribute being polling
     * to check if it is less than or equal to the max value.
     * 
     * @see org.eclipse.jetty.monitor.triggers.AttrEventTrigger#match(java.lang.Comparable)
     */
    @Override
    public boolean match(Comparable<TYPE> value)
    {
        return (value.compareTo(_max) <= 0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "name<=max". 
     * 
     * @return string representation of the event trigger
     * 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        StringBuilder result = new StringBuilder();
        
        result.append(getNameString());
        result.append("<=");
        result.append(_max);
        
        return result.toString();
    }
}
