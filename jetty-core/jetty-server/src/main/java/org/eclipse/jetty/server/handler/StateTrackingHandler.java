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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * <p>A troubleshooting {@link Handler.Wrapper} that tracks whether
 * {@link Handler}/{@link Request}/{@link Response} asynchronous APIs
 * are properly used by applications.</p>
 * <p>The violation of these tracked APIs are reported to a {@link Listener}
 * instance; the default listener implementation emits warning logs.</p>
 * <p>{@code StateTrackingHandler} can be linked in at any point in
 * the {@code Handler} chain, and even be present in multiple instances,
 * likely configured differently.</p>
 * <p>For example, to troubleshoot wrong usages of the callback passed to method
 * {@link #handle(Request, Response, Callback)}, a {@code StateTrackingHandler}
 * should be configured as the outermost {@code Handler}.
 * This is because the {@code handle(...)} call propagates inwards.
 * In this way, {@code StateTrackingHandler} can wrap the callback passed
 * to inner {@code Handler}s and verify that it is eventually completed.</p>
 * <p>On the other hand, to troubleshoot custom {@code Handler} implementations
 * that perform wrapping of {@link Response#write(boolean, ByteBuffer, Callback)},
 * a {@code StateTrackingHandler} should be configured after the custom
 * {@code Handler} implementation.
 * This is because the {@code write(...)} call propagates outwards.
 * In this way, {@code StateTrackingHandler} can wrap the {@code write(...)}
 * call before forwarding it to outer {@code Handler}s and eventually to the
 * Jetty implementation, and verify that it is eventually completed.</p>
 *
 * @see Listener
 */
@ManagedObject
public class StateTrackingHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(StateTrackingHandler.class);

    private final Map<Request, StateInfo> stateInfos = new ConcurrentHashMap<>();
    private final Listener listener;
    private long handlerCallbackTimeout;
    private boolean completeHandlerCallbackAtTimeout;
    private long demandCallbackTimeout;
    private long writeTimeout;
    private long writeCallbackTimeout;

    /**
     * <p>Creates a new instance with a default {@link Listener}
     * that logs events at warning level.</p>
     */
    public StateTrackingHandler()
    {
        this(new WarnListener());
    }

    /**
     * <p>Creates a new instance with the given {@link Listener}.</p>
     *
     * @param listener the event listener
     */
    public StateTrackingHandler(Listener listener)
    {
        this.listener = listener;
    }

    /**
     * @return the timeout in ms for the completion of the {@link #handle(Request, Response, Callback)} callback
     */
    @ManagedAttribute("The timeout in ms for the completion of the handle() callback")
    public long getHandlerCallbackTimeout()
    {
        return handlerCallbackTimeout;
    }

    public void setHandlerCallbackTimeout(long timeout)
    {
        this.handlerCallbackTimeout = timeout;
    }

    /**
     * @return whether the {@link #handle(Request, Response, Callback)} callback is completed
     * in case the {@link #getHandlerCallbackTimeout()} expires
     * @see #getHandlerCallbackTimeout()
     */
    @ManagedAttribute("Whether the handle() callback is completed in case of timeout")
    public boolean isCompleteHandlerCallbackAtTimeout()
    {
        return completeHandlerCallbackAtTimeout;
    }

    public void setCompleteHandlerCallbackAtTimeout(boolean completeHandlerCallbackAtTimeout)
    {
        this.completeHandlerCallbackAtTimeout = completeHandlerCallbackAtTimeout;
    }

    /**
     * @return the timeout in ms for the execution of the demand callback passed to {@link Request#demand(Runnable)}
     */
    @ManagedAttribute("The timeout in ms for the execution of the demand callback")
    public long getDemandCallbackTimeout()
    {
        return demandCallbackTimeout;
    }

    public void setDemandCallbackTimeout(long timeout)
    {
        this.demandCallbackTimeout = timeout;
    }

    /**
     * @return the timeout in ms for the execution of a {@link Response#write(boolean, ByteBuffer, Callback)} call
     */
    @ManagedAttribute("The timeout in ms for the execution of a response write")
    public long getWriteTimeout()
    {
        return writeTimeout;
    }

    public void setWriteTimeout(long timeout)
    {
        this.writeTimeout = timeout;
    }

    /**
     * @return the timeout in ms for the execution of the response write callback passed to {@link Response#write(boolean, ByteBuffer, Callback)}
     */
    @ManagedAttribute("The timeout in ms for the execution of the response write callback")
    public long getWriteCallbackTimeout()
    {
        return writeCallbackTimeout;
    }

    public void setWriteCallbackTimeout(long timeout)
    {
        this.writeCallbackTimeout = timeout;
    }

    @Override
    public boolean handle(Request originalRequest, Response originalResponse, Callback originalCallback) throws Exception
    {
        StateInfo stateInfo = new StateInfo(originalRequest);
        stateInfos.put(originalRequest, stateInfo);
        Request.addCompletionListener(originalRequest, x -> stateInfos.remove(originalRequest));

        Request request = originalRequest;
        if (demandCallbackTimeout > 0)
            request = new RequestWrapper(stateInfo);

        Response response = originalResponse;
        if (writeTimeout > 0 || writeCallbackTimeout > 0)
            response = new ResponseWrapper(stateInfo, originalResponse);

        HandlerCallback callback = new HandlerCallback(stateInfo, originalCallback);
        stateInfo.handlerCallback = callback;

        try
        {
            boolean handled = super.handle(request, response, callback);
            callback.setHandled(handled);

            if (!handled)
            {
                // Check if the callback was completed.
                ThreadInfo completionThreadInfo = callback.getCompletionThreadInfo();
                if (completionThreadInfo != null)
                    notifyInvalidHandlerReturnValue(stateInfo.request, completionThreadInfo);
            }

            return handled;
        }
        catch (Throwable x)
        {
            stateInfos.remove(originalRequest);
            notifyHandlerException(stateInfo.request, x, callback.getCompletionThreadInfo());
            throw x;
        }
    }

    private void notifyInvalidHandlerReturnValue(Request request, ThreadInfo completionThreadInfo)
    {
        try
        {
            listener.onInvalidHandlerReturnValue(request, completionThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyHandlerException(Request request, Throwable failure, ThreadInfo completionThreadInfo)
    {
        try
        {
            listener.onHandlerException(request, failure, completionThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyHandlerCallbackNotCompleted(Request request, ThreadInfo handlerThreadInfo)
    {
        try
        {
            listener.onHandlerCallbackNotCompleted(request, handlerThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyDemandCallbackBlocked(Request request, ThreadInfo demandThreadInfo, ThreadInfo runThreadInfo)
    {
        try
        {
            listener.onDemandCallbackBlocked(request, demandThreadInfo, runThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyWriteBlocked(Request request, ThreadInfo writeThreadInfo, ThreadInfo writingThreadInfo)
    {
        try
        {
            listener.onWriteBlocked(request, writeThreadInfo, writingThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyWriteCallbackNotCompleted(Request request, Throwable failure, ThreadInfo writeThreadInfo)
    {
        try
        {
            listener.onWriteCallbackNotCompleted(request, failure, writeThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    private void notifyWriteCallbackBlocked(Request request, Throwable writeFailure, ThreadInfo writeThreadInfo, ThreadInfo callbackThreadInfo)
    {
        try
        {
            listener.onWriteCallbackBlocked(request, writeFailure, writeThreadInfo, callbackThreadInfo);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying {}", listener, x);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("requests", stateInfos.values()));
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(getClass().getSimpleName(), hashCode());
    }

    public static class ThreadInfo
    {
        private final String info;
        private final StackTraceElement[] stackFrames;

        private ThreadInfo(Thread thread)
        {
            this.info = thread.toString();
            this.stackFrames = thread.getStackTrace();
        }

        public String getInfo()
        {
            return info;
        }

        public StackTraceElement[] getStackFrames()
        {
            return stackFrames;
        }

        @Override
        public String toString()
        {
            return toString("");
        }

        private String toString(String indent)
        {
            StringBuilder builder = new StringBuilder();
            builder.append(getInfo()).append(System.lineSeparator());
            for (StackTraceElement stackFrame : getStackFrames())
            {
                builder.append(indent).append("\tat ").append(stackFrame).append(System.lineSeparator());
            }
            return builder.toString();
        }
    }

    /**
     * <p>Listener of events emitted by {@link StateTrackingHandler}.</p>
     * <p>The methods of this interface are named after the wrong API usages
     * tracked by {@code StateTrackingHandler}.</p>
     */
    public interface Listener extends EventListener
    {
        /**
         * <p>Invoked when the {@link Handler} chain returns {@code false},
         * but the handler callback has been completed.</p>
         * <p>This event is always enabled.</p>
         *
         * @param request the current request
         * @param completionThreadInfo the {@link ThreadInfo} of the thread that completed the handler callback
         */
        default void onInvalidHandlerReturnValue(Request request, ThreadInfo completionThreadInfo)
        {
        }

        /**
         * <p>Invoked when the {@link Handler} chain throws an exception from
         * the {@link Handler#handle(Request, Response, Callback)} method.</p>
         * <p>This event is always enabled.</p>
         *
         * @param request the current request
         * @param failure the exception thrown
         * @param completionThreadInfo the {@link ThreadInfo} of the thread that completed the handler callback,
         * or {@code null} if the handler callback has not been completed
         */
        default void onHandlerException(Request request, Throwable failure, ThreadInfo completionThreadInfo)
        {
        }

        /**
         * <p>Invoked when the {@link Handler} callback is not completed within
         * the timeout specified with {@link #getHandlerCallbackTimeout()}.</p>
         * <p>This event is enabled only when {@link #getHandlerCallbackTimeout()}
         * is non-{@code null}.</p>
         * <p>When handler thread has already returned from the handler chain,
         * the thread info parameter is {@code null}.
         * Otherwise, the handler thread has not returned yet and may be blocked,
         * and the thread info parameter is not {@code null}.</p>
         * <p>Note: when present, the thread info stack trace may not be accurate,
         * as the thread blockage might have resolved just before the thread info
         * was taken.</p>
         *
         * @param request the current request
         * @param handlerThreadInfo the handler thread info, or {@code null}
         * if the handler thread already returned from the handler chain
         */
        default void onHandlerCallbackNotCompleted(Request request, ThreadInfo handlerThreadInfo)
        {
        }

        /**
         * <p>Invoked when the {@link Request#demand(Runnable) request demand callback}
         * {@code run()} method blocks for longer than the timeout specified with
         * {@link #getDemandCallbackTimeout()}.</p>
         * <p>This event is enabled only when {@link #getDemandCallbackTimeout()}
         * is non-{@code null}.</p>
         * <p>Note: the thread info stack trace of the thread that is running the
         * demand callback may not be accurate, as the thread blockage might have
         * resolved just before the thread info was taken.</p>
         *
         * @param request the current request
         * @param demandThreadInfo the thread info of the thread that called {@link Request#demand(Runnable)}
         * @param runThreadInfo the thread info of the thread running the demand callback
         */
        default void onDemandCallbackBlocked(Request request, ThreadInfo demandThreadInfo, ThreadInfo runThreadInfo)
        {
        }

        /**
         * <p>Invoked when the {@link Response#write(boolean, ByteBuffer, Callback)} call
         * blocks for longer than the timeout specified with {@link #getWriteTimeout()}.</p>
         * <p>This event is enabled only when {@link #getWriteTimeout()} is non-{@code null}.</p>
         * <p>Note: the thread info stack trace of the thread that is writing may not be
         * accurate, as the thread blockage might have resolved just before the thread
         * info was taken.</p>
         *
         * @param request the current request
         * @param writeThreadInfo the thread info of the thread that called {@link Response#write(boolean, ByteBuffer, Callback)}
         * @param writingThreadInfo the thread info of the thread tht is writing
         */
        default void onWriteBlocked(Request request, ThreadInfo writeThreadInfo, ThreadInfo writingThreadInfo)
        {
        }

        /**
         * <p>Invoked when the write callback passed to {@link Response#write(boolean, ByteBuffer, Callback)}
         * is not completed for longer than the timeout specified with {@link #getWriteTimeout()}.</p>
         * <p>This event is enabled only when {@link #getWriteTimeout()} is non-{@code null}.</p>
         * <p>Note that the write might have been fully performed, but since the callback is not
         * completed, this case is indistinguishable from the case where the callback is not complete
         * because the write has not been fully performed.</p>
         *
         * @param request the current request
         * @param writeFailure the write failure, or {@code null} if the write succeeded
         * @param writeThreadInfo the thread info of the thread that called {@link Response#write(boolean, ByteBuffer, Callback)}
         */
        default void onWriteCallbackNotCompleted(Request request, Throwable writeFailure, ThreadInfo writeThreadInfo)
        {
        }

        /**
         * <p>Invoked when the write callback passed to {@link Response#write(boolean, ByteBuffer, Callback)}
         * blocks for longer than the timeout specified with {@link #getWriteCallbackTimeout()}.</p>
         * <p>This event is enabled only when {@link #getWriteCallbackTimeout()} is non-{@code null}.</p>
         * <p>Note: the thread info stack trace of the thread that is running the write callback may not be
         * accurate, as the thread blockage might have resolved just before the thread info was taken.</p>
         *
         * @param request the current request
         * @param writeFailure the write failure, or {@code null} if the write succeeded
         * @param writeThreadInfo the thread info of the thread that called {@link Response#write(boolean, ByteBuffer, Callback)}
         * @param callbackThreadInfo the thread info of the thread invoking the write callback
         */
        default void onWriteCallbackBlocked(Request request, Throwable writeFailure, ThreadInfo writeThreadInfo, ThreadInfo callbackThreadInfo)
        {
        }
    }

    private static class WarnListener implements Listener
    {
        @Override
        public void onInvalidHandlerReturnValue(Request request, ThreadInfo completionThreadInfo)
        {
            LOG.warn("handler callback completed but false returned for: {}{}completed by: {}",
                request,
                System.lineSeparator(),
                completionThreadInfo
            );
        }

        @Override
        public void onHandlerException(Request request, Throwable failure, ThreadInfo completionThreadInfo)
        {
            String format = "handler exception thrown for {}";
            List<Object> args = new ArrayList<>();
            args.add(request);
            if (completionThreadInfo != null)
            {
                format += "{}completed by: {}";
                args.add(System.lineSeparator());
                args.add(completionThreadInfo);
            }
            args.add(failure);
            LOG.warn(format, args.toArray());
        }

        @Override
        public void onHandlerCallbackNotCompleted(Request request, ThreadInfo handlerThreadInfo)
        {
            LOG.warn("handler callback not completed for: {}{}handled by: {}",
                request,
                System.lineSeparator(),
                handlerThreadInfo
            );
        }

        @Override
        public void onDemandCallbackBlocked(Request request, ThreadInfo demandThreadInfo, ThreadInfo runThreadInfo)
        {
            LOG.warn("demand callback blocked for: {}{}demanded by: {}{}possibly blocked: {}",
                request,
                System.lineSeparator(),
                demandThreadInfo,
                System.lineSeparator(),
                runThreadInfo
            );
        }

        @Override
        public void onWriteBlocked(Request request, ThreadInfo writeThreadInfo, ThreadInfo writingThreadInfo)
        {
            LOG.warn("write blocked for: {}{}write by: {}{}possibly blocked: {}",
                request,
                System.lineSeparator(),
                writeThreadInfo,
                System.lineSeparator(),
                writingThreadInfo
            );
        }

        @Override
        public void onWriteCallbackNotCompleted(Request request, Throwable writeFailure, ThreadInfo writeThreadInfo)
        {
            LOG.warn("write callback not completed for: {}{}write {} by: {}",
                request,
                System.lineSeparator(),
                writeFailure == null ? "succeeded" : "failed with " + writeFailure,
                writeThreadInfo
            );
        }

        @Override
        public void onWriteCallbackBlocked(Request request, Throwable writeFailure, ThreadInfo writeThreadInfo, ThreadInfo callbackThreadInfo)
        {
            LOG.warn("write callback blocked for: {}{}write {} by: {}{}possibly blocked: {}",
                request,
                System.lineSeparator(),
                writeFailure == null ? "succeeded" : "failed with " + writeFailure,
                writeThreadInfo,
                System.lineSeparator(),
                callbackThreadInfo
            );
        }
    }

    private class StateInfo implements Dumpable
    {
        private final Queue<RequestWrapper.DemandCallback> demandCallbacks = new ConcurrentLinkedQueue<>();
        private final Queue<ResponseWrapper.WriteCallback> writeCallbacks = new ConcurrentLinkedQueue<>();
        private final Request request;
        private volatile HandlerCallback handlerCallback;

        private StateInfo(Request request)
        {
            this.request = request;
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable demandDumpable = demandCallbackTimeout > 0
                ? new DumpableCollection("demands", demandCallbacks)
                : (o, i) -> o.append("demands not tracked\n");

            Dumpable writeDumpable = writeTimeout > 0 || writeCallbackTimeout > 0
                ? new DumpableCollection("writes", writeCallbacks)
                : (o, i) -> o.append("writes not tracked\n");

            Dumpable.dumpObjects(out, indent, request.toString(), handlerCallback, demandDumpable, writeDumpable);
        }
    }

    private class HandlerCallback extends Callback.Nested implements Runnable, Dumpable
    {
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Request request;
        private final Scheduler.Task task;
        private final Thread handleThread;
        private volatile Boolean handled;
        private volatile ThreadInfo completionThreadInfo;
        private volatile String completion;

        private HandlerCallback(StateInfo stateInfo, Callback callback)
        {
            super(callback);
            this.request = stateInfo.request;
            long timeout = getHandlerCallbackTimeout();
            this.task = timeout > 0 ? request.getComponents().getScheduler().schedule(this, timeout, MILLISECONDS) : () -> true;
            this.handleThread = Thread.currentThread();
        }

        private void setHandled(boolean handled)
        {
            this.handled = handled;
        }

        @Override
        public void succeeded()
        {
            if (completed(null))
                super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            if (completed(x))
                super.failed(x);
        }

        private boolean completed(Throwable failure)
        {
            if (!completed.compareAndSet(false, true))
                return false;

            boolean cancelled = task.cancel();

            if (LOG.isDebugEnabled())
                LOG.debug("handler callback timeout cancelled={} for {}", cancelled, request);

            completion = failure == null ? "succeeded" : "failed with " + failure;
            ThreadInfo threadInfo = completionThreadInfo = new ThreadInfo(Thread.currentThread());
            boolean notify = handled == Boolean.FALSE;
            if (notify)
                notifyInvalidHandlerReturnValue(request, threadInfo);

            return true;
        }

        private ThreadInfo getCompletionThreadInfo()
        {
            return completionThreadInfo;
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("handler callback not completed within {} for {}", getHandlerCallbackTimeout(), request);

            Boolean handled = this.handled;

            ThreadInfo handlerThreadInfo = null;
            // The Handler chain has not returned yet, likely the thread is blocked.
            if (handled == null)
            {
                // The thread info of the thread that blocks.
                // Only create it if the handler thread is blocked,
                // otherwise we will have a stack trace possibly
                // belonging to a different request.
                handlerThreadInfo = new ThreadInfo(handleThread);
            }
            notifyHandlerCallbackNotCompleted(request, handlerThreadInfo);

            if (isCompleteHandlerCallbackAtTimeout())
                super.failed(new TimeoutException());
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Boolean handled = this.handled;
            ThreadInfo handleThreadInfo = null;
            if (handled == null)
                handleThreadInfo = new ThreadInfo(handleThread);
            String completion = this.completion;
            ThreadInfo completionThreadInfo = this.completionThreadInfo;

            out.append("handle() result: %s\n".formatted(handled == null ? "pending" : handled));
            if (handleThreadInfo != null)
                out.append(indent).append(handleThreadInfo.toString(indent));

            out.append(indent).append("handler callback: %s [%s]\n".formatted(Objects.toString(completion, "not completed"), getCallback()));
            if (completionThreadInfo != null)
                out.append(indent).append(completionThreadInfo.toString(indent));
        }
    }

    private class RequestWrapper extends Request.Wrapper
    {
        private final StateInfo stateInfo;

        private RequestWrapper(StateInfo stateInfo)
        {
            super(stateInfo.request);
            this.stateInfo = stateInfo;
        }

        @Override
        public void demand(Runnable reader)
        {
            DemandCallback demandCallback = new DemandCallback(reader);
            stateInfo.demandCallbacks.offer(demandCallback);
            super.demand(demandCallback);
        }

        private class DemandCallback implements Invocable.Task, Dumpable
        {
            private final Runnable callback;
            private final ThreadInfo demandThreadInfo;
            // Tri-state: null -> no demand, thread -> running demand, this -> done running demand.
            private volatile Object demandRunner;

            private DemandCallback(Runnable callback)
            {
                this.callback = callback;
                this.demandThreadInfo = new ThreadInfo(Thread.currentThread());
            }

            @Override
            public void run()
            {
                demandRunner = Thread.currentThread();
                Scheduler.Task task = getComponents().getScheduler().schedule(this::expired, getDemandCallbackTimeout(), MILLISECONDS);
                try
                {
                    callback.run();
                }
                finally
                {
                    demandRunner = this;
                    stateInfo.demandCallbacks.remove(this);
                    task.cancel();
                }
            }

            private void expired()
            {
                Object demandRunner = this.demandRunner;
                // If expired() lost the race with run(), return.
                if (demandRunner == this)
                    return;

                // Avoid clash with Request.LOG.
                Logger log = StateTrackingHandler.LOG;
                if (log.isDebugEnabled())
                    log.debug("demand callback blocked more than {} for {}", getDemandCallbackTimeout(), getWrapped());

                // The thread info of the thread that blocks.
                ThreadInfo runThreadInfo = new ThreadInfo((Thread)demandRunner);
                notifyDemandCallbackBlocked(getWrapped(), demandThreadInfo, runThreadInfo);
            }

            @Override
            public void dump(Appendable out, String indent) throws IOException
            {
                Object demandRunner = this.demandRunner;
                if (demandRunner instanceof Thread runThread)
                {
                    out.append("demand: running [%s]\n".formatted(callback));
                    out.append(indent).append(new ThreadInfo(runThread).toString(indent));
                }
                else
                {
                    out.append("demand: %s [%s]\n".formatted(demandRunner == null ? "pending" : "none", callback));
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.getInvocationType(callback, false);
            }
        }
    }

    private class ResponseWrapper extends Response.Wrapper
    {
        private final StateInfo stateInfo;

        private ResponseWrapper(StateInfo stateInfo, Response wrapped)
        {
            super(stateInfo.request, wrapped);
            this.stateInfo = stateInfo;
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            WriteCallback writeCallback = new WriteCallback(callback);
            stateInfo.writeCallbacks.offer(writeCallback);
            try
            {
                super.write(last, byteBuffer, writeCallback);
                writeCallback.writeComplete(null);
            }
            catch (Throwable x)
            {
                writeCallback.writeComplete(x);
                throw x;
            }
        }

        private class WriteCallback extends Callback.Nested implements Dumpable
        {
            private final AtomicBoolean callbackCompleted = new AtomicBoolean();
            private final Thread writeThread;
            private final ThreadInfo writeThreadInfo;
            private final Scheduler.Task writeTask;
            // Tri-state: null -> write pending, this -> write succeeded, Throwable -> write failed.
            private volatile Object writeCompleted;
            // Tri-state: null -> callback pending, thread -> callback running, this -> callback completed.
            private volatile Object callbackRunner;

            private WriteCallback(Callback callback)
            {
                super(callback);
                this.writeThread = Thread.currentThread();
                this.writeThreadInfo = new ThreadInfo(writeThread);
                long writeTimeout = getWriteTimeout();
                this.writeTask = writeTimeout > 0 ? stateInfo.request.getComponents().getScheduler().schedule(this::writeExpired, writeTimeout, MILLISECONDS) : null;
            }

            private void writeComplete(Throwable failure)
            {
                writeCompleted = failure == null ? this : failure;
            }

            private void writeExpired()
            {
                Object writeCompleted = this.writeCompleted;

                Request request = stateInfo.request;

                if (LOG.isDebugEnabled())
                    LOG.debug("write not completed within {} for {}", getWriteTimeout(), request);

                if (writeCompleted == null)
                    notifyWriteBlocked(request, writeThreadInfo, new ThreadInfo(writeThread));
                else
                    notifyWriteCallbackNotCompleted(request, writeCompleted == this ? null : (Throwable)writeCompleted, writeThreadInfo);
            }

            @Override
            public void succeeded()
            {
                if (!callbackCompleted.compareAndSet(false, true))
                    return;

                if (writeTask != null)
                    writeTask.cancel();

                callbackRunner = Thread.currentThread();
                long timeout = getWriteCallbackTimeout();
                Scheduler.Task task = timeout > 0 ? stateInfo.request.getComponents().getScheduler().schedule(() -> callbackExpired(null), timeout, MILLISECONDS) : null;
                try
                {
                    super.succeeded();
                }
                finally
                {
                    callbackRunner = this;
                    stateInfo.writeCallbacks.remove(this);
                    if (task != null)
                        task.cancel();
                }
            }

            @Override
            public void failed(Throwable x)
            {
                if (!callbackCompleted.compareAndSet(false, true))
                    return;

                if (writeTask != null)
                    writeTask.cancel();

                callbackRunner = Thread.currentThread();
                long timeout = getWriteCallbackTimeout();
                Scheduler.Task task = timeout > 0 ? stateInfo.request.getComponents().getScheduler().schedule(() -> callbackExpired(x), timeout, MILLISECONDS) : null;
                try
                {
                    super.failed(x);
                }
                finally
                {
                    callbackRunner = this;
                    stateInfo.writeCallbacks.remove(this);
                    if (task != null)
                        task.cancel();
                }
            }

            private void callbackExpired(Throwable failure)
            {
                Object callbackRunner = this.callbackRunner;
                // Return if callbackExpired() lost the race with succeeded()/failed().
                if (callbackRunner == this)
                    return;

                if (LOG.isDebugEnabled())
                    LOG.debug("write callback not completed within {} for {}", getWriteCallbackTimeout(), getRequest());

                // The thread info of the thread that blocks.
                ThreadInfo callbackThreadInfo = new ThreadInfo((Thread)callbackRunner);
                notifyWriteCallbackBlocked(getRequest(), failure, writeThreadInfo, callbackThreadInfo);
            }

            @Override
            public void dump(Appendable out, String indent) throws IOException
            {
                Object writeCompleted = this.writeCompleted;
                if (writeCompleted == null)
                {
                    out.append("write: pending\n");
                    out.append(indent).append(new ThreadInfo(writeThread).toString(indent));
                }
                else
                {
                    out.append("write: %s\n".formatted(writeCompleted == this ? "succeeded" : "failed with " + writeCompleted));
                }

                Object callbackRunner = this.callbackRunner;
                if (callbackRunner instanceof Thread callbackThread)
                {
                    out.append(indent).append("write callback: running [%s]\n".formatted(getCallback()));
                    out.append(indent).append(new ThreadInfo(callbackThread).toString(indent));
                }
                else
                {
                    out.append(indent).append("write callback: %s [%s]\n".formatted(callbackRunner == null ? "pending" : "completed", getCallback()));
                }
            }
        }
    }
}
