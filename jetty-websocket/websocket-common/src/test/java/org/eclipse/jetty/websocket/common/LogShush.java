//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.util.log.StdErrLog;

public class LogShush
{
    public static void disableStacks(Class<?> clazz)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(true);
    }

    public static void enableStacks(Class<?> clazz)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(false);
    }
}
