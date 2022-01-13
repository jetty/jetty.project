package org.eclipse.jetty.websocket.core.internal;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.websocket.core.Frame;

public abstract class DemandingFlusher extends IteratingCallback implements DemandChain
{
    private final BiConsumer<Frame, Callback> _forwardFrame;
    private final AtomicLong _demand = new AtomicLong();
    private final AtomicReference<Throwable> _failure = new AtomicReference<>();
    private LongConsumer _nextDemand;

    private Frame _frame;
    private Callback _callback;
    private boolean _needContent = true;
    private boolean _first = true;

    public DemandingFlusher(BiConsumer<Frame, Callback> forwardFrame)
    {
        _forwardFrame = forwardFrame;
    }

    protected abstract boolean handle(Frame frame, Callback callback, boolean first);

    @Override
    public void demand(long n)
    {
        _demand.getAndUpdate(d -> Math.addExact(d, n));
        iterate();
    }

    @Override
    public void setNextDemand(LongConsumer nextDemand)
    {
        _nextDemand = nextDemand;
    }

    public void onFrame(Frame frame, Callback callback)
    {
        _frame = frame;
        _callback = new CountingCallback(callback, 1);
        succeeded();
    }

    public void needContent()
    {
        _frame = null;
        _callback = null;
        _needContent = true;
        _first = true;
    }

    public void failFlusher(Throwable t)
    {
        if (_failure.compareAndSet(null, t))
        {
            failed(t);
            iterate();
        }
    }

    public void forwardFrame(Frame frame, Callback callback)
    {
        _demand.decrementAndGet();
        _forwardFrame.accept(frame, callback);
    }

    @Override
    protected Action process() throws Throwable
    {
        // Check for failure here in case process is called with no demand.
        Throwable failure = _failure.get();
        if (failure != null)
            throw failure;

        while (_demand.get() > 0)
        {
            failure = _failure.get();
            if (failure != null)
                throw failure;

            if (_needContent)
            {
                _needContent = false;
                _nextDemand.accept(1);
                return Action.SCHEDULED;
            }

            if (_frame == null)
                throw new IllegalStateException();

            boolean first = _first;
            _first = false;
            boolean finished = handle(_frame, _callback, first);
            if (finished)
                needContent();
        }

        return Action.IDLE;
    }

    @Override
    protected void onCompleteFailure(Throwable cause)
    {
        Throwable suppressed = _failure.getAndSet(cause);
        if (suppressed != null && suppressed != cause)
            cause.addSuppressed(suppressed);

        // This is wrapped with CountingCallback so protects against double succeed/failed.
        if (_callback != null)
            _callback.failed(cause);

        _frame = null;
        _callback = null;
    }
}
