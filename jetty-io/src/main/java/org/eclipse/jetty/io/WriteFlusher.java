//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;

/**
 * A Utility class to help implement {@link EndPoint#write(Callback, ByteBuffer...)} by calling
 * {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #onIncompleteFlush()} is called when not all content has been written after a call to
 * flush and should organize for the {@link #completeWrite()} method to be called when a subsequent call to flush
 * should  be able to make more progress.
 */
public abstract class WriteFlusher
{
    private static final Logger LOG = Log.getLogger(WriteFlusher.class);
    private static final boolean DEBUG = LOG.isDebugEnabled(); // Easy for the compiler to remove the code if DEBUG==false
    private static final ByteBuffer[] EMPTY_BUFFERS = new ByteBuffer[]{BufferUtil.EMPTY_BUFFER};
    private static final EnumMap<StateType, Set<StateType>> __stateTransitions = new EnumMap<>(StateType.class);
    private static final State __IDLE = new IdleState();
    private static final State __WRITING = new WritingState();
    private static final State __COMPLETING = new CompletingState();
    private final EndPoint _endPoint;
    private final AtomicReference<State> _state = new AtomicReference<>();

    static
    {
        // fill the state machine
        __stateTransitions.put(StateType.IDLE, EnumSet.of(StateType.WRITING));
        __stateTransitions.put(StateType.WRITING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.FAILED));
        __stateTransitions.put(StateType.PENDING, EnumSet.of(StateType.COMPLETING, StateType.IDLE, StateType.FAILED));
        __stateTransitions.put(StateType.COMPLETING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.FAILED));
        __stateTransitions.put(StateType.FAILED, EnumSet.noneOf(StateType.class));
    }

    // A write operation may either complete immediately:
    //     IDLE-->WRITING-->IDLE
    // Or it may not completely flush and go via the PENDING state
    //     IDLE-->WRITING-->PENDING-->COMPLETING-->IDLE
    // Or it may take several cycles to complete
    //     IDLE-->WRITING-->PENDING-->COMPLETING-->PENDING-->COMPLETING-->IDLE
    //
    // If a failure happens while in IDLE, it is a noop since there is no operation to tell of the failure.
    //     IDLE--(fail)-->IDLE
    //
    // From any other state than IDLE a failure will result in an FAILED state which is a terminal state, and
    // the callback is failed with the Throwable which caused the failure.
    //     IDLE-->WRITING--(fail)-->FAILED

    protected WriteFlusher(EndPoint endPoint)
    {
        _state.set(__IDLE);
        _endPoint = endPoint;
    }

    private enum StateType
    {
        IDLE,
        WRITING,
        PENDING,
        COMPLETING,
        FAILED
    }

    /**
     * Tries to update the current state to the given new state.
     *
     * @param previous the expected current state
     * @param next the desired new state
     * @return the previous state or null if the state transition failed
     * @throws WritePendingException if currentState is WRITING and new state is WRITING (api usage error)
     */
    private boolean updateState(State previous, State next)
    {
        if (!isTransitionAllowed(previous, next))
            throw new IllegalStateException();

        boolean updated = _state.compareAndSet(previous, next);
        if (DEBUG)
            LOG.debug("update {}:{}{}{}", this, previous, updated ? "-->" : "!->", next);
        return updated;
    }

    private boolean isTransitionAllowed(State currentState, State newState)
    {
        Set<StateType> allowedNewStateTypes = __stateTransitions.get(currentState.getType());
        if (!allowedNewStateTypes.contains(newState.getType()))
        {
            LOG.warn("{}: {} -> {} not allowed", this, currentState, newState);
            return false;
        }
        return true;
    }

    /**
     * State represents a State of WriteFlusher.
     */
    private static class State
    {
        private final StateType _type;

        private State(StateType stateType)
        {
            _type = stateType;
        }

        public StateType getType()
        {
            return _type;
        }

        @Override
        public String toString()
        {
            return String.format("%s", _type);
        }
    }

    /**
     * In IdleState WriteFlusher is idle and accepts new writes
     */
    private static class IdleState extends State
    {
        private IdleState()
        {
            super(StateType.IDLE);
        }
    }

    /**
     * In WritingState WriteFlusher is currently writing.
     */
    private static class WritingState extends State
    {
        private WritingState()
        {
            super(StateType.WRITING);
        }
    }

    /**
     * In FailedState no more operations are allowed. The current implementation will never recover from this state.
     */
    private static class FailedState extends State
    {
        private final Throwable _cause;

        private FailedState(Throwable cause)
        {
            super(StateType.FAILED);
            _cause = cause;
        }

        public Throwable getCause()
        {
            return _cause;
        }
    }

    /**
     * In CompletingState WriteFlusher is flushing buffers that have not been fully written in write(). If write()
     * didn't flush all buffers in one go, it'll switch the State to PendingState. completeWrite() will then switch to
     * this state and try to flush the remaining buffers.
     */
    private static class CompletingState extends State
    {
        private CompletingState()
        {
            super(StateType.COMPLETING);
        }
    }

    /**
     * In PendingState not all buffers could be written in one go. Then write() will switch to PendingState() and
     * preserve the state by creating a new PendingState object with the given parameters.
     */
    private class PendingState extends State
    {
        private final Callback _callback;
        private final ByteBuffer[] _buffers;

        private PendingState(ByteBuffer[] buffers, Callback callback)
        {
            super(StateType.PENDING);
            _buffers = buffers;
            _callback = callback;
        }

        public ByteBuffer[] getBuffers()
        {
            return _buffers;
        }

        InvocationType getCallbackInvocationType()
        {
            return Invocable.getInvocationType(_callback);
        }
    }

    public InvocationType getCallbackInvocationType()
    {
        State s = _state.get();
        return (s instanceof PendingState)
            ? ((PendingState)s).getCallbackInvocationType()
            : Invocable.InvocationType.BLOCKING;
    }

    /**
     * Abstract call to be implemented by specific WriteFlushers. It should schedule a call to {@link #completeWrite()}
     * or {@link #onFail(Throwable)} when appropriate.
     */
    protected abstract void onIncompleteFlush();

    /**
     * Tries to switch state to WRITING. If successful it writes the given buffers to the EndPoint. If state transition
     * fails it will fail the callback and leave the WriteFlusher in a terminal FAILED state.
     *
     * If not all buffers can be written in one go it creates a new {@code PendingState} object to preserve the state
     * and then calls {@link #onIncompleteFlush()}. The remaining buffers will be written in {@link #completeWrite()}.
     *
     * If all buffers have been written it calls callback.complete().
     *
     * @param callback the callback to call on either failed or complete
     * @param buffers the buffers to flush to the endpoint
     * @throws WritePendingException if unable to write due to prior pending write
     */
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        Objects.requireNonNull(callback);

        if (isFailed())
        {
            fail(callback);
            return;
        }

        if (DEBUG)
            LOG.debug("write: {} {}", this, BufferUtil.toDetailString(buffers));

        if (!updateState(__IDLE, __WRITING))
            throw new WritePendingException();

        try
        {
            buffers = flush(buffers);

            if (buffers != null)
            {
                if (DEBUG)
                    LOG.debug("flushed incomplete");
                PendingState pending = new PendingState(buffers, callback);
                if (updateState(__WRITING, pending))
                    onIncompleteFlush();
                else
                    fail(callback);

                return;
            }

            if (updateState(__WRITING, __IDLE))
                callback.succeeded();
            else
                fail(callback);
        }
        catch (Throwable e)
        {
            if (DEBUG)
                LOG.debug("write exception", e);
            if (updateState(__WRITING, new FailedState(e)))
                callback.failed(e);
            else
                fail(callback, e);
        }
    }

    private void fail(Callback callback, Throwable... suppressed)
    {
        Throwable cause;
        loop:
        while (true)
        {
            State state = _state.get();

            switch (state.getType())
            {
                case FAILED:
                {
                    FailedState failed = (FailedState)state;
                    cause = failed.getCause();
                    break loop;
                }

                case IDLE:
                    for (Throwable t : suppressed)
                    {
                        LOG.warn(t);
                    }
                    return;

                default:
                    Throwable t = new IllegalStateException();
                    if (!_state.compareAndSet(state, new FailedState(t)))
                        continue;

                    cause = t;
                    break loop;
            }
        }

        for (Throwable t : suppressed)
        {
            if (t != cause)
                cause.addSuppressed(t);
        }

        callback.failed(cause);
    }

    /**
     * Complete a write that has not completed and that called {@link #onIncompleteFlush()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)} is likely to be able to progress.
     *
     * It tries to switch from PENDING to COMPLETING. If state transition fails, then it does nothing as the callback
     * should have been already failed. That's because the only way to switch from PENDING outside this method is
     * {@link #onFail(Throwable)} or {@link #onClose()}
     */
    public void completeWrite()
    {
        if (DEBUG)
            LOG.debug("completeWrite: {}", this);

        State previous = _state.get();

        if (previous.getType() != StateType.PENDING)
            return; // failure already handled.

        PendingState pending = (PendingState)previous;
        if (!updateState(pending, __COMPLETING))
            return; // failure already handled.

        Callback callback = pending._callback;
        try
        {
            ByteBuffer[] buffers = pending.getBuffers();

            buffers = flush(buffers);

            if (buffers != null)
            {
                if (DEBUG)
                    LOG.debug("flushed incomplete {}", BufferUtil.toDetailString(buffers));
                if (buffers != pending.getBuffers())
                    pending = new PendingState(buffers, callback);
                if (updateState(__COMPLETING, pending))
                    onIncompleteFlush();
                else
                    fail(callback);
                return;
            }

            if (updateState(__COMPLETING, __IDLE))
                callback.succeeded();
            else
                fail(callback);
        }
        catch (Throwable e)
        {
            if (DEBUG)
                LOG.debug("completeWrite exception", e);
            if (updateState(__COMPLETING, new FailedState(e)))
                callback.failed(e);
            else
                fail(callback, e);
        }
    }

    /**
     * Flushes the buffers iteratively until no progress is made.
     *
     * @param buffers The buffers to flush
     * @return The unflushed buffers, or null if all flushed
     * @throws IOException if unable to flush
     */
    protected ByteBuffer[] flush(ByteBuffer[] buffers) throws IOException
    {
        boolean progress = true;
        while (progress && buffers != null)
        {
            long before = BufferUtil.remaining(buffers);
            boolean flushed = _endPoint.flush(buffers);
            long after = BufferUtil.remaining(buffers);
            long written = before - after;

            if (LOG.isDebugEnabled())
                LOG.debug("Flushed={} written={} remaining={} {}", flushed, written, after, this);

            if (written > 0)
            {
                Connection connection = _endPoint.getConnection();
                if (connection instanceof Listener)
                    ((Listener)connection).onFlushed(written);
            }

            if (flushed)
                return null;

            progress = written > 0;

            int index = 0;
            while (true)
            {
                if (index == buffers.length)
                {
                    // All buffers consumed.
                    buffers = null;
                    index = 0;
                    break;
                }
                else
                {
                    int remaining = buffers[index].remaining();
                    if (remaining > 0)
                        break;
                    ++index;
                    progress = true;
                }
            }
            if (index > 0)
                buffers = Arrays.copyOfRange(buffers, index, buffers.length);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("!fully flushed {}", this);

        // If buffers is null, then flush has returned false but has consumed all the data!
        // This is probably SSL being unable to flush the encrypted buffer, so return EMPTY_BUFFERS
        // and that will keep this WriteFlusher pending.
        return buffers == null ? EMPTY_BUFFERS : buffers;
    }

    /**
     * Notify the flusher of a failure
     *
     * @param cause The cause of the failure
     * @return true if the flusher passed the failure to a {@link Callback} instance
     */
    public boolean onFail(Throwable cause)
    {
        // Keep trying to handle the failure until we get to IDLE or FAILED state
        while (true)
        {
            State current = _state.get();
            switch (current.getType())
            {
                case IDLE:
                case FAILED:
                    if (DEBUG)
                        LOG.debug("ignored: " + this, cause);
                    return false;

                case PENDING:
                    if (DEBUG)
                        LOG.debug("failed: " + this, cause);

                    PendingState pending = (PendingState)current;
                    if (updateState(pending, new FailedState(cause)))
                    {
                        pending._callback.failed(cause);
                        return true;
                    }
                    break;

                case WRITING:
                case COMPLETING:
                    if (DEBUG)
                        LOG.debug("failed: " + this, cause);
                    if (updateState(current, new FailedState(cause)))
                        return true;
                    break;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    public void onClose()
    {
        switch (_state.get().getType())
        {
            case IDLE:
            case FAILED:
                return;

            default:
                onFail(new ClosedChannelException());
        }
    }

    boolean isFailed()
    {
        return isState(StateType.FAILED);
    }

    boolean isIdle()
    {
        return isState(StateType.IDLE);
    }

    public boolean isPending()
    {
        return isState(StateType.PENDING);
    }

    private boolean isState(StateType type)
    {
        return _state.get().getType() == type;
    }

    public String toStateString()
    {
        switch (_state.get().getType())
        {
            case WRITING:
                return "W";
            case PENDING:
                return "P";
            case COMPLETING:
                return "C";
            case IDLE:
                return "-";
            case FAILED:
                return "F";
            default:
                return "?";
        }
    }

    @Override
    public String toString()
    {
        State s = _state.get();
        return String.format("WriteFlusher@%x{%s}->%s", hashCode(), s, s instanceof PendingState ? ((PendingState)s)._callback : null);
    }

    /**
     * <p>A listener of {@link WriteFlusher} events.</p>
     */
    public interface Listener
    {
        /**
         * <p>Invoked when a {@link WriteFlusher} flushed bytes in a non-blocking way,
         * as part of a - possibly larger - write.</p>
         * <p>This method may be invoked multiple times, for example when writing a large
         * buffer: a first flush of bytes, then the connection became TCP congested, and
         * a subsequent flush of bytes when the connection became writable again.</p>
         * <p>This method is never invoked concurrently, but may be invoked by different
         * threads, so implementations may not rely on thread-local variables.</p>
         * <p>Implementations may throw an {@link IOException} to signal that the write
         * should fail, for example if the implementation enforces a minimum data rate.</p>
         *
         * @param bytes the number of bytes flushed
         * @throws IOException if the write should fail
         */
        void onFlushed(long bytes) throws IOException;
    }
}
