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

package org.eclipse.jetty.util.thread;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <p>A task (typically either a {@link Runnable} or {@link Callable}
 * that declares how it will behave when invoked:</p>
 * <ul>
 * <li>blocking, the invocation will certainly block (e.g. performs blocking I/O)</li>
 * <li>non-blocking, the invocation will certainly <strong>not</strong> block</li>
 * <li>either, the invocation <em>may</em> block</li>
 * </ul>
 *
 * <p>
 * Static methods and are provided that allow the current thread to be tagged
 * with a {@link ThreadLocal} to indicate if it has a blocking invocation type.
 * </p>
 */
public interface Invocable
{
    Runnable NOOP = () -> {};

    ThreadLocal<Boolean> __nonBlocking = new ThreadLocal<>();

    /**
     * <p>The behavior of an {@link Invocable} when it is invoked.</p>
     * <p>Typically, {@link Runnable}s or {@link org.eclipse.jetty.util.Callback}s declare their
     * invocation type; this information is then used by the code that should
     * invoke the {@code Runnable} or {@code Callback} to decide whether to
     * invoke it directly, or submit it to a thread pool to be invoked by
     * a different thread.</p>
     */
    enum InvocationType
    {
        /**
         * <p>Invoking the {@link Invocable} may block the invoker thread,
         * and the invocation may be performed immediately (possibly blocking
         * the invoker thread) or deferred to a later time, for example
         * by submitting the {@code Invocable} to a thread pool.</p>
         * <p>This invocation type is suitable for {@code Invocable}s that
         * call application code, for example to process an HTTP request.</p>
         */
        BLOCKING,
        /**
         * <p>Invoking the {@link Invocable} does not block the invoker thread,
         * and the invocation may be performed immediately in the invoker thread.</p>
         * <p>This invocation type is suitable for {@code Invocable}s that
         * call implementation code that is guaranteed to never block the
         * invoker thread.</p>
         */
        NON_BLOCKING,
        /**
         * <p>Invoking the {@link Invocable} may block the invoker thread,
         * but the invocation cannot be deferred to a later time, differently
         * from {@link #BLOCKING}.</p>
         * <p>This invocation type is suitable for {@code Invocable}s that
         * themselves perform the non-deferrable action in a non-blocking way,
         * thus advancing a possibly stalled system.</p>
         */
        EITHER
    }

    /**
     * <p>A task with an {@link InvocationType}.</p>
     */
    interface Task extends Invocable, Runnable
    {
    }

    // TODO review.  Handy for lambdas that throw (eg LifeCycle#start())
    // TODO: there is already java.util.Callable, can we use it?
    interface Callable extends Invocable
    {
        void call() throws Exception;
    }

    // TODO javadoc
    interface InvocableBiConsumer<T, U> extends Invocable, BiConsumer<T, U>
    {
    }

    static <T, U> InvocableBiConsumer<T, U> from(InvocationType invocationType, BiConsumer<T, U> biConsumer)
    {
        return new InvocableBiConsumer<T, U>()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public void accept(T t, U u)
            {
                biConsumer.accept(t, u);
            }
        };
    }

    /**
     * <p>A {@link Runnable} decorated with an {@link InvocationType}.</p>
     */
    class ReadyTask implements Task
    {
        private final InvocationType type;
        private final Runnable task;

        public ReadyTask(InvocationType type, Runnable task)
        {
            this.type = type;
            this.task = task;
        }

        @Override
        public void run()
        {
            task.run();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return type;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s|%s]", getClass().getSimpleName(), hashCode(), type, task);
        }
    }

    /**
     * <p>Creates a {@link Task} from the given InvocationType and Runnable.</p>
     *
     * @param type the InvocationType
     * @param task the Runnable
     * @return a new Task
     */
    static Task from(InvocationType type, Runnable task)
    {
        if (task instanceof Task t && t.getInvocationType() == type)
            return t;
        return new ReadyTask(type, task);
    }

    /**
     * Test if the current thread has been tagged as non blocking
     *
     * @return True if the task the current thread is running has
     * indicated that it will not block.
     */
    static boolean isNonBlockingInvocation()
    {
        return Boolean.TRUE.equals(__nonBlocking.get());
    }

    /**
     * Invoke a task with the calling thread, tagged to indicate
     * that it will not block.
     *
     * @param task The task to invoke.
     */
    static void invokeNonBlocking(Runnable task)
    {
        Boolean wasNonBlocking = __nonBlocking.get();
        try
        {
            __nonBlocking.set(Boolean.TRUE);
            task.run();
        }
        finally
        {
            __nonBlocking.set(wasNonBlocking);
        }
    }

    /**
     * Combine two invocation type.
     * @param it1 A type
     * @param it2 Another type
     * @return The combination of both type, where any tendency to block overrules any non blocking.
     */
    static InvocationType combine(InvocationType it1, InvocationType it2)
    {
        if (it1 != null && it2 != null)
        {
            if (it1 == it2)
                return it1;
            if (it1 == InvocationType.EITHER)
                return it2;
            if (it2 == InvocationType.EITHER)
                return it1;
        }
        return InvocationType.BLOCKING;
    }

    static InvocationType combineTypes(InvocationType... it)
    {
        if (it == null || it.length == 0)
            return InvocationType.BLOCKING;
        InvocationType type = it[0];
        for (int i = 1; i < it.length; i++)
            type = combine(type, it[i]);
        return type;
    }

    /**
     * Get the invocation type of an Object.
     *
     * @param o The object to check the invocation type of.
     * @return If the object is an Invocable, it is coerced and the {@link #getInvocationType()}
     * used, otherwise {@link InvocationType#BLOCKING} is returned.
     */
    static InvocationType getInvocationType(Object o)
    {
        if (o instanceof Invocable)
            return ((Invocable)o).getInvocationType();
        return InvocationType.BLOCKING;
    }

    /**
     * @return The InvocationType of this object
     */
    default InvocationType getInvocationType()
    {
        return InvocationType.BLOCKING;
    }

    /**
     * Combine {@link Runnable}s into a single {@link Runnable} that sequentially calls the others.
     * @param runnables the {@link Runnable}s to combine
     * @return the combined {@link Runnable} with a combined {@link InvocationType}.
     */
    static Runnable combine(Runnable... runnables)
    {
        Runnable result = null;
        for (Runnable runnable : runnables)
        {
            if (runnable == null)
                continue;
            if (result == null)
            {
                result = runnable;
            }
            else
            {
                Runnable first = result;
                result = new Task()
                {
                    @Override
                    public void run()
                    {
                        first.run();
                        runnable.run();
                    }

                    @Override
                    public InvocationType getInvocationType()
                    {
                        return combine(Invocable.getInvocationType(first), Invocable.getInvocationType(runnable));
                    }
                };
            }
        }
        return result;
    }

    /**
     * An extension of {@link java.util.concurrent.CompletableFuture} that is an {@link Invocable}.
     * The {@link InvocationType} is the type passed in construction (default NON_BLOCKING).
     * Methods on {@link java.util.concurrent.CompletableFuture} that may act in contradiction to the passed
     * {@link InvocationType} are extended to throw {@link IllegalStateException} in those circumstances.
     * <p>
     * Counterintuitively, if the blocking APIs like {@link #get()} are to be used, the {@link InvocationType}
     * should be {@link InvocationType#NON_BLOCKING}, as the wake-up callbacks used will not block.
     *
     * @param <V> The type of the result
     * @deprecated This class in only used for deprecated usages of CompletableFuture
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    class InvocableCompletableFuture<V> extends java.util.concurrent.CompletableFuture<V> implements Invocable
    {
        private final InvocationType _invocationType;

        public InvocableCompletableFuture(InvocationType invocationType)
        {
            _invocationType = Objects.requireNonNull(invocationType);
            if (_invocationType == InvocationType.EITHER)
                throw new IllegalArgumentException("EITHER is not supported");
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _invocationType;
        }

        @Override
        public V get() throws InterruptedException, ExecutionException
        {
            if (getInvocationType() == InvocationType.BLOCKING && !isDone())
                throw new IllegalStateException("Must be NON_BLOCKING or completed");
            return super.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            if (getInvocationType() == InvocationType.BLOCKING && !isDone())
                throw new IllegalStateException("Must be NON_BLOCKING or completed");
            return super.get(timeout, unit);
        }

        @Override
        public V join()
        {
            if (!isDone() && getInvocationType() == InvocationType.BLOCKING && !isDone())
                throw new IllegalStateException("Must be NON_BLOCKING or completed");
            return super.join();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> acceptEither(CompletionStage<? extends V> other, Consumer<? super V> action)
        {
            if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");

            return super.acceptEither(other, action);
        }

        @Override
        public <U> java.util.concurrent.CompletableFuture<U> applyToEither(CompletionStage<? extends V> other, Function<? super V, U> fn)
        {
            if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(fn)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.applyToEither(other, fn);
        }

        @Override
        public <U> java.util.concurrent.CompletableFuture<U> handle(BiFunction<? super V, Throwable, ? extends U> fn)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.handle(fn);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.runAfterBoth(other, action);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action)
        {
            if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.runAfterEither(other, action);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> thenAccept(Consumer<? super V> action)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenAccept(action);
        }

        @Override
        public <U> java.util.concurrent.CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super V, ? super U> action)
        {
            if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenAcceptBoth(other, action);
        }

        @Override
        public <U> java.util.concurrent.CompletableFuture<U> thenApply(Function<? super V, ? extends U> fn)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenApply(fn);
        }

        @Override
        public <U, V1> java.util.concurrent.CompletableFuture<V1> thenCombine(CompletionStage<? extends U> other, BiFunction<? super V, ? super U, ? extends V1> fn)
        {
            if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(fn)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenCombine(other, fn);
        }

        @Override
        public <U> java.util.concurrent.CompletableFuture<U> thenCompose(Function<? super V, ? extends CompletionStage<U>> fn)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenCompose(fn);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> thenRun(Runnable action)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.thenRun(action);
        }

        @Override
        public java.util.concurrent.CompletableFuture<V> whenComplete(BiConsumer<? super V, ? super Throwable> action)
        {
            if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
                throw new IllegalStateException("Bad invocation type when not completed");
            return super.whenComplete(action);
        }
    }
}
