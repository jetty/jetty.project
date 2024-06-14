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

package org.eclipse.jetty.util;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>Exception (or rather {@link Throwable} utility methods.</p>
 */
public class ExceptionUtil
{

    /**
     * <p>Convert a {@link Throwable} to a specific type by casting or construction on a new instance.</p>
     *
     * @param <T> The type of the {@link Throwable} to be returned
     * @param type The type of the Throwable to be thrown. It must have a constructor that
     * takes a {@link Throwable} as a cause.
     * @param throwable The {@link Throwable} or null
     * @return A {@link Throwable} of type <code>T</code> or null.
     * @throws IllegalArgumentException if the passed <code>type</code> cannot be constructed with a cause.
     */
    public static <T extends Throwable> T as(Class<T> type, Throwable throwable) throws IllegalArgumentException
    {
        if (throwable == null)
            return null;

        if (type.isInstance(throwable))
        {
            @SuppressWarnings("unchecked")
            T t = (T)throwable;
            return t;
        }

        try
        {
            Constructor<T> constructor = type.getConstructor(Throwable.class);
            return constructor.newInstance(throwable);
        }
        catch (Exception e)
        {
            IllegalArgumentException iae = new IllegalArgumentException(e);
            if (areNotAssociated(iae, throwable))
                iae.addSuppressed(throwable);
            throw iae;
        }
        catch (Throwable t)
        {
            if (areNotAssociated(t, throwable))
                t.addSuppressed(throwable);
            throw t;
        }
    }

    /**
     * Throw a {@link Throwable} as a checked {@link Exception} if it
     * cannot be thrown as unchecked.
     * @param throwable The {@link Throwable} to throw or null.
     * @throws Error If the passed {@link Throwable} is an {@link Error}.
     * @throws Exception Otherwise, if the passed {@link Throwable} is not null.
     */
    public static void ifExceptionThrow(Throwable throwable)
        throws Error, Exception
    {
        if (throwable == null)
            return;
        if (throwable instanceof Error error)
            throw error;
        if (throwable instanceof Exception exception)
            throw exception;
        throw new RuntimeException(throwable);
    }

    /**
     * Throw a {@link Throwable} as an unchecked {@link Exception}.
     * @param throwable The {@link Throwable} to throw or null.
     * @throws Error If the passed {@link Throwable} is an {@link Error}.
     * @throws RuntimeException Otherwise, if the passed {@link Throwable} is not null.
     */
    public static void ifExceptionThrowUnchecked(Throwable throwable)
        throws Error, RuntimeException
    {
        if (throwable == null)
            return;
        if (throwable instanceof RuntimeException runtimeException)
            throw runtimeException;
        if (throwable instanceof Error error)
            throw error;
        throw new RuntimeException(throwable);
    }

    /**
     * <p>Throw a {@link Throwable} as a specific type, casting or construction as required.</p>
     *
     * @param <T> The type of the {@link Throwable} to be thrown if <code>throwable</code> is not null.
     * @param type The type of the Throwable to be thrown. It must have a constructor that
     * takes a {@link Throwable} as a cause.
     * @param throwable A {@link Throwable} or null.
     * @throws Error If the passed {@link Throwable} is an {@link Error}.
     * @throws RuntimeException If the passed {@link Throwable} is a {@link RuntimeException}.
     * @throws T Thrown in <code>throwable</code> is not null and neither an {@link Error} nor {@link RuntimeException}.
     * @throws IllegalArgumentException if the passed <code>type</code> cannot be constructed with a cause.
     */
    public static <T extends Throwable> void ifExceptionThrowAs(Class<T> type, Throwable throwable) throws
        Error, RuntimeException, T, IllegalArgumentException
    {
        if (throwable == null)
            return;

        if (throwable instanceof Error error)
            throw error;

        if (throwable instanceof RuntimeException runtimeException)
            throw runtimeException;

        throw as(type, throwable);
    }

    /**
     * <p>Throw a {@link Throwable} as a specific type, casting or construction as required.</p>
     * @param type The type of the Throwable to be thrown. It must have a constructor that
     * takes a {@link Throwable} as a cause.
     * @param throwable A {@link Throwable} or null.
     * @param <T> The type of the {@link Throwable} to be thrown if <code>throwable</code> is not null.
     * @throws T Thrown in <code>throwable</code> is not null and neither an {@link Error} nor {@link RuntimeException}.
     */
    public static <T extends Throwable> void ifExceptionThrowAllAs(Class<T> type, Throwable throwable) throws T
    {
        if (throwable == null)
            return;
        throw as(type, throwable);
    }

    /** Check if two {@link Throwable}s are associated.
     * @param t1 A Throwable or null
     * @param t2 Another Throwable or null
     * @return true iff the exceptions are not associated by being the same instance, sharing a cause or one suppressing the other.
     */
    public static boolean areAssociated(Throwable t1, Throwable t2)
    {
        return t1 != null && t2 != null && !areNotAssociated(t1, t2);
    }

    /** Check if two {@link Throwable}s are associated.
     * @param t1 A Throwable or null
     * @param t2 Another Throwable or null
     * @return true iff the exceptions are not associated by being the same instance, sharing a cause or one suppressing the other.
     */
    public static boolean areNotAssociated(Throwable t1, Throwable t2)
    {
        if (t1 == null || t2 == null)
            return false;
        while (t1 != null)
        {
            Throwable two = t2;
            while (two != null)
            {
                if (t1 == two)
                    return false;
                if (t1.getCause() == two)
                    return false;
                if (Arrays.asList(t1.getSuppressed()).contains(two))
                    return false;
                if (Arrays.asList(two.getSuppressed()).contains(t1))
                    return false;

                two = two.getCause();
            }
            t1 = t1.getCause();
        }

        return true;
    }

    /**
     * Add a suppressed exception if it is not associated.
     * @see #areNotAssociated(Throwable, Throwable)
     * @param throwable The main Throwable
     * @param suppressed The Throwable to suppress if it is not associated.
     */
    public static void addSuppressedIfNotAssociated(Throwable throwable, Throwable suppressed)
    {
        if (areNotAssociated(throwable, suppressed))
            throwable.addSuppressed(suppressed);
    }

    /**
     * Decorate a Throwable with the suppressed errors and return it.
     * @param t the throwable
     * @param errors the list of errors
     * @return the original throwable with suppressed errors
     * @param <T> of type Throwable
     */
    public static <T extends Throwable> T withSuppressed(T t, List<Throwable> errors)
    {
        if (errors != null)
            errors.stream().filter(e -> areNotAssociated(t, e)).forEach(t::addSuppressed);
        return t;
    }

    /* <p>A utility class for combining multiple exceptions which can be
     * used in the following pattern:
     * <pre>
     *     ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();
     *     manyThings.forEach(task ->
     *       {
     *         try
     *         {
     *             task.run();
     *         }
     *         catch(Throwable t)
     *         {
     *             multiException.add(t)
     *         }
     *       }
     *     }
     *     multiException.ifExceptionalThrowAs(IOException::new);
     * </pre>
     * <p>In some cases, an alternative style can be used:
     * <pre>
     *     ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();
     *     manyThings.forEach(multiException::callAndCatch);
     *     multiException.ifExceptionalThrow();
     * </pre>
     */
    public static class MultiException
    {
        private Throwable _multiException;

        public void add(Throwable t)
        {
            _multiException = ExceptionUtil.combine(_multiException, t);
        }

        public void ifExceptionThrow() throws Exception
        {
            ExceptionUtil.ifExceptionThrow(_multiException);
        }

        public void ifExceptionThrowRuntime()
        {
            ExceptionUtil.ifExceptionThrowUnchecked(_multiException);
        }

        public <T extends Throwable> void ifExceptionThrowAs(Class<T> type) throws T
        {
            ExceptionUtil.ifExceptionThrowAs(type, _multiException);
        }

        public void callAndCatch(Invocable.Callable task)
        {
            ExceptionUtil.call(task, this::add);
        }
    }

    /**
     * <p>Combine two, possible null, {@link Throwable}s in a style to facilitate handling
     * multiple exceptions that are accumulated as suppressed exceptions. This is freqently
     * used in the following pattern:</p>
     * <pre>
     *     Throwable multiException = null;
     *     for (Runnable task : manyThings)
     *     {
     *         try
     *         {
     *             task.run();
     *         }
     *         catch(Throwable t)
     *         {
     *             multiException = multiException.combine(multiException, t)
     *         }
     *     }
     *     MultiException.ifExceptionalThrow(multiException);
     * </pre>
     * @param t1 A Throwable or null
     * @param t2 Another Throwable or null
     * @return t1 with t2 suppressed, or null.
     */
    public static Throwable combine(Throwable t1, Throwable t2)
    {
        if (t1 == null)
            return t2;
        if (areNotAssociated(t1, t2))
            t1.addSuppressed(t2);
        return t1;
    }

    public static void callThen(Throwable cause, Consumer<Throwable> first, Consumer<Throwable> second)
    {
        try
        {
            first.accept(cause);
        }
        catch (Throwable t)
        {
            addSuppressedIfNotAssociated(cause, t);
        }
        finally
        {
            second.accept(cause);
        }
    }

    public static void callThen(Throwable cause, Consumer<Throwable> first, Runnable second)
    {
        try
        {
            first.accept(cause);
        }
        catch (Throwable t)
        {
            addSuppressedIfNotAssociated(cause, t);
        }
        finally
        {
            second.run();
        }
    }

    public static void callThen(Runnable first, Runnable second)
    {
        try
        {
            first.run();
        }
        catch (Throwable t)
        {
            // ignored
        }
        finally
        {
            second.run();
        }
    }

    /**
     * Call a {@link Invocable.Callable} and handle failures
     * @param callable The runnable to call
     * @param failure The handling of failures
     */
    public static void call(Invocable.Callable callable, Consumer<Throwable> failure)
    {
        try
        {
            callable.call();
        }
        catch (Throwable thrown)
        {
            try
            {
                failure.accept(thrown);
            }
            catch (Throwable alsoThrown)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(alsoThrown, thrown);
                ExceptionUtil.ifExceptionThrowUnchecked(alsoThrown);
            }
        }
    }

    /**
     * Call a {@link Runnable} and handle failures
     * @param runnable The runnable to call
     * @param failure The handling of failures
     */
    public static void run(Runnable runnable, Consumer<Throwable> failure)
    {
        try
        {
            runnable.run();
        }
        catch (Throwable thrown)
        {
            try
            {
                failure.accept(thrown);
            }
            catch (Throwable alsoThrown)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(alsoThrown, thrown);
                ExceptionUtil.ifExceptionThrowUnchecked(alsoThrown);
            }
        }
    }

    /**
     * <p>Get from a {@link CompletableFuture} and convert any uncheck exceptions to {@link RuntimeException}.</p>
     * @param completableFuture The future to get from.
     * @param <T> The type of the {@code CompletableFuture}
     * @return The value from calling {@link CompletableFuture#get()}
     * @throws RuntimeException if the call to {@link CompletableFuture#get()} throws.
     */
    public static <T> T get(CompletableFuture<T> completableFuture)
    {
        try
        {
            return completableFuture.get();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        catch (ExecutionException e)
        {
            throw new RuntimeException(e.getCause());
        }
    }

    private ExceptionUtil()
    {
    }
}
