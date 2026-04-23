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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.SocketAddress;
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
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Utility class to help implement {@link EndPoint#write(Callback, ByteBuffer...)} by calling
 * {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #onIncompleteFlush()} is called when not all content has been written after a call to
 * flush and should organize for the {@link #completeWrite()} method to be called when a subsequent call to flush
 * should  be able to make more progress.
 */
public abstract class WriteFlusher
{
    private static final Logger LOG = LoggerFactory.getLogger(WriteFlusher.class);
    private static final ByteBuffer[] EMPTY_BUFFERS = new ByteBuffer[]{BufferUtil.EMPTY_BUFFER};
    private static final EnumMap<StateType, Set<StateType>> __stateTransitions = new EnumMap<>(StateType.class);
    private static final State __IDLE = new IdleState();
    private static final State __FLUSHING = new FlushingState();
    private static final State __COMPLETING = new CompletingState();
    private static final State __CANCEL = new State(StateType.CANCEL);
    private final EndPoint _endPoint;
    private final AtomicReference<State> _state = new AtomicReference<>();

    static
    {
        // A write operation may either complete immediately:
        //     IDLE-->FLUSHING-->IDLE
        // Or it may not completely flush and go via the  state
        //     IDLE-->FLUSHING-->PENDING-->COMPLETING-->IDLE
        // Or it may take several cycles to complete
        //     IDLE-->FLUSHING-->PENDING-->COMPLETING-->PENDING-->COMPLETING-->IDLE
        //
        // If a failure happens while in IDLE, the state goes to FAILED even if there is no operation to tell of the failure.
        //     IDLE--(fail)-->FAILED
        //
        // If a cancel happens then:
        //     PENDING -> FAILED
        //     COMPLETING/FLUSHING -> CANCEL
        //     CANCEL -> CANCELLING
        //     CANCELLING -> FAILED
        //
        // From any other state than IDLE a failure will result in an FAILED state which is a terminal state, and
        // the callback is failed with the Throwable which caused the failure.
        //     IDLE-->FLUSHING--(fail)-->FAILED

        __stateTransitions.put(StateType.IDLE, EnumSet.of(StateType.FLUSHING, StateType.FAILED));
        __stateTransitions.put(StateType.FLUSHING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.CANCEL, StateType.FAILED));
        __stateTransitions.put(StateType.PENDING, EnumSet.of(StateType.COMPLETING, StateType.IDLE, StateType.FAILED));
        __stateTransitions.put(StateType.COMPLETING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.CANCEL, StateType.FAILED));
        __stateTransitions.put(StateType.CANCEL, EnumSet.of(StateType.CANCELLING));
        __stateTransitions.put(StateType.CANCELLING, EnumSet.of(StateType.FAILED));
        __stateTransitions.put(StateType.FAILED, EnumSet.noneOf(StateType.class));
    }

    protected WriteFlusher(EndPoint endPoint)
    {
        _state.set(__IDLE);
        _endPoint = endPoint;
    }

    private enum StateType
    {
        /** No write is in progress */
        IDLE,

        /** A flush is currently being attempted to progress the write */
        FLUSHING,

        /** The write was not able to be completed by a previous flush and {@link #onIncompleteFlush()} is waiting
         *  for {@link #completeWrite()} to be called */
        PENDING,

        /** The {@link #completeWrite()} method has been called and the write will be progressed */
        COMPLETING,

        /** The {@link WriteFlusher#cancelWrite(Throwable)} method was called whilst in {@link StateType#FLUSHING} or {@link StateType#COMPLETING},
         * so that when those operations complete, the next state will be {@link StateType#CANCELLING}*/
        CANCEL,

        /** A flush operation has completed and seen the {@link StateType#CANCEL} state.  Entering this state indicates that
         * the thread calling {@link WriteFlusher#cancelWrite(Throwable)} can continue to progress to the {@link StateType#FAILED} state. */
        CANCELLING,

        /** The write failed due to a failure from flushing, or cancellation is done. */
        FAILED
    }

    /**
     * Tries to update the current state to the given new state.
     *
     * @param previous the expected current state
     * @param next the desired new state
     * @return the previous state or null if the state transition failed
     * @throws IllegalStateException if previous to next is not a legal state transition (api usage error)
     */
    private boolean updateState(State previous, State next)
    {
        if (!isTransitionAllowed(previous, next))
            throw new IllegalArgumentException("Bad transition %s -> %s".formatted(previous, next));

        boolean updated = _state.compareAndSet(previous, next);
        if (LOG.isDebugEnabled())
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
    private static class FlushingState extends State
    {
        private FlushingState()
        {
            super(StateType.FLUSHING);
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
        private final SocketAddress _address;
        private final ByteBuffer[] _buffers;

        private PendingState(Callback callback, SocketAddress address, ByteBuffer[] buffers)
        {
            super(StateType.PENDING);
            _callback = callback;
            _address = address;
            _buffers = buffers;
        }

        InvocationType getCallbackInvocationType()
        {
            return Invocable.getInvocationType(_callback);
        }
    }

    private class CancellingState extends State
    {
        private final Callback _callback;

        private CancellingState(Callback callback)
        {
            super(StateType.CANCELLING);
            _callback = callback;
        }
    }

    public InvocationType getCallbackInvocationType()
    {
        State s = _state.get();
        return (s instanceof PendingState p) ? p.getCallbackInvocationType() : Invocable.InvocationType.BLOCKING;
    }

    /**
     * Abstract call to be implemented by specific WriteFlushers. It should schedule a call to {@link #completeWrite()}
     * or {@link #onFail(Throwable)} when appropriate.
     */
    protected abstract void onIncompleteFlush();

    /**
     * Tries to switch state to FLUSHING. If successful it writes the given buffers to the EndPoint. If state transition
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
        write(callback, null, buffers);
    }

    public void write(Callback callback, SocketAddress address, ByteBuffer... buffers) throws WritePendingException
    {
        Objects.requireNonNull(callback);

        if (isFailed())
        {
            fail(callback);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("write: {} {}", this, BufferUtil.toDetailString(buffers));

        if (!updateState(__IDLE, __FLUSHING))
            throw new WritePendingException();

        try
        {
            buffers = flush(address, buffers);

            if (buffers != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("flush incomplete {}", this);
                PendingState pending = new PendingState(callback, address, buffers);
                if (updateState(__FLUSHING, pending))
                    onIncompleteFlush();
                else
                    fail(callback);

                return;
            }

            if (updateState(__FLUSHING, __IDLE))
                callback.succeeded();
            else
                fail(callback);
        }
        catch (Throwable e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write exception", e);
            if (updateState(__FLUSHING, new FailedState(e)))
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
                case CANCEL:
                {
                    CancellingState cancellingState = new CancellingState(callback);
                    if (_state.compareAndSet(state, cancellingState))
                        return; // Let the cancel method return the callback
                    break;
                }

                case FAILED:
                {
                    FailedState failed = (FailedState)state;
                    cause = failed.getCause();
                    break loop;
                }

                case IDLE:
                case CANCELLING:
                    for (Throwable t : suppressed)
                    {
                        LOG.warn("Failed Write Cause", t);
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
     * <p>
     * It tries to switch from PENDING to COMPLETING. If state transition fails, then it does nothing as the callback
     * should have been already failed. That's because the only way to switch from PENDING outside this method is
     * {@link #onFail(Throwable)} or {@link #onClose()}
     */
    public void completeWrite()
    {
        if (LOG.isDebugEnabled())
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
            ByteBuffer[] buffers = pending._buffers;
            SocketAddress address = pending._address;

            buffers = flush(address, buffers);

            if (buffers != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("flushed incomplete {}", BufferUtil.toDetailString(buffers));
                if (buffers != pending._buffers)
                    pending = new PendingState(callback, address, buffers);
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
            if (LOG.isDebugEnabled())
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
     * @param address the datagram channel to send the buffers to (used by QUIC and HTTP/3)
     * @param buffers The buffers to flush
     * @return The unflushed buffers, or null if all flushed
     * @throws IOException if unable to flush
     */
    protected ByteBuffer[] flush(SocketAddress address, ByteBuffer[] buffers) throws IOException
    {
        boolean progress = true;
        while (progress && buffers != null)
        {
            long before = BufferUtil.remaining(buffers);
            boolean flushed = address == null ? _endPoint.flush(buffers) : _endPoint.send(address, buffers);
            long after = BufferUtil.remaining(buffers);
            long written = before - after;

            if (LOG.isDebugEnabled())
                LOG.debug("Flushed={} written={} remaining={} {}", flushed, written, after, this);

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
                case CANCEL:
                case CANCELLING:
                case FAILED:
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("ignored: {} {}", cause, this);
                        if (LOG.isTraceEnabled())
                            LOG.trace("IGNORED", cause);
                    }
                    return false;

                case PENDING:
                    if (LOG.isDebugEnabled())
                        LOG.debug("failed: {}", this, cause);

                    PendingState pending = (PendingState)current;
                    if (updateState(pending, new FailedState(cause)))
                    {
                        pending._callback.failed(cause);
                        return true;
                    }
                    break;

                case FLUSHING:
                case COMPLETING:
                    if (LOG.isDebugEnabled())
                        LOG.debug("failed: {}", this, cause);
                    if (updateState(current, new FailedState(cause)))
                        return true;
                    break;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * Abort any write the flusher may have in progress or pending, then prevent any further write.
     *
     * @param cause the cause
     * @return the callback of the write in progress or pending, null if the flusher was idle
     */
    public Callback cancelWrite(Throwable cause)
    {
        // Keep trying to handle the failure until we get to IDLE or FAILED state
        while (true)
        {
            State current = _state.get();
            switch (current.getType())
            {
                case IDLE:
                    if (updateState(current, new FailedState(cause)))
                        return null;
                    break;

                case FAILED:
                    return null;

                case PENDING:
                    PendingState pending = (PendingState)current;
                    if (updateState(current, new FailedState(cause)))
                        return pending._callback;
                    break;

                case COMPLETING:
                case FLUSHING:
                    updateState(current, __CANCEL);
                    break;

                case CANCEL:
                    // A concurrent thread is racing to move from COMPLETING state and it will
                    // soon discover the CANCEL state and instead move to CANCELLING.
                    // This thread can stay in this method until that other thread leaves CANCEL.
                    Thread.onSpinWait();
                    break;

                case CANCELLING:
                    CancellingState cancelling = (CancellingState)current;
                    if (updateState(current, new FailedState(cause)))
                        return cancelling._callback;
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

    public boolean isFailed()
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
        return switch (_state.get().getType())
        {
            case IDLE -> "-";
            case FLUSHING -> "F";
            case PENDING -> "P";
            case COMPLETING -> "C";
            case CANCEL -> "L";
            case CANCELLING -> "N";
            case FAILED -> "X";
        };
    }

    @Override
    public String toString()
    {
        State s = _state.get();
        return String.format("WriteFlusher@%x{%s}->%s", hashCode(), s, s instanceof PendingState ? ((PendingState)s)._callback : null);
    }
}
