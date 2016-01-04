//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.tests.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public class JULog
{
    private final Logger log;

    public JULog(Class<?> clazz)
    {
        this.log = Logger.getLogger(clazz.getName());
    }

    public void info(String msg)
    {
        log.log(Level.INFO, msg);
    }

    public void info(String msg, Object ... args)
    {
        log.log(Level.INFO, msg, args);
    }

    public void warn(Throwable t)
    {
        log.log(Level.WARNING, "", t);
    }
}
