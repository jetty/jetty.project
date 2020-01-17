//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.log.jmx;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;

/**
 *
 */
@ManagedObject("Jetty Logging")
public class LogMBean extends ObjectMBean
{
    public LogMBean(Object managedObject)
    {
        super(managedObject);
    }

    @ManagedAttribute(value = "list of instantiated loggers")
    public List<String> getLoggers()
    {
        List<String> keySet = new ArrayList<String>(Log.getLoggers().keySet());
        return keySet;
    }

    @ManagedOperation(value = "true if debug enabled for the given logger")
    public boolean isDebugEnabled(@Name("logger") String logger)
    {
        return Log.getLogger(logger).isDebugEnabled();
    }

    @ManagedOperation(value = "Set debug enabled for given logger")
    public void setDebugEnabled(@Name("logger") String logger, @Name("enabled") Boolean enabled)
    {
        Log.getLogger(logger).setDebugEnabled(enabled);
    }
}
