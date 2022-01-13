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

package org.eclipse.jetty.util;

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * This specialized demand handling implements a pattern that allows
 * a large job to be broken into smaller tasks using iteration
 * rather than recursion.
 * </p>
 */
public abstract class IteratingDemand<I, O>
{
    private enum State
    {
        IDLE,
        DEMANDING,
        ITERATING,
        STALLED
    }

    private final AutoLock _lock = new AutoLock();
    private State _state = State.IDLE;
    private long _demand;
    private I _input;

    protected IteratingDemand()
    {
    }

    /**
     * Demand Input.
     * <p>This method must be implemented to demand input to be processed.
     * Once the demand is met, the {@link #onInput(Object)} method should be called.</p>
     */
    protected abstract void demandInput();

    /**
     * Release input.
     * <p>This method may be extended to release any input buffers that are pooled
     * and/or reference counted.</p>
     * @param input The input to be released
     */
    protected void releaseInput(I input)
    {
    }

    /**
     * Produce Output from Input.
     * <p>
     * This method must be implemented to process the input and to produce the output.
     * The method is called repeatedly with the same input until complete is called and no more
     * output is produced.
     * The repeated calls may be called iteratively whilst there is demand or from the
     * scope of a subsequent call to demand.
     * Any produced output is consumed by calling the passed consumer.
     * </p>
     * @param input The input to process or null if input has been released.
     * @param release The method to call to signal that processing of the input is complete
     * @return The output from the produced from the input or null if the input is fully consumed.
     * @throws Throwable If an error occurs producing output.
     */
    protected abstract O produce(I input, Runnable release) throws Throwable;

    /**
     * Handle output.
     * <p>This method must be implemented to handle the output produced</p>
     * @param out The output produced
     */
    protected abstract void onOutput(O out);

    protected void failed(Throwable t)
    {
        if (t instanceof RuntimeException)
            throw (RuntimeException)t;
        if (t instanceof Error)
            throw (Error)t;
        throw new RuntimeException(t);
    }

    /**
     * Demand output that is produced from Input.
     * <p>
     * Calling this method may cause {@link #demandInput()} to be called if some
     * input is necessary to produced some output.  The {@link #produce(Object, Runnable)}
     * method is then iteratively called on any any input received by
     * {@link #onInput(Object)} and {@link #onOutput(Object)} called on any
     * produced output in order to satisfy the demand.
     * </p>
     * @param n The number of times that {@link #onOutput(Object)} must be called
     * to satisfy demand.
     */
    public void demand(long n)
    {
        if (n == 0)
            return;
        if (n < 0)
            throw new IllegalArgumentException("negative demand");

        boolean iterate = false;
        I input = null;
        try (AutoLock ignored = _lock.lock())
        {
            _demand = MathUtils.cappedAdd(_demand, n);
            switch (_state)
            {
                case IDLE:
                case STALLED:
                    input = _input;
                    iterate = true;
                    _state = State.ITERATING;
                    break;

                default:
                    break;
            }
        }

        if (iterate)
            iterate(input);
    }

    public void onInput(I input)
    {
        if (input == null)
            throw new IllegalArgumentException("null input");
        try (AutoLock ignored = _lock.lock())
        {
            if (_state != State.DEMANDING)
                throw new IllegalStateException(toString());

            _state = State.ITERATING;
            _input = input;
        }

        iterate(input);
    }

    private void iterate(I input)
    {
        boolean demandInput = false;

        // While we are processing
        while (true)
        {
            O produced;
            try
            {
                produced = produce(input, input == null ? null : this::release);
                if (produced != null)
                    onOutput(produced);
            }
            catch (Throwable x)
            {
                release();
                failed(x);
                break;
            }

            try (AutoLock ignored = _lock.lock())
            {
                if (_state != State.ITERATING)
                    throw new IllegalStateException(toString());

                if (produced != null)
                    _demand--;

                _state = _demand == 0
                    ? _input == null ? State.IDLE : State.STALLED
                    : _input == null && produced == null ? State.DEMANDING : State.ITERATING;

                demandInput = _state == State.DEMANDING;

                if (_state != State.ITERATING)
                    break;
            }
        }

        if (demandInput)
            demandInput();
    }

    private void release()
    {
        I release;
        try (AutoLock ignored = _lock.lock())
        {
            if (_state != State.ITERATING)
                throw new IllegalStateException(toString());
            release = _input;
            _input = null;
        }
        if (release != null)
            releaseInput(release);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), _state);
    }
}
