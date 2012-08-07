// ========================================================================
// Copyright (c) 2012-2012 Mort Bay Consulting Pty. Ltd.
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * A Utility class to help implement {@link EndPoint#write(Object, Callback, ByteBuffer...)} by calling
 * {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #onIncompleteFlushed()} is called when not all content has been written after a call to
 * flush and should organise for the {@link #completeWrite()} method to be called when a subsequent call to flush
 * should  be able to make more progress.
 * <p>
 */
abstract public class WriteFlusher
{
    private static final Logger LOG = Log.getLogger(WriteFlusher.class);
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
        __stateTransitions.put(StateType.PENDING, EnumSet.of(StateType.COMPLETING,StateType.IDLE));
        __stateTransitions.put(StateType.COMPLETING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.FAILED));
        __stateTransitions.put(StateType.FAILED, EnumSet.of(StateType.IDLE));
    }

    // A write operation may either complete immediately:
    //     IDLE-->WRITING-->IDLE
    // Or it may not completely flush and go via the PENDING state
    //     IDLE-->WRITING-->PENDING-->COMPLETING-->IDLE
    // Or it may take several cycles to complete
    //     IDLE-->WRITING-->PENDING-->COMPLETING-->PENDING-->COMPLETING-->IDLE
    //
    // If a failure happens while in IDLE, it is a noop since there is no operation to tell of the failure.
    // If a failure happens while in WRITING, but the the write has finished successfully or with an IOExceptions,
    // the callback's complete or respectively failed methods will be called.
    // If a failure happens in PENDING state, then the fail method calls the pending callback and moves to IDLE state
    //
    //   IDLE--(fail)-->IDLE
    //   IDLE-->WRITING--(fail)-->FAILED-->IDLE
    //   IDLE-->WRITING-->PENDING--(fail)-->IDLE
    //   IDLE-->WRITING-->PENDING-->COMPLETING--(fail)-->FAILED-->IDLE
    //
    // So a call to fail in the PENDING state will be directly handled and the state changed to IDLE
    // A call to fail in the WRITING or COMPLETING states will just set the state to FAILED and the failure will be
    // handled with the write or completeWrite methods try to move the state from what they thought it was.
    //

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
     * @param previous the expected current state
     * @param next the desired new state
     * @return the previous state or null if the state transition failed
     * @throws WritePendingException if currentState is WRITING and new state is WRITING (api usage error)
     */
    private boolean updateState(State previous,State next)
    {
        if (!isTransitionAllowed(previous,next))
            throw new IllegalStateException();

        boolean updated = _state.compareAndSet(previous, next);
        LOG.debug("State update {} -> {} succeeded: {}", previous, next, updated);
        return updated;
    }

    private void fail(PendingState<?> pending)
    {
        State current = _state.get();
        if (current.getType()==StateType.FAILED)
        {
            FailedState failed=(FailedState)current;
            if (updateState(failed,__IDLE))
            {
                pending.fail(failed.getCause());
                return;
            }
        }
        throw new IllegalStateException();
    }

    private void ignoreFail()
    {
        State current = _state.get();
        while (current.getType()==StateType.FAILED)
        {
            if (updateState(current,__IDLE))
                return;
            current = _state.get();
        }
    }

    private boolean isTransitionAllowed(State currentState, State newState)
    {
        Set<StateType> allowedNewStateTypes = __stateTransitions.get(currentState.getType());
        if (!allowedNewStateTypes.contains(newState.getType()))
        {
            LOG.debug("StateType update: {} -> {} not allowed", currentState, newState);
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
            _cause=cause;
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
     *
     * @param <C>
     */
    private class PendingState<C> extends State
    {
        private final C _context;
        private final Callback<C> _callback;
        private final ByteBuffer[] _buffers;

        private PendingState(ByteBuffer[] buffers, C context, Callback<C> callback)
        {
            super(StateType.PENDING);
            _buffers = buffers;
            _context = context;
            _callback = callback;
        }

        public ByteBuffer[] getBuffers()
        {
            return _buffers;
        }

        protected void fail(Throwable cause)
        {
            _callback.failed(_context, cause);
        }

        protected void complete()
        {
            _callback.completed(_context);
        }
    }

    /**
     * Abstract call to be implemented by specific WriteFlushers. It should schedule a call to {@link #completeWrite()}
     * or {@link #onFail(Throwable)} when appropriate.
     */
    abstract protected void onIncompleteFlushed();

    /**
     * Tries to switch state to WRITING. If successful it writes the given buffers to the EndPoint. If state transition
     * fails it'll fail the callback.
     *
     * If not all buffers can be written in one go it creates a new {@link PendingState} object to preserve the state
     * and then calls {@link #onIncompleteFlushed()}. The remaining buffers will be written in {@link #completeWrite()}.
     *
     * If all buffers have been written it calls callback.complete().
     *
     * @param context context to pass to the callback
     * @param callback the callback to call on either failed or complete
     * @param buffers the buffers to flush to the endpoint
     * @param <C> type of the context
     */
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws WritePendingException
    {
        if (callback == null)
            throw new IllegalArgumentException();
        LOG.debug("write: {}", this);

        if (!updateState(__IDLE,__WRITING))
            throw new WritePendingException();

        try
        {
            _endPoint.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    PendingState<?> pending=new PendingState<>(buffers, context, callback);
                    if (updateState(__WRITING,pending))
                        onIncompleteFlushed();
                    else
                        fail(new PendingState<>(buffers, context, callback));
                    return;
                }
            }

            // If updateState didn't succeed, we don't care as our buffers have been written
            if (!updateState(__WRITING,__IDLE))
                ignoreFail();
            callback.completed(context);
        }
        catch (IOException e)
        {
            if (updateState(__WRITING,__IDLE))
                callback.failed(context, e);
            else
                fail(new PendingState<>(buffers, context, callback));
        }
    }


    /**
     * Complete a write that has not completed and that called {@link #onIncompleteFlushed()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)} is likely to be able to progress.
     *
     * It tries to switch from PENDING to COMPLETING. If state transition fails, then it does nothing as the callback
     * should have been already failed. That's because the only way to switch from PENDING outside this method is
     * {@link #onFail(Throwable)} or {@link #onClose()}
     */
    public void completeWrite()
    {
        State previous = _state.get();

        if (previous.getType()!=StateType.PENDING)
            return; // failure already handled.

        PendingState<?> pending = (PendingState<?>)previous;
        if (!updateState(pending,__COMPLETING))
            return; // failure already handled.

        try
        {
            ByteBuffer[] buffers = pending.getBuffers();

            _endPoint.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    if (updateState(__COMPLETING,pending))
                        onIncompleteFlushed();
                    else
                        fail(pending);
                    return;
                }
            }

            // If updateState didn't succeed, we don't care as our buffers have been written
            if (!updateState(__COMPLETING,__IDLE))
                ignoreFail();
            pending.complete();
        }
        catch (IOException e)
        {
            if(updateState(__COMPLETING,__IDLE))
                pending.fail(e);
            else
                fail(pending);
        }
    }

    public void onFail(Throwable cause)
    {
        LOG.debug("failed: " + this, cause);

        // Keep trying to handle the failure until we get to IDLE or FAILED state
        while(true)
        {
            State current=_state.get();
            switch(current.getType())
            {
                case IDLE:
                    return;

                case PENDING:
                    PendingState<?> pending = (PendingState<?>)current;
                    if (updateState(pending,__IDLE))
                    {
                        pending.fail(cause);
                        return;
                    }
                    break;

                default:
                    if (updateState(current,new FailedState(cause)))
                        return;
                    break;
            }
        }
    }

    public void onClose()
    {
        if (_state.get()==__IDLE)
            return;
        onFail(new ClosedChannelException());
    }

    boolean isIdle()
    {
        return _state.get().getType() == StateType.IDLE;
    }

    public boolean isInProgress()
    {
        switch(_state.get().getType())
        {
            case WRITING:
            case PENDING:
            case COMPLETING:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString()
    {
        return String.format("WriteFlusher@%x{%s}", hashCode(), _state.get());
    }
}
