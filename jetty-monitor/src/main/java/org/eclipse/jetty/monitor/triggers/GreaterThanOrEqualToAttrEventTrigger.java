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
 * GreaterThanOrEqualToAttrEventTrigger
 * 
 * Event trigger that polls a value of an MXBean attribute and
 * checks if it is greater than or equal to specified min value. 
 */
public class GreaterThanOrEqualToAttrEventTrigger<TYPE extends Comparable<TYPE>> extends AttrEventTrigger<TYPE>
{
    protected final TYPE _min;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct event trigger and specify the MXBean attribute
     * that will be polled by this event trigger as well as min
     * value of the attribute.
     * 
     * @param objectName object name of an MBean to be polled
     * @param attributeName name of an MBean attribute to be polled
     * @param min minimum value of the attribute
     * 
     * @throws MalformedObjectNameException
     * @throws IllegalArgumentException
     */
    public GreaterThanOrEqualToAttrEventTrigger(String objectName, String attributeName, TYPE min)
        throws MalformedObjectNameException, IllegalArgumentException
    {
        super(objectName,attributeName);
        
        if (min == null)
            throw new IllegalArgumentException("Value cannot be null");

        _min = min;
    }

    /* ------------------------------------------------------------ */
    /**
     * Compare the value of the MXBean attribute being polling
     * to check if it is greater than or equal to the min value.
     * 
     * @see org.eclipse.jetty.monitor.triggers.AttrEventTrigger#match(java.lang.Comparable)
     */
    @Override
    public boolean match(Comparable<TYPE> value)
    {
        return (value.compareTo(_min) >= 0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "min<=name". 
     * 
     * @return string representation of the event trigger
     * 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        StringBuilder result = new StringBuilder();
        
        result.append(_min);
        result.append("<=");
        result.append(getNameString());
        
        return result.toString();
    }
}
