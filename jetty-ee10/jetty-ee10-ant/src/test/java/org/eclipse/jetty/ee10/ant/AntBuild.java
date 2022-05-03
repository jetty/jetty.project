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

package org.eclipse.jetty.ee10.ant;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public class AntBuild
{
    private Thread _process;
    private String _ant;

    private int _port;
    private String _host;

    public AntBuild(String ant)
    {
        _ant = ant;
    }

    private class AntBuildProcess implements Runnable
    {
        List<String[]> connList;

        @Override
        public void run()
        {
            File buildFile = new File(_ant);

            Project antProject = new Project();
            try
            {
                antProject.setBaseDir(MavenTestingUtils.getBaseDir());
                antProject.setUserProperty("ant.file", buildFile.getAbsolutePath());
                DefaultLogger logger = new DefaultLogger();

                ConsoleParser parser = new ConsoleParser();
                //connList = parser.newPattern(".*([0-9]+\\.[0-9]*\\.[0-9]*\\.[0-9]*):([0-9]*)",1);
                connList = parser.newPattern("Jetty AntTask Started", 1);

                PipedOutputStream pos = new PipedOutputStream();
                PipedInputStream pis = new PipedInputStream(pos);

                PipedOutputStream pose = new PipedOutputStream();
                PipedInputStream pise = new PipedInputStream(pose);

                startPump("STDOUT", parser, pis);
                startPump("STDERR", parser, pise);

                logger.setErrorPrintStream(new PrintStream(pos));
                logger.setOutputPrintStream(new PrintStream(pose));
                logger.setMessageOutputLevel(Project.MSG_VERBOSE);
                antProject.addBuildListener(logger);

                antProject.fireBuildStarted();
                antProject.init();

                ProjectHelper helper = ProjectHelper.getProjectHelper();

                antProject.addReference("ant.projectHelper", helper);

                helper.parse(antProject, buildFile);

                antProject.executeTarget("jetty.run");

                parser.waitForDone(10000, TimeUnit.MILLISECONDS);
            }
            catch (Exception e)
            {
                antProject.fireBuildFinished(e);
            }
        }

        public void waitForStarted() throws Exception
        {
            while (connList == null || connList.isEmpty())
            {
                Thread.sleep(10);
            }
        }
    }

    public void start() throws Exception
    {
        System.out.println("Starting Ant Build ...");
        AntBuildProcess abp = new AntBuildProcess();
        _process = new Thread(abp);

        _process.start();

        abp.waitForStarted();

        // once this has returned we should have the connection info we need
        //_host = abp.getConnectionList().get(0)[0];
        //_port = Integer.parseInt(abp.getConnectionList().get(0)[1]);

    }

    public int getJettyPort()
    {
        return Integer.parseInt(System.getProperty("jetty.ant.server.port"));
    }

    public String getJettyHost()
    {
        return System.getProperty("jetty.ant.server.host");
    }

    /**
     * Stop the jetty server
     */
    public void stop()
    {
        System.out.println("Stopping Ant Build ...");
        _process.interrupt();
    }

    private static class ConsoleParser
    {
        private List<ConsolePattern> patterns = new ArrayList<ConsolePattern>();
        private CountDownLatch latch;
        private int count;

        public List<String[]> newPattern(String exp, int cnt)
        {
            ConsolePattern pat = new ConsolePattern(exp, cnt);
            patterns.add(pat);
            count += cnt;

            return pat.getMatches();
        }

        public void parse(String line)
        {
            for (ConsolePattern pat : patterns)
            {
                Matcher mat = pat.getMatcher(line);
                if (mat.find())
                {
                    int num = 0;
                    int count = mat.groupCount();
                    String[] match = new String[count];
                    while (num++ < count)
                    {
                        match[num - 1] = mat.group(num);
                    }
                    pat.getMatches().add(match);

                    if (pat.getCount() > 0)
                    {
                        getLatch().countDown();
                    }
                }
            }
        }

        public void waitForDone(long timeout, TimeUnit unit) throws InterruptedException
        {
            getLatch().await(timeout, unit);
        }

        private CountDownLatch getLatch()
        {
            synchronized (this)
            {
                if (latch == null)
                {
                    latch = new CountDownLatch(count);
                }
            }

            return latch;
        }
    }

    private static class ConsolePattern
    {
        private Pattern pattern;
        private List<String[]> matches;
        private int count;

        ConsolePattern(String exp, int cnt)
        {
            pattern = Pattern.compile(exp);
            matches = new ArrayList<String[]>();
            count = cnt;
        }

        public Matcher getMatcher(String line)
        {
            return pattern.matcher(line);
        }

        public List<String[]> getMatches()
        {
            return matches;
        }

        public int getCount()
        {
            return count;
        }
    }

    private void startPump(String mode, ConsoleParser parser, InputStream inputStream)
    {
        ConsoleStreamer pump = new ConsoleStreamer(mode, inputStream);
        pump.setParser(parser);
        Thread thread = new Thread(pump, "ConsoleStreamer/" + mode);
        thread.start();
    }

    /**
     * Simple streamer for the console output from a Process
     */
    private static class ConsoleStreamer implements Runnable
    {
        private String mode;
        private BufferedReader reader;
        private ConsoleParser parser;

        public ConsoleStreamer(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
        }

        public void setParser(ConsoleParser connector)
        {
            this.parser = connector;
        }

        @Override
        public void run()
        {
            String line;
            //System.out.printf("ConsoleStreamer/%s initiated%n",mode);
            try
            {
                while ((line = reader.readLine()) != (null))
                {
                    if (parser != null)
                    {
                        parser.parse(line);
                    }
                    System.out.println("[" + mode + "] " + line);
                }
            }
            catch (IOException ignore)
            {
                /* ignore */
            }
            finally
            {
                IO.close(reader);
            }
            //System.out.printf("ConsoleStreamer/%s finished%n",mode);
        }
    }
}
