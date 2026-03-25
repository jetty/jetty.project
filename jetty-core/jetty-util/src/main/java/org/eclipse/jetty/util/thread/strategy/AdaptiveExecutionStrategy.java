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

package org.eclipse.jetty.util.thread.strategy;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
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
public class AdaptiveExecutionStrategy extends ContainerLifeCycle implements ExecutionStrategy, Runnable
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

    private final LongAdder _pcMode = new LongAdder();
    private final LongAdder _picMode = new LongAdder();
    private final LongAdder _pecMode = new LongAdder();
    private final LongAdder _epcMode = new LongAdder();
    private final LongAdder _epcProduce = new LongAdder();
    private final Producer _producer;
    private final TryExecutor _tryExecutor;
    private final Executor _executor;
    private final boolean _isUseVirtualThreads;
    private final AtomicReference<State> _state = new AtomicReference<>(State.IDLE);

    /**
     * @param producer The producer of tasks to be consumed.
     * @param executor The executor to be used for executing producers or consumers, depending on the sub-strategy.
     */
    public AdaptiveExecutionStrategy(Producer producer, Executor executor)
    {
        _producer = producer;
        _executor = VirtualThreads.getExecutor(executor);
        _tryExecutor = TryExecutor.asTryExecutor(executor);
        _isUseVirtualThreads = VirtualThreads.isUseVirtualThreads(executor);
        installBean(_producer);
        installBean(_executor);
        installBean(_tryExecutor);
        if (LOG.isDebugEnabled())
            LOG.debug("created {}", this);
    }

    @Override
    public void dispatch()
    {
        boolean execute = false;
        loop: while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case IDLE:
                    execute = true;
                    break loop;

                case PRODUCING:
                    if (!_state.compareAndSet(State.PRODUCING, State.REPRODUCING))
                        continue;
                    break loop;

                default:
                    break loop;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("dispatch {} {}", execute, this);
        if (execute)
        {
            // Try to avoid queuing a producer if we can run it directly.
            if (!_tryExecutor.tryExecute(this))
                _executor.execute(this);
        }
    }

    @Override
    public void produce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("produce() producing {}", this);
        tryProduce();
    }

    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("dispatch() producing {}", this);
        tryProduce();
    }

    /**
     * Tries to become the producing thread and then produces and consumes tasks.
     */
    private void tryProduce()
    {
        // check if the thread can produce.
        loop: while (true)
        {
            State state = _state.get();

            switch (state)
            {
                case IDLE:
                    // The strategy was IDLE, so this thread can become the producer.
                    if (!_state.compareAndSet(state, State.PRODUCING))
                        continue;
                    break loop;

                case PRODUCING:
                    // The strategy is already producing, so another thread must be the producer.
                    // However, it may be just about to stop being the producer so we set the
                    // REPRODUCING state to force it to produce at least once more.
                    if (!_state.compareAndSet(state, State.REPRODUCING))
                        continue;
                    return;

                case REPRODUCING:
                    // Another thread is already producing and will already try again to produce.
                    return;

                default:
                    throw new IllegalStateException(toString(state));
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("producing {}", this);

        // Determine the thread's invocation type once, outside the production loop.
        boolean nonBlocking = Invocable.isNonBlockingInvocation();
        running: while (isRunning())
        {
            try
            {
                Runnable task = produceTask();
                if (task != null)
                {
                    // Consume the task according to a selected substrategy.
                    if (consumeTask(task, selectSubStrategy(task, nonBlocking)))
                        // continue producing
                        continue;
                    // do not continue producing
                    return;
                }

                // No task produce, so determine if we should keep producing.
                while (true)
                {
                    State state = _state.get();
                    switch (state)
                    {
                        case PRODUCING:
                            // This thread is still the producer, so it is now IDLE and we stop producing.
                            if (!_state.compareAndSet(state, State.IDLE))
                                continue;
                            return;

                        case REPRODUCING:
                            // Another thread may have queued a task and tried to produce
                            // so the calling thread should continue to produce.
                            if (!_state.compareAndSet(state, State.PRODUCING))
                                continue;
                            continue running;

                        default:
                            throw new IllegalStateException(toString(state));
                    }
                }
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
            {
                // The produced task may be run either as blocking or non blocking.

                // If the calling producing thread is already non-blocking, use PC.
                // Since the task is EITHER, it must check Invocable#isNonBlockingInvocation, which is already set.
                if (nonBlocking)
                    return SubStrategy.PRODUCE_CONSUME;

                if (tryExecuteProduceConsume())
                    return SubStrategy.EXECUTE_PRODUCE_CONSUME;

                // Otherwise use PIC: this producer thread consumes the task
                // in non-blocking mode and then resumes production.
                // Since the task is EITHER, it must check Invocable#isNonBlockingInvocation to know it cannot block.
                return SubStrategy.PRODUCE_INVOKE_CONSUME;
            }

            case BLOCKING:
            {
                // The produced task may block.

                // If the calling thread may not block then we must use PEC: the task is consumed by the executor and
                // this producer thread continues to produce (or returns to outer execution strategy)
                if (nonBlocking)
                    return SubStrategy.PRODUCE_EXECUTE_CONSUME;

                if (tryExecuteProduceConsume())
                    return SubStrategy.EXECUTE_PRODUCE_CONSUME;

                // Otherwise use PEC: the task is consumed by the executor and this producer thread continues to produce.
                return SubStrategy.PRODUCE_EXECUTE_CONSUME;
            }

            default:
                throw new IllegalStateException(String.format("taskType=%s %s", taskType, this));
        }
    }

    private boolean tryExecuteProduceConsume()
    {
        // Try to go in EPC mode from PRODUCING/REPRODUCING, but only
        // if we can guarantee that there is another producer thread.

        State state = _state.get();
        if (!_state.compareAndSet(state, State.IDLE))
            return false;

        // If we can execute another producer, then we are EPC.
        if (_tryExecutor.tryExecute(this))
            return true;

        // No reserved thread available, so we need to try to return to production.
        while (true)
        {
            state = _state.get();
            switch (state)
            {
                case IDLE:
                    if (!_state.compareAndSet(state, State.PRODUCING))
                        continue;
                    // We are the producer again, so we are not EPC.
                    return false;
                case PRODUCING:
                    if (!_state.compareAndSet(state, State.REPRODUCING))
                        continue;
                    // Somebody else is producing so we can be EPC.
                    return true;
                case REPRODUCING:
                    return true;
            }
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
            LOG.debug("consumeTask ss={}/{}/{} t={} {}", subStrategy, Invocable.isNonBlockingInvocation(), Invocable.getInvocationType(task), task, this);
        return switch (subStrategy)
        {
            case PRODUCE_CONSUME -> pcRunTask(task);
            case PRODUCE_INVOKE_CONSUME -> picRunTask(task);
            case PRODUCE_EXECUTE_CONSUME -> pecRunTask(task);
            case EXECUTE_PRODUCE_CONSUME -> epcRunTask(task);
        };
    }

    /**
     * Runs a task in produce-consume mode.
     *
     * @param task the task to run
     * @return always true, as this thread remains the producer
     */
    private boolean pcRunTask(Runnable task)
    {
        _pcMode.increment();
        runTask(task);
        return true;
    }

    /**
     * Runs a task in execute-produce-consume mode.
     *
     * @param task the task to run
     * @return whether this thread remains the producer
     */
    private boolean epcRunTask(Runnable task)
    {
        _epcMode.increment();

        runTask(task);

        // Race the pending producer to produce again.
        while (true)
        {
            State state = _state.get();
            if (state != State.IDLE)
                // The pending producer is now producing, so this thread no longer produces.
                return false;

            if (!_state.compareAndSet(state, State.PRODUCING))
                continue;

            // We beat the pending producer, so we will become the producer instead.
            // The pending producer will become a noop if it arrives whilst we are producing,
            // or it may take over if we subsequently do another EPC consumption.
            _epcProduce.increment();
            return true;
        }
    }

    /**
     * Runs a task in produce-execute-consume mode.
     *
     * @param task the task to run
     * @return always true, as this thread remains the producer
     */
    private boolean pecRunTask(Runnable task)
    {
        _pecMode.increment();
        execute(task);
        return true;
    }

    /**
     * Runs a task in produce-invoke-consume mode.
     *
     * @param task the task to run
     * @return always true, as this thread remains the producer
     */
    private boolean picRunTask(Runnable task)
    {
        try
        {
            _picMode.increment();
            Invocable.invokeNonBlocking(task);
            return true;
        }
        catch (Throwable x)
        {
            LOG.warn("Task invoke failed", x);
            return true;
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
            {
                if (LOG.isTraceEnabled())
                    LOG.trace("IGNORED", e);
            }

            if (task instanceof Closeable)
                IO.close((Closeable)task);
        }
    }

    @ManagedAttribute(value = "whether this execution strategy uses virtual threads", readonly = true)
    public boolean isUseVirtualThreads()
    {
        return _isUseVirtualThreads;
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

    @ManagedAttribute(value = "number of times a EPC thread produces again", readonly = true)
    public long getEPCProduceCount()
    {
        return _epcProduce.longValue();
    }

    @ManagedAttribute(value = "whether this execution strategy is idle", readonly = true)
    public boolean isIdle()
    {
        return _state.get() == State.IDLE;
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
        return toString(_state.get());
    }

    private String toString(State state)
    {
        StringBuilder builder = new StringBuilder();
        getString(builder);
        getState(builder, state);
        return builder.toString();
    }

    private void getString(StringBuilder builder)
    {
        builder
            .append(TypeUtil.toShortName(getClass()))
            .append('@')
            .append(Integer.toHexString(hashCode()))
            .append('/')
            .append(_producer)
            .append('/');
    }

    private void getState(StringBuilder builder, State state)
    {
        builder.append(state)
            .append('/')
            .append(_tryExecutor)
            .append("[pc=")
            .append(getPCTasksConsumed())
            .append(",pic=")
            .append(getPICTasksExecuted())
            .append(",pec=")
            .append(getPECTasksExecuted())
            .append(",epc=")
            .append(getEPCProduceCount())
            .append("/")
            .append(getEPCTasksConsumed())
            .append("]");
    }
}
