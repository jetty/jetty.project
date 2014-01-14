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
 * EqualToAttrEventTrigger
 * 
 * Event trigger that polls a value of an MXBean attribute and
 * checks if it is equal to specified value. 
 */
public class EqualToAttrEventTrigger<TYPE extends Comparable<TYPE>> extends AttrEventTrigger<TYPE>
{
    protected final TYPE _value;
    
    /* ------------------------------------------------------------ */
    /**
     * Construct event trigger and specify the MXBean attribute
     * that will be polled by this event trigger as well as the
     * target value of the attribute.
     * 
     * @param objectName object name of an MBean to be polled
     * @param attributeName name of an MBean attribute to be polled
     * @param value target value of the attribute
     * 
     * @throws MalformedObjectNameException
     * @throws IllegalArgumentException
     */
    public EqualToAttrEventTrigger(String objectName, String attributeName, TYPE value)
        throws MalformedObjectNameException, IllegalArgumentException
    {
        super(objectName,attributeName);
        
        if (value == null)
            throw new IllegalArgumentException("Value cannot be null");

        _value = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Compare the value of the MXBean attribute being polling
     * to check if it is equal to the specified value.
     */
    @Override
    public boolean match(Comparable<TYPE> value)
    {
        return (value.compareTo(_value) == 0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the string representation of this event trigger
     * in the format "name=value". 
     * 
     * @return string representation of the event trigger
     * 
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        StringBuilder result = new StringBuilder();
        
        result.append(getNameString());
        result.append("==");
        result.append(_value);
        
        return result.toString();
    }
}
