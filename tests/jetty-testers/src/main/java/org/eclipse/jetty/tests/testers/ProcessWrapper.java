//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.testers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A useful wrapper of {@link Process} instances.</p>
 * <p>The process output and error streams are captured each by a thread
 * associated with this instance, and and exposed via {@link #getLogs()}
 * and {@link #awaitConsoleLogsFor(String, Duration)}.</p>
 * <p>Process termination can be handled asynchronously via {@link #whenExit()}.</p>
 */
public class ProcessWrapper implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(ProcessWrapper.class);

    private final Queue<String> logs = new ConcurrentLinkedQueue<>();
    private final Process process;
    private final ConsoleStreamer stdOut;
    private final ConsoleStreamer stdErr;

    public ProcessWrapper(Process process)
    {
        this.process = process;
        this.stdOut = startConsoleStreamer("out", process.getInputStream());
        this.stdErr = startConsoleStreamer("err", process.getErrorStream());
    }

    public Process getProcess()
    {
        return process;
    }

    /**
     * @return a collection of the logs emitted on the process output and error streams
     */
    public Collection<String> getLogs()
    {
        return logs;
    }

    /**
     * <p>Returns a {@link CompletableFuture} that completes when the process exits
     * and when the threads capturing the process output and error stream exit.</p>
     * <p>The returned {@link CompletableFuture} can be used to wait for a timeout
     * via {@code processWrapper.whenExit().orTimeout(5, TimeUnit.SECONDS)}.</p>
     *
     * @return a CompletableFuture that completes when the process exits
     */
    public CompletableFuture<Process> whenExit()
    {
        return getProcess().onExit().thenCombine(joinConsoleStreamers(), (p, v) -> p);
    }

    /**
     * <p>Same as {@link Process#waitFor(long, TimeUnit)}.</p>
     * <p>Use this method for simple assertions in test
     * code, when it is known that the process will exit.</p>
     *
     * @param time the time to wait
     * @param unit the unit of time
     * @return {@code true} if the process has exited and {@code false} if the time elapsed before the process has exited
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitFor(long time, TimeUnit unit) throws InterruptedException
    {
        return getProcess().waitFor(time, unit);
    }

    public boolean awaitFor(Duration duration) throws InterruptedException
    {
        return awaitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * <p>Polls the console log lines derived from the
     * process output and error streams for the given text.</p>
     *
     * @param txt the text to search in the log lines
     * @param time the time to wait
     * @param unit the unit of time
     * @return {@code true} if the text was found in a log line,
     * {@code false} if the text was not found within the given time
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitConsoleLogsFor(String txt, long time, TimeUnit unit) throws InterruptedException
    {
        long poll = 50;
        long millis = unit.toMillis(time);
        while (millis > 0)
        {
            millis -= poll;
            Thread.sleep(poll);
            if (getLogs().stream().anyMatch(s -> s.contains(txt)))
                return true;
        }
        return false;
    }

    public boolean awaitConsoleLogsFor(String txt, Duration duration) throws InterruptedException
    {
        return awaitConsoleLogsFor(txt, duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * <p>Same as {@link Process#exitValue()}.</p>
     *
     * @return the process exit value
     * @throws IllegalThreadStateException if the process has not exited
     */
    public int getExitValue() throws IllegalThreadStateException
    {
        return getProcess().exitValue();
    }

    /**
     * <p>Stops the process by calling {@link Process#destroy()}, and returns {@link #whenExit()}.</p>
     *
     * @return a CompletableFuture that completes when the process exits
     */
    public CompletableFuture<Process> stop()
    {
        getProcess().destroy();
        return whenExit();
    }

    public Process forceStop()
    {
        return getProcess().destroyForcibly();
    }

    /**
     * <p>Calls {@link #stop()} and blocks via
     * {@link CompletableFuture#join()} until the process has exited.</p>
     */
    @Override
    public void close()
    {
        stop().join();
        LOG.info("Process exit with value {}", getProcess().exitValue());
    }

    private ConsoleStreamer startConsoleStreamer(String mode, InputStream stream)
    {
        ConsoleStreamer streamer = new ConsoleStreamer(mode, stream);
        streamer.start();
        return streamer;
    }

    private CompletableFuture<Void> joinConsoleStreamers()
    {
        return CompletableFuture.allOf(stdOut.join(), stdErr.join());
    }

    private class ConsoleStreamer implements Runnable
    {
        private final CompletableFuture<Void> completable = new CompletableFuture<>();
        private final Thread thread;
        private final BufferedReader reader;

        private ConsoleStreamer(String mode, InputStream stream)
        {
            this.thread = new Thread(this, "process/" + mode);
            this.reader = new BufferedReader(new InputStreamReader(stream));
        }

        private void start()
        {
            thread.start();
        }

        private CompletableFuture<Void> join()
        {
            return completable;
        }

        @Override
        public void run()
        {
            try
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    LOG.info(line);
                    logs.add(line);
                }
            }
            catch (Throwable x)
            {
                LOG.trace("", x);
            }
            finally
            {
                IO.close(reader);
                completable.complete(null);
            }
        }
    }
}
