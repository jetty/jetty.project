//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.maven.plugin;

/**
 * SystemProperty
 *
 * Provides the ability to set System properties
 * for the mojo execution. A value will only
 * be set if it is not set already. That is, if
 * it was set on the command line or by the system,
 * it won't be overridden by settings in the
 * plugin's configuration.
 */
public class SystemProperty
{

    private String name;
    private String value;
    private boolean isSet;

    /**
     * @return Returns the name.
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * @param name The name to set.
     */
    public void setName(String name)
    {
        this.name = name;
    }

    public String getKey()
    {
        return this.name;
    }

    public void setKey(String name)
    {
        this.name = name;
    }

    /**
     * @return Returns the value.
     */
    public String getValue()
    {
        return this.value;
    }

    /**
     * @param value The value to set.
     */
    public void setValue(String value)
    {
        this.value = value;
    }

    public boolean isSet()
    {
        return isSet;
    }

    /**
     * Set a System.property with this value
     * if it is not already set.
     */
    void setIfNotSetAlready()
    {
        if (System.getProperty(getName()) == null)
        {
            System.setProperty(getName(), (getValue() == null ? "" : getValue()));
            isSet = true;
        }
    }

    void setAnyway()
    {
        System.setProperty(getName(), (getValue() == null ? "" : getValue()));
        isSet = true;
    }
}
