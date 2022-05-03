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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
                String response = send(stopKey + "\r\n" + "pid" + "\r\n", stopWait);
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
            if (pid == null)
            {
                //no pid, so just wait until jetty reports itself stopped
                try
                {
                    getLog().info("Waiting " + stopWait + " seconds for jetty to stop");
                    String response = send(stopKey + "\r\n" + command + "\r\n", stopWait);

                    if ("Stopped".equals(response))
                        getLog().info("Server reports itself as stopped");
                    else
                        getLog().info("Couldn't verify server as stopped, received " + response);
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
                //wait for pid to stop
                getLog().info("Waiting " + stopWait + " seconds for jetty " + pid + " to stop");
                Optional<ProcessHandle> optional = ProcessHandle.of(pid);
                final long remotePid = pid.longValue();
                optional.ifPresentOrElse(p -> 
                {
                    try
                    {
                        //if running in the same process, just send the stop
                        //command and wait for the response
                        if (ProcessHandle.current().pid() == remotePid)
                        {
                            send(stopKey + "\r\n" + command + "\r\n", stopWait);
                        }
                        else
                        {
                            //running forked, so wait for the process
                            send(stopKey + "\r\n" + command + "\r\n", 0);
                            CompletableFuture<ProcessHandle> future = p.onExit();
                            if (p.isAlive())
                            {
                                p = future.get(stopWait, TimeUnit.SECONDS);
                            }

                            if (p.isAlive())
                                getLog().info("Couldn't verify server process stop");
                            else
                                getLog().info("Server process stopped");
                        }
                    }
                    catch (ConnectException e)
                    {
                        //jetty not listening on the given port, don't wait for the process
                        getLog().info("Jetty not running!");
                    }
                    catch (TimeoutException e)
                    {
                        getLog().error("Timeout expired while waiting for server process to stop");
                    }
                    catch (Throwable e)
                    {
                        getLog().error(e);
                    }
                }, () -> getLog().info("Process not running"));
            }
        }
        else
        {
            //send the stop command but don't wait to verify the stop
            getLog().info("Stopping jetty");
            try
            {
                send(stopKey + "\r\n" + command + "\r\n", 0);
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
     * @param wait length of time in sec to wait for a response
     * @return the response, if any, to the command
     * @throws Exception
     */
    private String send(String command, int wait)
        throws Exception
    {
        String response = null;
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort); OutputStream out = s.getOutputStream();)
        {
            out.write(command.getBytes());
            out.flush();

            if (wait > 0)
            {
                //Wait for a response
                s.setSoTimeout(wait * 1000);

                try (LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));)
                {
                    response = lin.readLine();
                }
            }
            else
            {
                //Wait only a small amount of time to ensure TCP has sent the message
                s.setSoTimeout(1000);
                s.getInputStream().read();
            }
            
            return response;
        }
    }
}
