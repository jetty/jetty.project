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

import java.io.File;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestJettyStopMojo
{   
    public static class TestLog implements org.apache.maven.plugin.logging.Log
    {
        List<String> sink = new ArrayList<String>();

        @Override
        public boolean isDebugEnabled()
        {
            return true;
        }

        @Override
        public void debug(CharSequence content)
        {
            sink.add(content.toString());
        }

        @Override
        public void debug(CharSequence content, Throwable error)
        {
            sink.add(content.toString());
        }

        @Override
        public void debug(Throwable error)
        {
        }

        @Override
        public boolean isInfoEnabled()
        {
            return true;
        }

        @Override
        public void info(CharSequence content)
        {
            sink.add(content.toString());
        }

        @Override
        public void info(CharSequence content, Throwable error)
        {
            sink.add(content.toString());
        }

        @Override
        public void info(Throwable error)
        {
        }

        @Override
        public boolean isWarnEnabled()
        {
            return true;
        }

        @Override
        public void warn(CharSequence content)
        {
            sink.add(content.toString());
        }

        @Override
        public void warn(CharSequence content, Throwable error)
        {
            sink.add(content.toString());
        }

        @Override
        public void warn(Throwable error)
        {
        }

        @Override
        public boolean isErrorEnabled()
        {
            return true;
        }

        @Override
        public void error(CharSequence content)
        {
            sink.add(content.toString());
        }

        @Override
        public void error(CharSequence content, Throwable error)
        {
            sink.add(content.toString());
        }

        @Override
        public void error(Throwable error)
        {
        }

        public void assertContains(String str)
        {
            assertThat(sink, Matchers.hasItem(str));
        }
        
        public void dumpStdErr()
        {
            for (String s : sink)
            {
                System.err.println(s);
            }
        }
    }
    
    @Test
    public void testStopNoWait() throws Exception
    {
        String stopKey = "foo";
        
        //start the responder
        MockShutdownMonitorRunnable runnable = new MockShutdownMonitorRunnable();
        runnable.setPidResponse("abcd");
        MockShutdownMonitor monitor = new MockShutdownMonitor(stopKey, runnable, false);
        monitor.start();
        
        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopKey = "foo";
        mojo.stopPort = monitor.getPort();
        mojo.setLog(log);
        
        mojo.execute();
        
        log.assertContains("Stopping jetty");
    }
    
    @Test
    public void testStopWaitBadPid() throws Exception
    {
        //test what happens if we get back a bad pid
        String stopKey = "foo";
        //start the responder
        MockShutdownMonitorRunnable runnable = new MockShutdownMonitorRunnable();
        runnable.setPidResponse("abcd");
        MockShutdownMonitor monitor = new MockShutdownMonitor(stopKey, runnable, false);
        monitor.start();
        
        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopWait = 5;
        mojo.stopKey = stopKey;
        mojo.stopPort = monitor.getPort();
        mojo.setLog(log);

        mojo.execute();
        
        log.assertContains("Server returned bad pid");
        log.assertContains("Server reports itself as stopped");
    }

    @Test
    public void testStopWait() throws Exception
    {
        String stopKey = "foo";

        List<String> cmd = new ArrayList<String>();
        String java = "java";
        String[] javaexes = new String[]{"java", "java.exe"};
        File javaHomeDir = new File(System.getProperty("java.home"));
        Path javaHomePath = javaHomeDir.toPath();
        for (String javaexe : javaexes)
        {
            Path javaBinPath = javaHomePath.resolve(Paths.get("bin", javaexe));
            if (Files.exists(javaBinPath) && !Files.isDirectory(javaBinPath))
                java = javaBinPath.toFile().getAbsolutePath();
        }

        cmd.add(java);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(MockShutdownMonitor.class.getCanonicalName());
        cmd.add(stopKey);    
        
        ProcessBuilder command = new ProcessBuilder(cmd);
        File file = MavenTestingUtils.getTargetFile("tester.out");
        command.redirectOutput(file);
        command.redirectErrorStream(true);
        command.directory(MavenTestingUtils.getTargetDir());
        Process fork = command.start();
        
        Thread.sleep(500);
        while (!file.exists() && file.length() == 0)
        {
            Thread.sleep(300);
        }

        String str = null;
        try (LineNumberReader reader = new LineNumberReader(new FileReader(file));)
        {
            str = reader.readLine();
        }
        assertNotNull(str);

        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopWait = 5;
        mojo.stopKey = stopKey;
        mojo.stopPort = Integer.valueOf(str).intValue();
        mojo.setLog(log);
        
        mojo.execute();
        
        log.dumpStdErr();
        
        log.assertContains("Waiting " + mojo.stopWait + " seconds for jetty " + fork.pid() + " to stop");
        log.assertContains("Server process stopped");
    }
}
