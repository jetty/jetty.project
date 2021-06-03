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

package org.eclipse.jetty.util.thread.strategy;

import java.io.Closeable;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.TryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An adaptive execution strategy that uses the {@link Invocable} status
 * of both the task and the current thread to select an optimal strategy
 * that prioritizes executing the task immediately in the current
 * producing thread if it can be done so without thread starvation issues.</p>
 *
 * This strategy selects between the following sub-strategies:
 * <dl>
 *     <dt>ProduceConsume(PC)</dt>
 *     <dd>The producing thread consumes the task by executing it directly and then
 *     continues to produce.</dd>
 *     <dt>ProduceInvokeConsume(PIC)</dt>
 *     <dd>The producing thread consumes the task by invoking it with {@link Invocable#invokeNonBlocking(Runnable)}
 *     and then continues to produce.</dd>
 *     <dt>ProduceExecuteConsume(PEC)</dt>
 *     <dd>The producing thread dispatches the task to a thread pool to be executed and then immediately resumes
 *     producing.</dd>
 *     <dt>ExecuteProduceConsume(EPC)</dt>
 *     <dd>The producing thread consumes the task by executing it directly (as in PC mode) but then races with
 *     a pending producer thread to take over production.
 *     </dd>
 * </dl>
 * The sub-strategy is selected as follows:
 * <dl>
 *     <dt>PC</dt><dd>If the produced task has been invoked with {@link Invocable#invokeNonBlocking(Runnable)
 *     to indicate that it is {@link Invocable.InvocationType#NON_BLOCKING}.</dd>
 *     <dt>EPC</dt><dd>If the producing thread is not {@link Invocable.InvocationType#NON_BLOCKING}
 *     and a pending producer thread is available, either because there is already a pending producer
 *     or one is successfully started with {@link TryExecutor#tryExecute(Runnable)}.</dd>
 *     <dt>PIC</dt><dd>If the produced task has used the {@link Invocable#getInvocationType()} API to
 *     indicate that it is {@link Invocable.InvocationType#EITHER}.</dd>
 *     <dt>PEC</dt><dd>Otherwise.</dd>
 * </dl>
 * <p>Because of the preference for {@code PC} mode, on a multicore machine with many
 * many {@link Invocable.InvocationType#NON_BLOCKING} tasks, multiple instances of the strategy may be
 * required to keep all CPUs on the system busy.</p>
 * <p>Since the producing thread may be invoked with {@link Invocable#invokeNonBlocking(Runnable)
 * this allows {@link AdaptiveExecutionStrategy}s to be efficiently and safely chain: so that a task
 * produced by one execution strategy may become itself become a producer in a second execution strategy
 * (e.g. an IO selector may use an execution strategy to handle multiple connections and each
 * connection may use a execution strategy to handle multiplexed channels/streams within the connection).
 * If a task containing another execution strategy identifies as {@link Invocable.InvocationType#EITHER}
 * then the first strategy may invoke it as {@link Invocable.InvocationType#NON_BLOCKING} when it has
 * no pending producer threads available. This avoids thread starvation as the production on the second
 * strategy can always be executed, but without t he risk that it may block the last available producer
 * for the first strategy.</p>
 * <p>This strategy was previously named EatWhatYouKill (EWYK) because its preference to for
 * a producer to directly consume a task was similar to the hunting proverb, in the sense that one
 * should eat(consume) what they kill(produce).</p>
 *
 */
@ManagedObject("Adaptive execution strategy")
public class AdaptiveExecutionStrategy extends ContainerLifeCycle implements ExecutionStrategy, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveExecutionStrategy.class);

    private enum State
    {
        IDLE, PRODUCING, REPRODUCING
    }

    /* The modes this strategy can work in */
    private enum Mode
    {
        PRODUCE_CONSUME,
        PRODUCE_INVOKE_CONSUME, // This is PRODUCE_CONSUME an EITHER task with NON_BLOCKING invocation
        PRODUCE_EXECUTE_CONSUME,
        EXECUTE_PRODUCE_CONSUME // Eat What You Kill!
    }

    private final AutoLock _lock = new AutoLock();
    private final LongAdder _pcMode = new LongAdder();
    private final LongAdder _picMode = new LongAdder();
    private final LongAdder _pecMode = new LongAdder();
    private final LongAdder _epcMode = new LongAdder();
    private final Producer _producer;
    private final Executor _executor;
    private final TryExecutor _tryExecutor;
    private State _state = State.IDLE;
    private boolean _pending;

    public AdaptiveExecutionStrategy(Producer producer, Executor executor)
    {
        _producer = producer;
        _executor = executor;
        _tryExecutor = TryExecutor.asTryExecutor(executor);
        addBean(_producer);
        addBean(_tryExecutor);
        if (LOG.isDebugEnabled())
            LOG.debug("{} created", this);
    }

    @Override
    public void dispatch()
    {
        boolean execute = false;
        try (AutoLock l = _lock.lock())
        {
            switch (_state)
            {
                case IDLE:
                    if (!_pending)
                    {
                        _pending = true;
                        execute = true;
                    }
                    break;

                case PRODUCING:
                    _state = State.REPRODUCING;
                    break;

                default:
                    break;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} dispatch {}", this, execute);
        if (execute)
            _executor.execute(this);
    }

    @Override
    public void run()
    {
        tryProduce(true);
    }

    @Override
    public void produce()
    {
        tryProduce(false);
    }

    private void tryProduce(boolean wasPending)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} tryProduce {}", this, wasPending);

        try (AutoLock l = _lock.lock())
        {
            if (wasPending)
                _pending = false;

            switch (_state)
            {
                case IDLE:
                    // Enter PRODUCING
                    _state = State.PRODUCING;
                    break;

                case PRODUCING:
                    // Keep other Thread producing
                    _state = State.REPRODUCING;
                    return;

                default:
                    return;
            }
        }

        boolean nonBlocking = Invocable.isNonBlockingInvocation();

        while (isRunning())
        {
            try
            {
                if (doProduce(nonBlocking))
                    continue;
                return;
            }
            catch (Throwable th)
            {
                LOG.warn("Unable to produce", th);
            }
        }
    }

    private boolean doProduce(boolean nonBlocking)
    {
        Runnable task = produceTask();

        if (task == null)
        {
            try (AutoLock l = _lock.lock())
            {
                // Could another task just have been queued with a produce call?
                switch (_state)
                {
                    case PRODUCING:
                        _state = State.IDLE;
                        return false;

                    case REPRODUCING:
                        _state = State.PRODUCING;
                        return true;

                    default:
                        throw new IllegalStateException(toStringLocked());
                }
            }
        }

        Mode mode;
        Invocable.InvocationType taskType = Invocable.getInvocationType(task);
        if (taskType == Invocable.InvocationType.NON_BLOCKING)
        {
            mode = Mode.PRODUCE_CONSUME;
        }
        else if (!nonBlocking && (_pending || _tryExecutor.tryExecute(this)))
        {
            _pending = true;
            _state = State.IDLE;
            mode = Mode.EXECUTE_PRODUCE_CONSUME;
        }
        else if (taskType == Invocable.InvocationType.EITHER)
        {
            mode = Mode.PRODUCE_INVOKE_CONSUME;
        }
        else
        {
            mode = Mode.PRODUCE_EXECUTE_CONSUME;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} m={} t={}/{}", this, mode, task, Invocable.getInvocationType(task));

        // Consume or execute task
        switch (mode)
        {
            case PRODUCE_CONSUME:
                _pcMode.increment();
                runTask(task);
                return true;

            case PRODUCE_INVOKE_CONSUME:
                _picMode.increment();
                invokeTask(task);
                return true;

            case PRODUCE_EXECUTE_CONSUME:
                _pecMode.increment();
                execute(task);
                return true;

            case EXECUTE_PRODUCE_CONSUME:
                _epcMode.increment();
                runTask(task);

                // Try to produce again?
                try (AutoLock l = _lock.lock())
                {
                    if (_state == State.IDLE)
                    {
                        // We beat the pending producer, so we will become the producer instead
                        _state = State.PRODUCING;
                        return true;
                    }
                }
                return false;

            default:
                throw new IllegalStateException(toString());
        }
    }

    private void runTask(Runnable task)
    {
        try
        {
            task.run();
        }
        catch (Throwable x)
        {
            LOG.warn("Task run failed", x);
        }
    }

    private void invokeTask(Runnable task)
    {
        try
        {
            Invocable.invokeNonBlocking(task);
        }
        catch (Throwable x)
        {
            LOG.warn("Task invoke failed", x);
        }
    }

    private Runnable produceTask()
    {
        try
        {
            return _producer.produce();
        }
        catch (Throwable e)
        {
            LOG.warn("Task produce failed", e);
            return null;
        }
    }

    private void execute(Runnable task)
    {
        try
        {
            _executor.execute(task);
        }
        catch (RejectedExecutionException e)
        {
            if (isRunning())
                LOG.warn("Execute failed", e);
            else
                LOG.trace("IGNORED", e);

            if (task instanceof Closeable)
            {
                try
                {
                    ((Closeable)task).close();
                }
                catch (Throwable e2)
                {
                    LOG.trace("IGNORED", e2);
                }
            }
        }
    }

    @ManagedAttribute(value = "number of tasks consumed with PC mode", readonly = true)
    public long getPCTasksConsumed()
    {
        return _pcMode.longValue();
    }

    @ManagedAttribute(value = "number of tasks executed with PIC mode", readonly = true)
    public long getPICTasksExecuted()
    {
        return _picMode.longValue();
    }

    @ManagedAttribute(value = "number of tasks executed with PEC mode", readonly = true)
    public long getPECTasksExecuted()
    {
        return _pecMode.longValue();
    }

    @ManagedAttribute(value = "number of tasks consumed with EPC mode", readonly = true)
    public long getEPCTasksConsumed()
    {
        return _epcMode.longValue();
    }

    @ManagedAttribute(value = "whether this execution strategy is idle", readonly = true)
    public boolean isIdle()
    {
        try (AutoLock l = _lock.lock())
        {
            return _state == State.IDLE;
        }
    }

    @ManagedOperation(value = "resets the task counts", impact = "ACTION")
    public void reset()
    {
        _pcMode.reset();
        _epcMode.reset();
        _pecMode.reset();
        _picMode.reset();
    }

    @Override
    public String toString()
    {
        try (AutoLock l = _lock.lock())
        {
            return toStringLocked();
        }
    }

    public String toStringLocked()
    {
        StringBuilder builder = new StringBuilder();
        getString(builder);
        getState(builder);
        return builder.toString();
    }

    private void getString(StringBuilder builder)
    {
        builder.append(getClass().getSimpleName());
        builder.append('@');
        builder.append(Integer.toHexString(hashCode()));
        builder.append('/');
        builder.append(_producer);
        builder.append('/');
    }

    private void getState(StringBuilder builder)
    {
        builder.append(_state);
        builder.append("/p=");
        builder.append(_pending);
        builder.append('/');
        builder.append(_tryExecutor);
        builder.append("[pc=");
        builder.append(getPCTasksConsumed());
        builder.append(",pic=");
        builder.append(getPICTasksExecuted());
        builder.append(",pec=");
        builder.append(getPECTasksExecuted());
        builder.append(",epc=");
        builder.append(getEPCTasksConsumed());
        builder.append("]");
        builder.append("@");
        builder.append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()));
    }
}
