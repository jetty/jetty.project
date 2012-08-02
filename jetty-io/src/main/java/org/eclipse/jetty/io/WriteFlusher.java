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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * A Utility class to help implement {@link AsyncEndPoint#write(Object, Callback, ByteBuffer...)}
 * by calling {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #onIncompleteFlushed()} is called when not all content has been
 * written after a call to flush and should organise for the {@link #completeWrite()}
 * method to be called when a subsequent call to flush should be able to make more progress.
 */
abstract public class WriteFlusher
{
    private static final Logger logger = Log.getLogger(WriteFlusher.class);
    private final static ByteBuffer[] NO_BUFFERS = new ByteBuffer[0];
    private final EndPoint _endp;
    private final AtomicReference<State> _state = new AtomicReference<>();
    private final EnumMap<StateType, Set<StateType>> __stateTransitions = new EnumMap<>(StateType.class); //TODO: static
    private final State idleState = new IdleState(); //TODO: static all of them
    private final State writingState = new WritingState();
    private final State failedState = new FailedState();
    private final State completingState = new CompletedState();
    private volatile Throwable failure;

    protected WriteFlusher(EndPoint endp)
    {
        _state.set(idleState);
        _endp = endp;

        // fill the state machine
        __stateTransitions.put(StateType.IDLE, new HashSet<StateType>());
        __stateTransitions.put(StateType.WRITING, new HashSet<StateType>());
        __stateTransitions.put(StateType.PENDING, new HashSet<StateType>());
        __stateTransitions.put(StateType.COMPLETING, new HashSet<StateType>());
        __stateTransitions.put(StateType.FAILED, new HashSet<StateType>());

        __stateTransitions.get(StateType.IDLE).add(StateType.WRITING);
        __stateTransitions.get(StateType.WRITING).add(StateType.IDLE);
        __stateTransitions.get(StateType.WRITING).add(StateType.PENDING);
        __stateTransitions.get(StateType.WRITING).add(StateType.FAILED);
        __stateTransitions.get(StateType.PENDING).add(StateType.IDLE);
        __stateTransitions.get(StateType.PENDING).add(StateType.COMPLETING);
        __stateTransitions.get(StateType.PENDING).add(StateType.FAILED);
        __stateTransitions.get(StateType.COMPLETING).add(StateType.IDLE);
        __stateTransitions.get(StateType.COMPLETING).add(StateType.PENDING);
        __stateTransitions.get(StateType.COMPLETING).add(StateType.FAILED);

        __stateTransitions.get(StateType.IDLE).add(StateType.IDLE); // TODO: should never happen?! Probably remove this transition and just throw as this indicates a bug
    }

    private enum StateType
    {
        IDLE,
        WRITING,
        PENDING,
        COMPLETING,
        FAILED
    }

    private State updateState(State newState)
    {
        State currentState = _state.get();
        boolean updated = false;

        while (!updated)
        {
            if(!isTransitionAllowed(newState, currentState))
                return null; // return false + currentState

            updated = _state.compareAndSet(currentState, newState);
            logger.debug("StateType update: {} -> {} {}", currentState, newState, updated ? "" : "failed");
            if (!updated)
                currentState = _state.get();
        }
        // We need to return true and currentState
        return currentState;
    }

    private boolean isTransitionAllowed(State newState, State currentState)
    {
        Set<StateType> allowedNewStateTypes = __stateTransitions.get(currentState.getType());
        if (currentState.getType() == StateType.WRITING && newState.getType() == StateType.WRITING)
        {
            logger.debug("WRITE PENDING EXCEPTION"); //TODO: thomas remove, we don't log and throw
            throw new WritePendingException();
        }
        if (!allowedNewStateTypes.contains(newState.getType()))
        {
            logger.debug("{} -> {} not allowed.", currentState.getType(), newState.getType()); //thomas remove
            return false;
        }
        return true;
    }

    private abstract class State
    {
        protected StateType _type;
        protected ByteBuffer[] _buffers;
        protected Object _context;
        protected Callback<Object> _callback;

        private State(StateType stateType, ByteBuffer[] buffers, Object context, Callback<Object> callback)
        {
            _type = stateType;
            _buffers = buffers;
            _context = context;
            _callback = callback;
        }

        /**
         * In most States this is a noop. In others it needs to be overwritten.
         *
         * @param cause
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

        public void compactBuffers()
        {
            this._buffers = compact(_buffers);
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

    private class IdleState extends State
    {
        private IdleState()
        {
            super(StateType.IDLE, null, null, null);
        }
    }

    private class WritingState extends State
    {
        private WritingState()
        {
            super(StateType.WRITING, null, null, null);
        }
    }

    private class FailedState extends State
    {
        private FailedState()
        {
            super(StateType.FAILED, null, null, null);
        }
    }

    private class CompletedState extends State
    {
        private CompletedState()
        {
            super(StateType.COMPLETING, null, null, null);
        }
    }

    private class PendingState extends State
    {
        private PendingState(ByteBuffer[] buffers, Object context, Callback<Object> callback)
        {
            super(StateType.PENDING, buffers, context, callback);
        }

        @Override
        protected void fail(Throwable cause)
        {
            _callback.failed(_context, cause);
        }

        @Override
        protected void complete()
        {
            _callback.completed(_context);
        }
    }

    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers)
    {
        logger.debug("write: starting write. {}", _state); //thomas
        if (callback == null)
            throw new IllegalArgumentException();
        if(updateState(writingState) == null)
        {
            callback.failed(context, failure);
            return;
        }
        try
        {
            _endp.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    if(updateState(new PendingState(buffers, context, (Callback<Object>)callback)) == null)
                        callback.failed(context, failure);
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
            callback.failed(context, e);
        }
    }

    /**
     * Abstract call to be implemented by specific WriteFlushers.
     * It should schedule a call to {@link #completeWrite()} or
     * {@link #failed(Throwable)} when appropriate.
     *
     * @return true if a flush can proceed.
     */
    abstract protected void onIncompleteFlushed();


    /* Remove empty buffers from the start of a multi buffer array
     */
    private ByteBuffer[] compact(ByteBuffer[] buffers)
    {
        if (buffers.length < 2)
            return buffers;
        int b = 0;
        while (b < buffers.length && BufferUtil.isEmpty(buffers[b]))
            b++;
        if (b == 0)
            return buffers;
        if (b == buffers.length)
            return NO_BUFFERS;

        ByteBuffer[] compact = new ByteBuffer[buffers.length - b];
        System.arraycopy(buffers, b, compact, 0, compact.length);
        return compact;
    }

    /**
     * Complete a write that has not completed and that called
     * {@link #onIncompleteFlushed()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)}
     * is likely to be able to progress.
     */
    public void completeWrite()
    {
        State currentState = updateState(completingState);
        if (currentState == null || currentState.getType() != StateType.PENDING)
            return;

        try
        {
            currentState.compactBuffers(); //TODO: do we need it?
            _endp.flush(currentState.getBuffers());

            // Are we complete?
            for (ByteBuffer b : currentState.getBuffers())
            {
                if (b.hasRemaining())
                {
                    if(updateState(currentState)==null)
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
        State currentState = _state.get();
        logger.debug("failed: s={} e={}", _state, cause);
        updateState(failedState);
        currentState.fail(cause);
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
