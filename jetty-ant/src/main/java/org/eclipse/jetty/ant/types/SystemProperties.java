//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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


package org.eclipse.jetty.ant.types;

import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.taskdefs.Property;
import org.eclipse.jetty.ant.utils.TaskLog;

/**
 * SystemProperties
 * <p> 
 * Ant &lt;systemProperties/&gt; tag definition.
 */
public class SystemProperties
{

    private List systemProperties = new ArrayList();

    public List getSystemProperties()
    {
        return systemProperties;
    }

    public void addSystemProperty(Property property)
    {
        systemProperties.add(property);
    }

    /**
     * Set a System.property with this value if it is not already set.
     * @param property the property to test 
     * @return true if property has been set
     */
    public static boolean setIfNotSetAlready(Property property)
    {
        if (System.getProperty(property.getName()) == null)
        {
            System.setProperty(property.getName(), (property.getValue() == null ? "" : property
                    .getValue()));
            TaskLog.log("Setting property '" + property.getName() + "' to value '"
                    + property.getValue() + "'");
            return true;
        }

        return false;
    }
}
