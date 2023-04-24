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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BiFunction;

import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized container for request listeners.</p>
 */
public class RequestListeners implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestListeners.class);

    private Request.QueuedListener queuedListener;
    private Request.BeginListener beginListener;
    private Request.HeadersListener headersListener;
    private Request.CommitListener commitListener;
    private Request.ContentListener contentListener;
    private Request.SuccessListener successListener;
    private Request.FailureListener failureListener;

    public boolean addListener(Request.Listener listener)
    {
        // Use binary OR to avoid short-circuit.
        return addQueuedListener(listener) |
               addBeginListener(listener) |
               addHeadersListener(listener) |
               addCommitListener(listener) |
               addContentListener(listener) |
               addSuccessListener(listener) |
               addFailureListener(listener);
    }

    public boolean removeListener(Request.Listener listener)
    {
        // Use binary OR to avoid short-circuit.
        return removeQueuedListener(listener) |
               removeBeginListener(listener) |
               removeHeadersListener(listener) |
               removeCommitListener(listener) |
               removeContentListener(listener) |
               removeSuccessListener(listener) |
               removeFailureListener(listener);
    }

    public boolean addQueuedListener(Request.QueuedListener listener)
    {
        if (listener == null)
            return false;
        Request.QueuedListener existing = queuedListener;
        queuedListener = existing == null ? listener : new QueuedListenerLink(existing, listener);
        return true;
    }

    public boolean removeQueuedListener(Request.QueuedListener listener)
    {
        if (listener == null)
            return false;
        if (queuedListener == listener)
        {
            queuedListener = null;
            return true;
        }
        if (queuedListener instanceof QueuedListenerLink link)
        {
            Request.QueuedListener remaining = link.remove(listener);
            if (remaining != null)
            {
                queuedListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyQueued(Request.QueuedListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onQueued(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addBeginListener(Request.BeginListener listener)
    {
        if (listener == null)
            return false;
        Request.BeginListener existing = beginListener;
        beginListener = existing == null ? listener : new BeginListenerLink(existing, listener);
        return true;
    }

    public boolean removeBeginListener(Request.BeginListener listener)
    {
        if (listener == null)
            return false;
        if (beginListener == listener)
        {
            beginListener = null;
            return true;
        }
        if (beginListener instanceof BeginListenerLink link)
        {
            Request.BeginListener remaining = link.remove(listener);
            if (remaining != null)
            {
                beginListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyBegin(Request.BeginListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onBegin(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addHeadersListener(Request.HeadersListener listener)
    {
        if (listener == null)
            return false;
        Request.HeadersListener existing = headersListener;
        headersListener = existing == null ? listener : new HeadersListenerLink(existing, listener);
        return true;
    }

    public boolean removeHeadersListener(Request.HeadersListener listener)
    {
        if (listener == null)
            return false;
        if (headersListener == listener)
        {
            headersListener = null;
            return true;
        }
        if (headersListener instanceof HeadersListenerLink link)
        {
            Request.HeadersListener remaining = link.remove(listener);
            if (remaining != null)
            {
                headersListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyHeaders(Request.HeadersListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addCommitListener(Request.CommitListener listener)
    {
        if (listener == null)
            return false;
        Request.CommitListener existing = commitListener;
        commitListener = existing == null ? listener : new CommitListenerLink(existing, listener);
        return true;
    }

    public boolean removeCommitListener(Request.CommitListener listener)
    {
        if (listener == null)
            return false;
        if (commitListener == listener)
        {
            commitListener = null;
            return true;
        }
        if (commitListener instanceof CommitListenerLink link)
        {
            Request.CommitListener remaining = link.remove(listener);
            if (remaining != null)
            {
                commitListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyCommit(Request.CommitListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onCommit(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addContentListener(Request.ContentListener listener)
    {
        if (listener == null)
            return false;
        Request.ContentListener existing = contentListener;
        contentListener = existing == null ? listener : new ContentListenerLink(existing, listener);
        return true;
    }

    public boolean removeContentListener(Request.ContentListener listener)
    {
        if (listener == null)
            return false;
        if (contentListener == listener)
        {
            contentListener = null;
            return true;
        }
        if (contentListener instanceof ContentListenerLink link)
        {
            Request.ContentListener remaining = link.remove(listener);
            if (remaining != null)
            {
                contentListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyContent(Request.ContentListener listener, Request request, ByteBuffer byteBuffer)
    {
        try
        {
            if (listener != null)
            {
                // The same ByteBuffer instance may be passed to multiple listeners
                // that may modify the position and limit, so clear it every time.
                byteBuffer.clear();
                listener.onContent(request, byteBuffer);
            }
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addSuccessListener(Request.SuccessListener listener)
    {
        if (listener == null)
            return false;
        Request.SuccessListener existing = successListener;
        successListener = existing == null ? listener : new SuccessListenerLink(existing, listener);
        return true;
    }

    public boolean removeSuccessListener(Request.SuccessListener listener)
    {
        if (listener == null)
            return false;
        if (successListener == listener)
        {
            successListener = null;
            return true;
        }
        if (successListener instanceof SuccessListenerLink link)
        {
            Request.SuccessListener remaining = link.remove(listener);
            if (remaining != null)
            {
                successListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifySuccess(Request.SuccessListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public boolean addFailureListener(Request.FailureListener listener)
    {
        if (listener == null)
            return false;
        Request.FailureListener existing = failureListener;
        failureListener = existing == null ? listener : new FailureListenerLink(existing, listener);
        return true;
    }

    public boolean removeFailureListener(Request.FailureListener listener)
    {
        if (listener == null)
            return false;
        if (failureListener == listener)
        {
            failureListener = null;
            return true;
        }
        if (failureListener instanceof FailureListenerLink link)
        {
            Request.FailureListener remaining = link.remove(listener);
            if (remaining != null)
            {
                failureListener = remaining;
                return true;
            }
        }
        return false;
    }

    protected static void notifyFailure(Request.FailureListener listener, Request request, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(request, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void clear()
    {
        queuedListener = null;
        beginListener = null;
        headersListener = null;
        commitListener = null;
        contentListener = null;
        successListener = null;
        failureListener = null;
    }

    protected Request.QueuedListener getQueuedListener()
    {
        return queuedListener;
    }

    protected Request.BeginListener getBeginListener()
    {
        return beginListener;
    }

    protected Request.HeadersListener getHeadersListener()
    {
        return headersListener;
    }

    protected Request.CommitListener getCommitListener()
    {
        return commitListener;
    }

    protected Request.ContentListener getContentListener()
    {
        return contentListener;
    }

    protected Request.SuccessListener getSuccessListener()
    {
        return successListener;
    }

    protected Request.FailureListener getFailureListener()
    {
        return failureListener;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new ListenerDump("queued", getQueuedListener()),
            new ListenerDump("begin", getBeginListener()),
            new ListenerDump("headers", getHeadersListener()),
            new ListenerDump("commit", getCommitListener()),
            new ListenerDump("content", getContentListener()),
            new ListenerDump("success", getSuccessListener()),
            new ListenerDump("failure", getFailureListener())
        );
    }

    private record ListenerDump(String name, Object listener)
    {
        @Override
        public String toString()
        {
            return name + " = " + listener;
        }
    }

    private static class Link<T, L extends Link<T, L>>
    {
        private final Class<L> type;
        private final BiFunction<T, T, L> ctor;
        protected final T prev;
        protected final T next;

        protected Link(Class<L> type, BiFunction<T, T, L> ctor, T prev, T next)
        {
            this.type = type;
            this.ctor = ctor;
            this.prev = prev;
            this.next = next;
        }

        @SuppressWarnings("unchecked")
        protected T remove(T listener)
        {
            // The add methods build a fold-left structure:
            // f = Link3(Link2(Link1(listener1, listener2), listener3), listener4)
            // f.remove(listener1) yields: fa = Link3a(Link2a(listener2, listener3), listener4)
            // f.remove(listener4) yields: fa = Link2(Link1(listener1, listener2), listener3)

            // First check next, to optimize the case where listeners
            // are removed in reverse order (we would not allocate).
            // If there is a match on next, return the other component.
            if (next == listener)
                return prev;

            // If it is a link, delegate the removal to it.
            if (type.isInstance(prev))
            {
                T remaining = type.cast(prev).remove(listener);
                // The prev link was modified by the removal,
                // rebuild this link with the modification.
                if (remaining != null)
                    return (T)ctor.apply(remaining, next);
                // Not found.
                return null;
            }

            // If there is a match on prev, return the other component.
            if (prev == listener)
                return next;

            // Not found.
            return null;
        }

        @Override
        public String toString()
        {
            return "%s@%x(%s,%s)".formatted(getClass().getSimpleName(), hashCode(), prev, next);
        }
    }

    private static class QueuedListenerLink extends Link<Request.QueuedListener, QueuedListenerLink> implements Request.QueuedListener
    {
        private QueuedListenerLink(Request.QueuedListener prev, Request.QueuedListener next)
        {
            super(QueuedListenerLink.class, QueuedListenerLink::new, prev, next);
        }

        @Override
        public void onQueued(Request request)
        {
            notifyQueued(prev, request);
            notifyQueued(next, request);
        }
    }

    private static class BeginListenerLink extends Link<Request.BeginListener, BeginListenerLink> implements Request.BeginListener
    {
        private BeginListenerLink(Request.BeginListener prev, Request.BeginListener next)
        {
            super(BeginListenerLink.class, BeginListenerLink::new, prev, next);
        }

        @Override
        public void onBegin(Request request)
        {
            notifyBegin(prev, request);
            notifyBegin(next, request);
        }
    }

    private static class HeadersListenerLink extends Link<Request.HeadersListener, HeadersListenerLink> implements Request.HeadersListener
    {
        private HeadersListenerLink(Request.HeadersListener prev, Request.HeadersListener next)
        {
            super(HeadersListenerLink.class, HeadersListenerLink::new, prev, next);
        }

        @Override
        public void onHeaders(Request request)
        {
            notifyHeaders(prev, request);
            notifyHeaders(next, request);
        }
    }

    private static class CommitListenerLink extends Link<Request.CommitListener, CommitListenerLink> implements Request.CommitListener
    {
        private CommitListenerLink(Request.CommitListener prev, Request.CommitListener next)
        {
            super(CommitListenerLink.class, CommitListenerLink::new, prev, next);
        }

        @Override
        public void onCommit(Request request)
        {
            notifyCommit(prev, request);
            notifyCommit(next, request);
        }
    }

    private static class ContentListenerLink extends Link<Request.ContentListener, ContentListenerLink> implements Request.ContentListener
    {
        private ContentListenerLink(Request.ContentListener prev, Request.ContentListener next)
        {
            super(ContentListenerLink.class, ContentListenerLink::new, prev, next);
        }

        @Override
        public void onContent(Request request, ByteBuffer content)
        {
            notifyContent(prev, request, content);
            notifyContent(next, request, content);
        }
    }

    private static class SuccessListenerLink extends Link<Request.SuccessListener, SuccessListenerLink> implements Request.SuccessListener
    {
        private SuccessListenerLink(Request.SuccessListener prev, Request.SuccessListener next)
        {
            super(SuccessListenerLink.class, SuccessListenerLink::new, prev, next);
        }

        @Override
        public void onSuccess(Request request)
        {
            notifySuccess(prev, request);
            notifySuccess(next, request);
        }
    }

    private static class FailureListenerLink extends Link<Request.FailureListener, FailureListenerLink> implements Request.FailureListener
    {
        private FailureListenerLink(Request.FailureListener prev, Request.FailureListener next)
        {
            super(FailureListenerLink.class, FailureListenerLink::new, prev, next);
        }

        @Override
        public void onFailure(Request request, Throwable failure)
        {
            notifyFailure(prev, request, failure);
            notifyFailure(next, request, failure);
        }
    }
}
