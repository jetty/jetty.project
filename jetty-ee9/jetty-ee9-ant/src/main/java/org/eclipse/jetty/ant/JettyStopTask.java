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
