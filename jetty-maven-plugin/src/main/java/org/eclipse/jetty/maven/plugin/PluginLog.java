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

import org.apache.maven.plugin.logging.Log;

/**
 * PluginLog
 *
 * Convenience class to provide access to the plugin
 * Log for non-mojo classes.
 */
public class PluginLog
{
    private static Log log = null;

    public static void setLog(Log l)
    {
        log = l;
    }

    public static Log getLog()
    {
        return log;
    }
}
