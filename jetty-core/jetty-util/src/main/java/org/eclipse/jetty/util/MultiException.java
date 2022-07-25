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

package org.eclipse.jetty.util;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wraps multiple exceptions.
 *
 * Allows multiple exceptions to be thrown as a single exception.
 *
 * The MultiException itself should not be thrown instead one of the
 * ifExceptionThrow* methods should be called instead.
 * TODO: Remove entirely in favor of Error lists with {@link TypeUtil#withSuppressed(Throwable, List)}?
 * TODO: Deprecate this now (if we do above)?
 */
@SuppressWarnings("serial")
public class MultiException extends Exception
{
    private static final String DEFAULT_MESSAGE = "Multiple exceptions";

    public MultiException()
    {
        this(DEFAULT_MESSAGE);
    }

    public MultiException(String message)
    {
        // Avoid filling in stack trace information.
        super(message, null, true, false);
    }

    public void add(Throwable e)
    {
        if (e instanceof MultiException mex)
        {
            for (Throwable throwable : mex.getSuppressed())
            {
                addSuppressed(throwable);
            }
        }
        else
        {
            addSuppressed(e);
        }
    }

    /**
     * Throw a MultiException.
     * If this multi exception is empty then no action is taken. If it
     * contains a single exception that is thrown, otherwise then this
     * multi exception is thrown.
     *
     * @throws Exception the Error or Exception if nested is 1, or the MultiException itself if nested is more than 1.
     */
    public void ifExceptionThrow()
        throws Exception
    {
        Throwable[] suppressed = getSuppressed();
        int count = suppressed.length;

        if (count <= 0)
            return;

        if (count == 1)
        {
            if (suppressed[0] instanceof Error)
                throw (Error)suppressed[0];
            if (suppressed[0] instanceof Exception ex)
                throw ex;
        }

        throw this;
    }

    /**
     * Throw a Runtime exception.
     * If this multi exception is empty then no action is taken. If it
     * contains a single error or runtime exception that is thrown, otherwise then this
     * multi exception is thrown, wrapped in a runtime exception.
     *
     * @throws Error If this exception contains exactly 1 {@link Error}
     * @throws RuntimeException If this exception contains 1 {@link Throwable} but it is not an error,
     * or it contains more than 1 {@link Throwable} of any type.
     */
    public void ifExceptionThrowRuntime()
    throws RuntimeException
    {
        Throwable[] suppressed = getSuppressed();
        int count = suppressed.length;

        if (count <= 0)
            return;

        if (count == 1)
        {
            if (suppressed[0] instanceof Error)
                throw (Error)suppressed[0];
            if (suppressed[0] instanceof RuntimeException ex)
                throw ex;
            else
                throw new RuntimeException(getMessage(), suppressed[0]);
        }

        throw new RuntimeException(getMessage(), this);
    }

    /**
     * Throw a multiexception.
     * If this multi exception is empty then no action is taken. If it
     * contains any exceptions then this multi exception is thrown.
     *
     * @throws MultiException the multiexception if there are nested exception
     */
    public void ifExceptionThrowMulti()
        throws MultiException
    {
        Throwable[] suppressed = getSuppressed();
        int count = suppressed.length;

        if (count <= 0)
            return;

        throw this;
    }

    @Override
    public String toString()
    {
        return Stream.of(getSuppressed())
            .map(throwable ->
            {
                if (throwable.getMessage() == null)
                    return throwable.getClass().getName();
                else
                    return String.format("%s:%s", throwable.getClass().getName(), throwable.getMessage());
            })
            .collect(Collectors.joining(", ", MultiException.class.getSimpleName() + "[", "]"));
    }
}
