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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.awaitility.Awaitility;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Disabled //ShutdownMonitor singleton
public class TestJettyStopMojo
{
    /**
     * ShutdownMonitorMain
     * Kick off the ShutdownMonitor and wait for it to exit.
     */
    public static final class ShutdownMonitorMain
    {
        public static void main(String[] args)
        {
            try
            {
                //TODO needs visibility of the ShutdownMonitor instance
                /*                ShutdownMonitor monitor = ShutdownMonitor.getInstance();
                monitor.setPort(0);
                monitor.start();
                monitor.await();*/
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class TestLog implements org.apache.maven.plugin.logging.Log
    {
        List<String> sink = new ArrayList<>();

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
        //send a stop message and don't wait for the reply or the process to shutdown
        String stopKey = "foo";
        MockShutdownMonitorRunnable runnable = new MockShutdownMonitorRunnable();
        runnable.setPidResponse("abcd");
        MockShutdownMonitor monitor = new MockShutdownMonitor(stopKey, runnable);
        monitor.start();
        
        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopKey = stopKey;
        mojo.stopPort = monitor.getPort();
        mojo.setLog(log);
        
        mojo.execute();
        
        log.assertContains("Stopping jetty");
    }
    
    @Test
    public void testStopWaitBadPid() throws Exception
    {
        //test that even if we receive a bad pid, we still send the stop command and wait to
        //receive acknowledgement, but we don't wait for the process to exit
        String stopKey = "foo";
        MockShutdownMonitorRunnable runnable = new MockShutdownMonitorRunnable();
        runnable.setPidResponse("abcd");
        MockShutdownMonitor monitor = new MockShutdownMonitor(stopKey, runnable);
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
    public void testStopSameProcess() throws Exception
    {
        //test that if we need to stop a jetty in the same process as us
        //we will wait for it to exit
        String stopKey = "foo";
        long thisPid = ProcessHandle.current().pid();
        MockShutdownMonitorRunnable runnable = new MockShutdownMonitorRunnable();
        runnable.setPidResponse(Long.toString(thisPid));
        MockShutdownMonitor monitor = new MockShutdownMonitor(stopKey, runnable);
        monitor.start();
        
        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopWait = 5;
        mojo.stopKey = stopKey;
        mojo.stopPort = monitor.getPort();
        mojo.setLog(log);

        mojo.execute();
        
        log.assertContains("Waiting 5 seconds for jetty " + thisPid + " to stop");
    }
    
    @Test
    public void testStopWait() throws Exception
    {
        //test that we will communicate with a remote process and wait for it to exit
        String stopKey = "foo";
        List<String> cmd = new ArrayList<>();
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
        cmd.add("-DSTOP.KEY=" + stopKey);
        cmd.add("-DDEBUG=true");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ShutdownMonitorMain.class.getName());

        ProcessBuilder command = new ProcessBuilder(cmd);
        File file = MavenTestingUtils.getTargetFile("tester.out");
        command.redirectOutput(file);
        command.redirectErrorStream(true);
        command.directory(MavenTestingUtils.getTargetDir());
        Process fork = command.start();

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(file::exists);
        final String[] port = {null};
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
        {
            Optional<String> tmp = Files.readAllLines(file.toPath()).stream()
                    .filter(s -> s.startsWith("STOP.PORT=")).findFirst();
            if (tmp.isPresent())
            {
                // TODO validate it's an integer
                port[0] = tmp.get().substring(10);
                return true;
            }
            return false;

        });

        assertNotNull(port[0]);

        TestLog log = new TestLog();
        JettyStopMojo mojo = new JettyStopMojo();
        mojo.stopWait = 5;
        mojo.stopKey = stopKey;
        mojo.stopPort = Integer.parseInt(port[0]);
        mojo.setLog(log);

        mojo.execute();

        log.dumpStdErr();
        log.assertContains("Waiting " + mojo.stopWait + " seconds for jetty " + fork.pid() + " to stop");
        log.assertContains("Server process stopped");
    }
}
