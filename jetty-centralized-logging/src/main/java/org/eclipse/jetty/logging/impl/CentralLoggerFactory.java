// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * The Logger Factory for CentralLoggers.
 */
public class CentralLoggerFactory implements ILoggerFactory
{
    private CentralLoggerConfig root;
    private Map<String, CentralLogger> loggers;

    public CentralLoggerFactory(CentralLoggerConfig root)
    {
        this.root = root;
        this.loggers = new HashMap<String, CentralLogger>();
        JavaUtilLoggingRouting.init();
    }

    public void setRoot(CentralLoggerConfig root)
    {
        this.root = root;
        this.loggers.clear();
    }

    public Logger getLogger(String name)
    {
        CentralLogger ret = null;
        synchronized (this)
        {
            ret = loggers.get(name);
            if (ret == null)
            {
                CentralLoggerConfig clogger = root.getConfiguredLogger(name);
                ret = clogger.getLogger();
                loggers.put(name,ret);
            }
        }
        return ret;
    }
}
