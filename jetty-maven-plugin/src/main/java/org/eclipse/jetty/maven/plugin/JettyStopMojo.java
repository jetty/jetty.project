//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * This goal stops a running instance of jetty.
 *
 * The <b>stopPort</b> and <b>stopKey</b> parameters can be used to
 * configure which jetty to stop.
 */
@Mojo(name = "stop")
public class JettyStopMojo extends AbstractWebAppMojo
{
    /**
     * Max time in seconds that the plugin will wait for confirmation that jetty has stopped.
     */
    @Parameter
    protected int stopWait;
    
    @Override
    protected void startJettyEmbedded() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    protected void startJettyForked() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    protected void startJettyHome() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (stopPort <= 0)
            throw new MojoExecutionException("Please specify a valid port");
        if (stopKey == null)
            throw new MojoExecutionException("Please specify a valid stopKey");

        String command = "forcestop";

        if (stopWait > 0)
        {
            //try to get the pid of the forked jetty process
            Long pid = null;
            try
            {
                String response = send(stopKey + "\r\n" + "pid" + "\r\n", true, stopWait);
                pid = Long.valueOf(response);
            }
            catch (NumberFormatException e)
            {
                getLog().info("Server returned bad pid");
            }
            catch (ConnectException e)
            {
                //jetty not running, no point continuing
                getLog().info("Jetty not running!");
                return;
            }
            catch (Exception e)
            {
                //jetty running, try to stop it regardless of error
                getLog().error(e);
            }

            //now send the stop command and wait for confirmation - either an ack from jetty, or
            //that the process has stopped
            try
            {
                if (pid == null)
                {
                    //no pid, so just wait until jetty reports itself stopped
                    getLog().info("Waiting " + stopWait + " seconds for jetty to stop");
                    String response = send(stopKey + "\r\n" + command + "\r\n", true, stopWait);

                    if ("Stopped".equals(response))
                        getLog().info("Server reports itself as stopped");
                    else
                        getLog().info("Couldn't verify server as stopped, received " + response);
                }
                else
                {
                    //wait for pid to stop
                    getLog().info("Waiting " + stopWait + " seconds for jetty " + pid + " to stop");
                    send(stopKey + "\r\n" + command + "\r\n", false, 0);
                    Optional<ProcessHandle> optional = ProcessHandle.of(pid);
                    ProcessHandle handle = optional.orElse(null);
                    if (handle != null)
                    {
                        try
                        {
                            CompletableFuture<ProcessHandle> future = handle.onExit();
                            if (handle.isAlive())
                            {
                                handle = future.get(stopWait, TimeUnit.SECONDS);
                            }

                            if (handle.isAlive())
                                getLog().info("Couldn't verify server process stop");
                            else
                                getLog().info("Server process stopped");
                        }
                        catch (Throwable e)
                        {
                            getLog().error(e);
                        }
                    }
                }
            }
            catch (ConnectException e)
            {
                getLog().info("Jetty not running!");
            }
            catch (Exception e)
            {
                getLog().error(e);
            }
        }
        else
        {
            //send the stop command but don't wait to verify the stop
            getLog().info("Stopping jetty");
            try
            {
                send(stopKey + "\r\n" + command + "\r\n", false, 0);
            }
            catch (ConnectException e)
            {
                getLog().info("Jetty not running!");
            }
            catch (Exception e)
            {
                getLog().error(e);
            }
        }
    }
    
    /**
     * Send a command to a jetty process, optionally waiting for a response.
     * 
     * @param command the command to send
     * @param expectResponse if true, we will wait for a response
     * @param wait length of time in sec to wait for a response
     * @return the response, if any, to the command
     * @throws Exception
     */
    private String send(String command, boolean expectResponse, int wait)
        throws Exception
    {
        String response = null;
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort); OutputStream out = s.getOutputStream();)
        {
            out.write(command.getBytes());
            out.flush();

            if (expectResponse)
            {   
                if (wait > 0)
                    s.setSoTimeout(wait * 1000);
                
                try (LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));)
                {
                    response = lin.readLine();
                }
            }
            return response;
        }
    }
}
