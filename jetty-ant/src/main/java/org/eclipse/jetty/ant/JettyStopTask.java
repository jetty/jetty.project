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

package org.eclipse.jetty.ant;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.eclipse.jetty.ant.utils.TaskLog;

/**
 * JettyStopTask
 */
public class JettyStopTask extends Task
{

    private int stopPort;

    private String stopKey;

    private int stopWait;

    /**
     *
     */
    public JettyStopTask()
    {
        TaskLog.setTask(this);
    }

    /**
     * @see org.apache.tools.ant.Task#execute()
     */
    @Override
    public void execute() throws BuildException
    {
        try
        {
            Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort);
            if (stopWait > 0)
                s.setSoTimeout(stopWait * 1000);
            try
            {
                OutputStream out = s.getOutputStream();
                out.write((stopKey + "\r\nstop\r\n").getBytes());
                out.flush();

                if (stopWait > 0)
                {
                    TaskLog.log("Waiting" + (stopWait > 0 ? (" " + stopWait + "sec") : "") + " for jetty to stop");
                    LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                    String response = lin.readLine();
                    if ("Stopped".equals(response))
                        System.err.println("Stopped");
                }
            }
            finally
            {
                s.close();
            }
        }
        catch (ConnectException e)
        {
            TaskLog.log("Jetty not running!");
        }
        catch (Exception e)
        {
            TaskLog.log(e.getMessage());
        }
    }

    public int getStopPort()
    {
        return stopPort;
    }

    public void setStopPort(int stopPort)
    {
        this.stopPort = stopPort;
    }

    public String getStopKey()
    {
        return stopKey;
    }

    public void setStopKey(String stopKey)
    {
        this.stopKey = stopKey;
    }

    public int getStopWait()
    {
        return stopWait;
    }

    public void setStopWait(int stopWait)
    {
        this.stopWait = stopWait;
    }
}
