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

package org.eclipse.jetty.util.thread.strategy;

import java.io.Closeable;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.IO;
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
 * <p>This strategy selects between the following sub-strategies:</p>
 * <dl>
 *     <dt>ProduceConsume(PC)</dt>
 *     <dd>The producing thread consumes the task by running it directly
 *     and then continues to produce.</dd>
 *     <dt>ProduceInvokeConsume(PIC)</dt>
 *     <dd>The producing thread consumes the task by running it with {@link Invocable#invokeNonBlocking(Runnable)}
 *     and then continues to produce.</dd>
 *     <dt>ProduceExecuteConsume(PEC)</dt>
 *     <dd>The producing thread dispatches the task to a thread pool to be executed
 *     and then continues to produce.</dd>
 *     <dt>ExecuteProduceConsume(EPC)</dt>
 *     <dd>The producing thread consumes dispatches a pending producer to a thread pool,
 *     then consumes the task by running it directly (as in PC mode), then races with
 *     the pending producer thread to take over production.
 *     </dd>
 * </dl>
 * <p>The sub-strategy is selected as follows:</p>
 * <dl>
 *     <dt>PC</dt>
 *     <dd>If the produced task is {@link Invocable.InvocationType#NON_BLOCKING}.</dd>
 *     <dt>EPC</dt>
 *     <dd>If the producing thread is not {@link Invocable.InvocationType#NON_BLOCKING}
 *     and a pending producer thread is available, either because there is already a pending producer
 *     or one is successfully started with {@link TryExecutor#tryExecute(Runnable)}.</dd>
 *     <dt>PIC</dt>
 *     <dd>If the produced task is {@link Invocable.InvocationType#EITHER} and EPC was not selected.</dd>
 *     <dt>PEC</dt>
 *     <dd>Otherwise.</dd>
 * </dl>
 *
 * <p>Because of the preference for {@code PC} mode, on a multicore machine with many
 * many {@link Invocable.InvocationType#NON_BLOCKING} tasks, multiple instances of the strategy may be
 * required to keep all CPUs on the system busy.</p>
 *
 * <p>Since the producing thread may be invoked with {@link Invocable#invokeNonBlocking(Runnable)}
 * this allows {@link AdaptiveExecutionStrategy}s to be efficiently and safely chained: a task
 * produced by one execution strategy may become itself be a producer in a second execution strategy
 * (e.g. an IO selector may use an execution strategy to handle multiple connections and each
 * connection may use a execution strategy to handle multiplexed channels/streams within the connection).</p>
 *
 * <p>A task containing another {@link AdaptiveExecutionStrategy} should identify as
 * {@link Invocable.InvocationType#EITHER} so when there are no pending producers threads available to
 * the first strategy, then it may invoke the second as {@link Invocable.InvocationType#NON_BLOCKING}.
 * This avoids starvation as the production on the second strategy can always be executed,
 * but without the risk that it may block the last available producer for the first strategy.</p>
 *
 * <p>This strategy was previously named EatWhatYouKill (EWYK) because its preference for a
 * producer to directly consume the tasks that it produces is similar to a hunting proverb
 * that says that a hunter should eat (i.e. consume) what they kill (i.e. produced).</p>
 */
@ManagedObject("Adaptive execution strategy")
public class AdaptiveExecutionStrategy extends ContainerLifeCycle implements ExecutionStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveExecutionStrategy.class);

    /**
     * The production state of the strategy.
     */
    private enum State
    {
        IDLE,       // No tasks or producers.
        PRODUCING,  // There is an active producing thread.
        REPRODUCING // There is an active producing thread and demand for more production.
    }

    /**
     * The sub-strategies used by the strategy to consume tasks that are produced.
     */
    private enum SubStrategy
    {
        /**
         * Consumes produced tasks and continues producing.
         */
        PRODUCE_CONSUME,
        /**
         * Consumes produced tasks as non blocking and continues producing.
         */
        PRODUCE_INVOKE_CONSUME,
        /**
         * Executes produced tasks and continues producing.
         */
        PRODUCE_EXECUTE_CONSUME,
        /**
         * Executes a pending producer, consumes produced tasks and races the pending producer to continue producing.
         */
        EXECUTE_PRODUCE_CONSUME
    }

    private final AutoLock _lock = new AutoLock();
    private final LongAdder _pcMode = new LongAdder();
    private final LongAdder _picMode = new LongAdder();
    private final LongAdder _pecMode = new LongAdder();
    private final LongAdder _epcMode = new LongAdder();
    private final Producer _producer;
    private final Executor _executor;
    private final TryExecutor _tryExecutor;
    private final Runnable _runPendingProducer = () -> tryProduce(true);
    private State _state = State.IDLE;
    private boolean _pending;

    /**
     * @param producer The produce of tasks to be consumed.
     * @param executor The executor to be used for executing producers or consumers, depending on the sub-strategy.
     */
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
            LOG.debug("{} dispatch {}", toStringLocked(), execute);
        if (execute)
            _executor.execute(_runPendingProducer);
    }

    @Override
    public void produce()
    {
        tryProduce(false);
    }

    /**
     * Tries to become the producing thread and then produces and consumes tasks.
     *
     * @param wasPending True if the calling thread was started as a pending producer.
     */
    private void tryProduce(boolean wasPending)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} tryProduce {}", toStringLocked(), wasPending);

        // Takes the lock to atomically check if the thread can produce.
        try (AutoLock l = _lock.lock())
        {
            // If the calling thread was the pending producer, there is no longer one pending.
            if (wasPending)
                _pending = false;

            switch (_state)
            {
                case IDLE:
                    // The strategy was IDLE, so this thread can become the producer.
                    _state = State.PRODUCING;
                    break;

                case PRODUCING:
                    // The strategy is already producing, so another thread must be the producer.
                    // However, it may be just about to stop being the producer so we set the
                    // REPRODUCING state to force it to produce at least once more.
                    _state = State.REPRODUCING;
                    return;

                case REPRODUCING:
                    // Another thread is already producing and will already try again to produce.
                    return;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }

        // Determine the thread's invocation type once, outside of the production loop.
        boolean nonBlocking = Invocable.isNonBlockingInvocation();
        while (isRunning())
        {
            try
            {
                Runnable task = produceTask();

                // If we did not produce a task
                if (task == null)
                {
                    // take the lock to atomically determine if we should keep producing.
                    try (AutoLock l = _lock.lock())
                    {
                        switch (_state)
                        {
                            case PRODUCING:
                                // The calling thread was the only producer, so it is now IDLE and we stop producing.
                                _state = State.IDLE;
                                return;

                            case REPRODUCING:
                                // Another thread may have queued a task and tried to produce
                                // so the calling thread should continue to produce.
                                _state = State.PRODUCING;
                                continue;

                            default:
                                throw new IllegalStateException(toStringLocked());
                        }
                    }
                }

                // Consume the task according the selected sub-strategy, then
                // continue producing only if the sub-strategy returns true.
                if (consumeTask(task, selectSubStrategy(task, nonBlocking)))
                    continue;
                return;
            }
            catch (Throwable th)
            {
                LOG.warn("Unable to produce", th);
            }
        }
    }

    /**
     * Selects the execution strategy.
     *
     * @param task The task to select the strategy for.
     * @param nonBlocking True if the producing thread cannot block.
     * @return The sub-strategy to use for the task.
     */
    private SubStrategy selectSubStrategy(Runnable task, boolean nonBlocking)
    {
        Invocable.InvocationType taskType = Invocable.getInvocationType(task);
        switch (taskType)
        {
            case NON_BLOCKING:
                // The produced task will not block, so use PC: consume task directly
                // and then resume production.
                return SubStrategy.PRODUCE_CONSUME;

            case EITHER:
                // The produced task may be run either as blocking or non blocking.

                // If the calling producing thread is already non-blocking, use PC.
                if (nonBlocking)
                    return SubStrategy.PRODUCE_CONSUME;

                // Take the lock to atomically check if a pending producer is available.
                try (AutoLock l = _lock.lock())
                {
                    // If a pending producer is available or one can be started
                    if (_pending || _tryExecutor.tryExecute(_runPendingProducer))
                    {
                        // Use EPC: the producer directly consumes the task, which may block
                        // and then races with the pending producer to resume production.
                        _pending = true;
                        _state = State.IDLE;
                        return SubStrategy.EXECUTE_PRODUCE_CONSUME;
                    }
                }

                // Otherwise use PIC: the producer consumes the task
                // in non-blocking mode and then resumes production.
                return SubStrategy.PRODUCE_INVOKE_CONSUME;

            case BLOCKING:
                // The produced task may block.

                // If the calling producing thread may also block
                if (!nonBlocking)
                {
                    // Take the lock to atomically check if a pending producer is available.
                    try (AutoLock l = _lock.lock())
                    {
                        // If a pending producer is available or one can be started
                        if (_pending || _tryExecutor.tryExecute(_runPendingProducer))
                        {
                            // use EPC: The producer directly consumes the task, which may block
                            // and then races with the pending producer to resume production.
                            _pending = true;
                            _state = State.IDLE;
                            return SubStrategy.EXECUTE_PRODUCE_CONSUME;
                        }
                    }
                }

                // Otherwise use PEC: the task is consumed by the executor and the producer continues to produce.
                return SubStrategy.PRODUCE_EXECUTE_CONSUME;

            default:
                throw new IllegalStateException(String.format("taskType=%s %s", taskType, this));
        }
    }

    /**
     * Consumes a task with a sub-strategy.
     *
     * @param task The task to consume.
     * @param subStrategy The execution sub-strategy to use to consume the task.
     * @return True if the sub-strategy requires the caller to continue to produce tasks.
     */
    private boolean consumeTask(Runnable task, SubStrategy subStrategy)
    {
        // Consume and/or execute task according to the selected mode.
        if (LOG.isDebugEnabled())
            LOG.debug("ss={} t={}/{} {}", subStrategy, task, Invocable.getInvocationType(task), toStringLocked());
        switch (subStrategy)
        {
            case PRODUCE_CONSUME:
                _pcMode.increment();
                runTask(task);
                return true;

            case PRODUCE_INVOKE_CONSUME:
                _picMode.increment();
                invokeAsNonBlocking(task);
                return true;

            case PRODUCE_EXECUTE_CONSUME:
                _pecMode.increment();
                execute(task);
                return true;

            case EXECUTE_PRODUCE_CONSUME:
                _epcMode.increment();
                runTask(task);

                // Race the pending producer to produce again.
                try (AutoLock l = _lock.lock())
                {
                    if (_state == State.IDLE)
                    {
                        // We beat the pending producer, so we will become the producer instead.
                        // The pending produce will become a noop if it arrives whilst we are producing,
                        // or it may take over if we subsequently do another EPC consumption.
                        _state = State.PRODUCING;
                        return true;
                    }
                }
                // The pending producer is now producing, so this thread no longer produces.
                return false;

            default:
                throw new IllegalStateException(String.format("ss=%s %s", subStrategy, this));
        }
    }

    /**
     * Runs a Runnable task, logging any thrown exception.
     *
     * @param task The task to run.
     */
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

    /**
     * Runs a task in non-blocking mode.
     *
     * @param task The task to run in non-blocking mode.
     */
    private void invokeAsNonBlocking(Runnable task)
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

    /**
     * Produces a task, logging any Throwable that may result.
     *
     * @return A produced task or null if there were no tasks or a Throwable was thrown.
     */
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

    /**
     * Executes a task via the {@link Executor} used to construct this strategy.
     * If the execution is rejected and the task is a Closeable, then it is closed.
     *
     * @param task The task to execute.
     */
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
                IO.close((Closeable)task);
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
        String ret;
        try (AutoLock l = _lock.lock())
        {
            ret = toStringLocked();
        }
        return ret;
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
