//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee10.ant.types;

import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.taskdefs.Property;
import org.eclipse.jetty.ee10.ant.utils.TaskLog;

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
     *
     * @param property the property to test
     * @return true if property has been set
     */
    public static boolean setIfNotSetAlready(Property property)
    {
        if (System.getProperty(property.getName()) == null)
        {
            System.setProperty(property.getName(), (property.getValue() == null ? "" : property.getValue()));
            TaskLog.log("Setting property '" + property.getName() + "' to value '" + property.getValue() + "'");
            return true;
        }

        return false;
    }
}
