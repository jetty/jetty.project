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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SystemProperties
 *
 * Map of name to SystemProperty.
 *
 * When a SystemProperty instance is added, if it has not
 * been already set (eg via the command line java system property)
 * then it will be set.
 */
public class SystemProperties
{
    private final Map<String, SystemProperty> properties;
    private boolean force;

    public SystemProperties()
    {
        properties = new HashMap<>();
    }

    public void setForce(boolean force)
    {
        this.force = force;
    }

    public boolean getForce()
    {
        return this.force;
    }

    public void setSystemProperty(SystemProperty prop)
    {
        properties.put(prop.getName(), prop);
        if (!force)
            prop.setIfNotSetAlready();
        else
            prop.setAnyway();
    }

    public SystemProperty getSystemProperty(String name)
    {
        return properties.get(name);
    }

    public boolean containsSystemProperty(String name)
    {
        return properties.containsKey(name);
    }

    public List<SystemProperty> getSystemProperties()
    {
        return new ArrayList<>(properties.values());
    }
}
