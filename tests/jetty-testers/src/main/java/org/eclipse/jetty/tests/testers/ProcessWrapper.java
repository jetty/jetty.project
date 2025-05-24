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
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.awaitility.core.ConditionEvaluationListener;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.EvaluatedCondition;
import org.awaitility.core.TimeoutEvent;
import org.eclipse.jetty.toolchain.test.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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

    public static final int START_TIMEOUT = Integer.getInteger("home.start.timeout", 30);

    private final Queue<String> logs = new ConcurrentLinkedQueue<>();
    private final Process process;
    private final ConsoleStreamer stdOut;
    private final ConsoleStreamer stdErr;

    public static final String JETTY_START_SEARCH = "Started oejs.Server@";

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

    public boolean awaitForJettyStart()
    {
        return awaitForJettyStart(START_TIMEOUT, TimeUnit.SECONDS);
    }

    private record ShowLogOnTimeout<T>(ProcessWrapper run) implements ConditionEvaluationListener<T>
    {
        @Override
        public void conditionEvaluated(EvaluatedCondition<T> condition)
        {
            // do nothing
        }

        @Override
        public void onTimeout(TimeoutEvent timeoutEvent)
        {
            System.out.println("LOGS: " + String.join("\n", run.getLogs()));
        }
    }

    public Supplier<String> logs()
    {
        return () -> String.join("\n", this.getLogs());
    }

    public boolean awaitForJettyStart(long time, TimeUnit unit)
    {
        // Started oejs.Server@
        try
        {
            await()
                    //.conditionEvaluationListener(new ShowLogOnTimeout<>(this))
                    .atMost(time, unit)
                    //.logging(s -> System.out.println("LOGS: " + logs().get()))
                    .until(() ->
                    {
                        try (Stream<String> lines = getLogs().stream())
                        {
                            return lines.anyMatch(line -> line.contains(JETTY_START_SEARCH));
                        }
                    });
        }
        catch (ConditionTimeoutException e)
        {
            // as is surefire show logs from the run
            throw new RuntimeException(logs().get(), e);
        }
        // assert no WARN in the logs
        assertThat(logs().get(), not(containsString("WARN  :")));
        return true;
    }

    public boolean awaitForStart() throws InterruptedException
    {
        return awaitForStart(START_TIMEOUT, TimeUnit.SECONDS);
    }

    public boolean awaitForStart(long time, TimeUnit unit) throws InterruptedException
    {
        try
        {
            getProcess().waitFor(time, unit);
        }
        catch (InterruptedException e)
        {
            throw new IllegalStateException(logs().get(), e);
        }
        // assert no WARN in the logs
        assertThat(logs().get(), not(containsString("WARN  :")));
        return true;
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

    /**
     * <p>Calls {@link #stop()} and blocks via
     * {@link CompletableFuture#join()} until the process has exited.</p>
     */
    @Override
    public void close()
    {
        stop().join();
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
                    LOG.debug(line);
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
