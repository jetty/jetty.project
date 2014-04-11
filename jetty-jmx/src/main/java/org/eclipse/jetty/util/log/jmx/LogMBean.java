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

package org.eclipse.jetty.util.log.jmx;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 */
public class LogMBean extends ObjectMBean
{

    public LogMBean(Object managedObject)
    {
        super(managedObject);
    }

    public List<String> getLoggers()
    {
        List<String> keySet = new ArrayList<String>(Log.getLoggers().keySet());
        return keySet;
    }

    public boolean isDebugEnabled(String logger)
    {
        return Log.getLogger(logger).isDebugEnabled();
    }

    public void setDebugEnabled(String logger, Boolean enabled)
    {
        Log.getLogger(logger).setDebugEnabled(enabled);
    }
}
