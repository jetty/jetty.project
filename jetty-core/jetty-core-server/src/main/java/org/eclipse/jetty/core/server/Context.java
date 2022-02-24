package org.eclipse.jetty.core.server;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * A Context for handling/processing a request.
 * Every request will always have a non-null context, which may initially be the Server context, or
 * a context provided by a ContextHandler.
 * <p>
 * A Context is also an {@link Executor}, which allows tasks to be run by a thread pool, but scoped
 * to the classloader and any other aspects of the context.   Methods {@link #run(Runnable)},
 * {@link #call(Invocable.Callable)} and {@link #accept(Consumer, Throwable)}
 * are also provided to allow various functional interfaces to be called scoped to the context,
 * without being executed in another thread.
 * <p>
 * A Context is also a {@link Decorator}, allowing objects to be decorated in a context scope.
 *
 */
public interface Context extends Attributes, Decorator, Executor
{
    /**
     * @return The URI path prefix of the context, which may be null for the server context, or "/" for the root context.
     */
    String getContextPath();

    ClassLoader getClassLoader();

    Path getResourceBase();

    Request.Processor getErrorProcessor();

    void call(Invocable.Callable callable) throws Exception;

    void run(Runnable task);

    void accept(Consumer<Throwable> consumer, Throwable t);
}
