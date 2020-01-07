//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Display an optional Warning Message if the {jetty.home} and {jetty.base} are the same directory.
 * <p>
 * This is to warn about not recommended approach to setting up the Jetty Distribution.
 */
public class HomeBaseWarning
{
    private static final Logger LOG = Log.getLogger(HomeBaseWarning.class);

    public HomeBaseWarning()
    {
        boolean showWarn = false;

        String home = System.getProperty("jetty.home");
        String base = System.getProperty("jetty.base");

        if (StringUtil.isBlank(base))
        {
            // no base defined? then we are likely running
            // via direct command line.
            return;
        }

        Path homePath = new File(home).toPath();
        Path basePath = new File(base).toPath();

        try
        {
            showWarn = Files.isSameFile(homePath, basePath);
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            // Can't definitively determine this state
            return;
        }

        if (showWarn)
        {
            StringBuilder warn = new StringBuilder();
            warn.append("This instance of Jetty is not running from a separate {jetty.base} directory");
            warn.append(", this is not recommended.  See documentation at http://www.eclipse.org/jetty/documentation/current/startup.html");
            LOG.warn("{}", warn.toString());
        }
    }
}
