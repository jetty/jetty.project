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
 * A Utility class to help implement {@link AsyncEndPoint#write(Object, Callback, ByteBuffer...)} by calling
 * {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #onIncompleteFlushed()} is called when not all content has been written after a call to
 * flush and should organise for the {@link #completeWrite()} method to be called when a subsequent call to flush
 * should  be able to make more progress.
 */
abstract public class WriteFlusher
{
    private static final Logger logger = Log.getLogger(WriteFlusher.class);
    private static final EnumMap<StateType, Set<StateType>> __stateTransitions = new EnumMap<>(StateType.class);
    private static final State idleState = new IdleState();
    private static State writingState = new WritingState();
    private static final State failedState = new FailedState();
    private static final State completingState = new CompletingState();
    private final EndPoint _endPoint;
    private final AtomicReference<State> _state = new AtomicReference<>();
    private volatile Throwable failure;

    static
    {
        // fill the state machine
        __stateTransitions.put(StateType.IDLE, EnumSet.of(StateType.WRITING, StateType.FAILED));
        __stateTransitions.put(StateType.WRITING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.FAILED));
        __stateTransitions.put(StateType.PENDING, EnumSet.of(StateType.IDLE, StateType.COMPLETING, StateType.FAILED));
        __stateTransitions.put(StateType.COMPLETING, EnumSet.of(StateType.IDLE, StateType.PENDING, StateType.FAILED));
        __stateTransitions.put(StateType.FAILED, EnumSet.noneOf(StateType.class));
    }

    protected WriteFlusher(EndPoint endPoint)
    {
        _state.set(idleState);
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
     * Tries to update the currenState to the given new state.
     * @param newState the desired new state
     * @return the state before the updateState or null if the state transition failed
     * @throws WritePendingException if currentState is WRITING and new state is WRITING (api usage error)
     */
    private State updateState(State newState)
    {
        State currentState = _state.get();
        boolean updated = false;

        while (!updated)
        {
            if (!isTransitionAllowed(currentState, newState))
                return null; // return false + currentState

            updated = _state.compareAndSet(currentState, newState);
            logger.debug("StateType update: {} -> {} {}", currentState, newState, updated ? "" : "failed");
            if (!updated)
                currentState = _state.get();
        }
        // We need to return true and currentState
        return currentState;
    }

    private boolean isTransitionAllowed(State currentState, State newState)
    {
        Set<StateType> allowedNewStateTypes = __stateTransitions.get(currentState.getType());
        if (currentState.getType() == StateType.WRITING && newState.getType() == StateType.WRITING)
        {
            throw new WritePendingException();
        }
        if (!allowedNewStateTypes.contains(newState.getType()))
        {
            logger.debug("StateType update: {} -> {} not allowed", currentState, newState);
            return false;
        }
        return true;
    }

    /**
     * State represents a State of WriteFlusher.
     *
     * @param <C>
     */
    private static class State<C>
    {
        private final StateType _type;
        private final C _context;
        private final Callback<C> _callback;
        private ByteBuffer[] _buffers;

        private State(StateType stateType, ByteBuffer[] buffers, C context, Callback<C> callback)
        {
            _type = stateType;
            _buffers = buffers;
            _context = context;
            _callback = callback;
        }

        /**
         * In most States this is a noop. In others it needs to be overwritten.
         *
         * @param cause cause of the failure
         */
        protected void fail(Throwable cause)
        {
        }

        /**
         * In most States this is a noop. In others it needs to be overwritten.
         */
        protected void complete()
        {
        }

        public StateType getType()
        {
            return _type;
        }

        protected C getContext()
        {
            return _context;
        }

        protected Callback<C> getCallback()
        {
            return _callback;
        }

        public ByteBuffer[] getBuffers()
        {
            return _buffers;
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
    private static class IdleState extends State<Void>
    {
        private IdleState()
        {
            super(StateType.IDLE, null, null, null);
        }
    }

    /**
     * In WritingState WriteFlusher is currently writing.
     */
    private static class WritingState extends State<Void>
    {
        private WritingState()
        {
            super(StateType.WRITING, null, null, null);
        }
    }

    /**
     * In FailedState no more operations are allowed. The current implementation will never recover from this state.
     */
    private static class FailedState extends State<Void>
    {
        private FailedState()
        {
            super(StateType.FAILED, null, null, null);
        }
    }

    /**
     * In CompletingState WriteFlusher is flushing buffers that have not been fully written in write(). If write()
     * didn't flush all buffers in one go, it'll switch the State to PendingState. completeWrite() will then switch to
     * this state and try to flush the remaining buffers.
     */
    private static class CompletingState extends State<Void>
    {
        private CompletingState()
        {
            super(StateType.COMPLETING, null, null, null);
        }
    }

    /**
     * In PendingState not all buffers could be written in one go. Then write() will switch to PendingState() and
     * preserve the state by creating a new PendingState object with the given parameters.
     *
     * @param <C>
     */
    private class PendingState<C> extends State<C>
    {
        private PendingState(ByteBuffer[] buffers, C context, Callback<C> callback)
        {
            super(StateType.PENDING, buffers, context, callback);
        }

        @Override
        protected void fail(Throwable cause)
        {
            getCallback().failed(getContext(), cause);
        }

        @Override
        protected void complete()
        {
            getCallback().completed(getContext());
        }
    }

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
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers)
    {
        if (callback == null)
            throw new IllegalArgumentException();
        logger.debug("write: {}", this);
        if (updateState(writingState) == null)
        {
            fail(context, callback, failure);
            return;
        }
        try
        {
            _endPoint.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    if (updateState(new PendingState<>(buffers, context, callback)) == null)
                        fail(context, callback, failure);
                    else
                        onIncompleteFlushed();
                    return;
                }
            }
            // If updateState didn't succeed, we don't care as our buffers have been written
            updateState(idleState);
            callback.completed(context);
        }
        catch (IOException e)
        {
            // If updateState didn't succeed, we don't care as writing our buffers failed
            updateState(failedState);
            fail(context, callback, e);
        }
    }

    private <C> void fail(C context, Callback<C> callback, Throwable failure)
    {
        if (failure == null)
            failure = new IllegalStateException();
        callback.failed(context, failure);
    }

    /**
     * Abstract call to be implemented by specific WriteFlushers. It should schedule a call to {@link #completeWrite()}
     * or {@link #failed(Throwable)} when appropriate.
     */
    abstract protected void onIncompleteFlushed();

    /**
     * Complete a write that has not completed and that called {@link #onIncompleteFlushed()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)} is likely to be able to progress.
     *
     * It tries to switch from PENDING to COMPLETING. If state transition fails, then it does nothing as the callback
     * should have been already failed. That's because the only way to switch from PENDING outside this method is
     * {@link #failed(Throwable)} or {@link #close()}
     */
    public void completeWrite()
    {
        State currentState = updateState(completingState);
        if (currentState == null)
            return;

        try
        {
            _endPoint.flush(currentState.getBuffers());

            // Are we complete?
            for (ByteBuffer b : currentState.getBuffers())
            {
                if (b.hasRemaining())
                {
                    if (updateState(currentState) == null)
                        currentState.fail(failure);
                    else
                        onIncompleteFlushed();
                    return;
                }
            }
            // If updateState didn't succeed, we don't care as our buffers have been written
            updateState(idleState);
            currentState.complete();
        }
        catch (IOException e)
        {
            // If updateState didn't succeed, we don't care as writing our buffers failed
            updateState(failedState);
            currentState.fail(e);
        }
    }

    public void failed(Throwable cause)
    {
        failure = cause;
        logger.debug("failed: " + this, cause);
        _state.get().fail(cause);
        updateState(failedState);
    }

    public void close()
    {
        failed(new ClosedChannelException());
    }

    public boolean isWritePending()
    {
        return _state.get().getType() == StateType.PENDING;
    }

    //TODO: remove
    State getState()
    {
        return _state.get();
    }

    @Override
    public String toString()
    {
        return String.format("WriteFlusher@%x{%s}", hashCode(), _state.get());
    }
}
