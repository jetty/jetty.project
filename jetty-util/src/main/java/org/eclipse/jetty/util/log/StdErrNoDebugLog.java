//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.log;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

import java.util.Properties;


/**
 * StdErrNoDebugLog Logging implementation.
 * <div>
 * A Jetty {@link Logger} that always return <code>false</code> for {@link #isDebugEnabled()}. So all the Jetty code will
 * never write log at debug level log
 * </div>
 */
@ManagedObject("Jetty StdErrNoDebugLog Logging Implementation")
public class StdErrNoDebugLog extends StdErrLog
{

    public StdErrNoDebugLog() {
        super();
    }

    public StdErrNoDebugLog(String name) {
        super(name);
    }

    public StdErrNoDebugLog(String name,Properties props) {
        super(name,props);
    }

    @ManagedAttribute("is debug enabled for root logger Log.LOG")
    @Override
    public boolean isDebugEnabled()
    {
        return false;
    }

    @Override
    public void debug( String msg, Object... args )
    {
        // no op
    }

    @Override
    public void debug( Throwable thrown )
    {
        // no op
    }

    @Override
    public void debug( String msg, Throwable thrown )
    {
        // no op
    }

    @Override
    public void debug( String msg, long arg )
    {
        // no op
    }

    @Override
    public void setDebugEnabled( boolean enabled )
    {
        // no op
    }


}
