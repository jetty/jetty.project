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

package org.eclipse.jetty.util.component;

import java.io.FileWriter;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** A LifeCycle Listener that writes state changes to a file.
 * <p>This can be used with the jetty.sh script to wait for successful startup.
 */
public class FileNoticeLifeCycleListener implements LifeCycle.Listener
{
    Logger LOG = Log.getLogger(FileNoticeLifeCycleListener.class);
    
    private final String _filename;
    
    public FileNoticeLifeCycleListener(String filename)
    {
        _filename=filename;
    }

    private void writeState(String action, LifeCycle lifecycle)
    {
        try
        {
            FileWriter out = new FileWriter(_filename,true);
            out.append(action).append(" ").append(lifecycle.toString()).append("\n");
            out.close();
        }
        catch(Exception e)
        {
            LOG.warn(e);
        }
    }
    
    public void lifeCycleStarting(LifeCycle event)
    {  
        writeState("STARTING",event);      
    }

    public void lifeCycleStarted(LifeCycle event)
    {        
        writeState("STARTED",event); 
    }

    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {        
        writeState("FAILED",event);
    }

    public void lifeCycleStopping(LifeCycle event)
    {        
        writeState("STOPPING",event);
    }

    public void lifeCycleStopped(LifeCycle event)
    {        
        writeState("STOPPED",event);
    }
}
