//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
