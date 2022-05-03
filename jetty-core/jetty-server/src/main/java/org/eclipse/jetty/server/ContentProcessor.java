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

package org.eclipse.jetty.server;

import java.nio.channels.ReadPendingException;
import java.util.Objects;

import org.eclipse.jetty.util.thread.AutoLock;

public abstract class ContentProcessor extends Content.Processor
{
    private final AutoLock _lock = new AutoLock();
    private boolean _reading;
    private boolean _iterating;
    private boolean _available;
    private Content _output;
    private Runnable _onContentAvailable;

    public ContentProcessor(Content.Reader reader)
    {
        super(reader);
    }

    @Override
    public Content readContent()
    {
        boolean available;
        try (AutoLock ignored = _lock.lock())
        {
            if (_reading)
                throw new ReadPendingException();

            if (_output != null)
            {
                Content output = _output;
                _output = null;
                return output;
            }

            available = _available;
            _available = false;
            _reading = true;
        }

        Content input = null;
        try
        {
            while (true)
            {
                Content output = available ? null : process(null);
                if (output != null)
                    return output;
                available = false;
                input = getReader().readContent();
                output = process(input);
                if (output != null)
                    return output;
                if (input == null)
                    return null;
            }
        }
        catch (Throwable t)
        {
            return new Content.Error(t, input != null && input.isLast());
        }
        finally
        {
            try (AutoLock ignored = _lock.lock())
            {
                _reading = false;
            }
        }
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        Objects.requireNonNull(onContentAvailable);
        iterate(false, onContentAvailable);
    }

    private void iterate(boolean onContentAvailableCalled, Runnable onContentAvailable)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_reading)
                throw new IllegalStateException("read pending");

            if (onContentAvailable != null)
            {
                if (_onContentAvailable != null && onContentAvailable != _onContentAvailable)
                    throw new IllegalStateException("demand pending");
                _onContentAvailable = onContentAvailable;
                onContentAvailable = null;
            }

            _available |= onContentAvailableCalled;

            if (_iterating)
                return;

            if (_output == null)
            {
                onContentAvailableCalled = _available;
                _available = false;
            }
            else
            {
                onContentAvailable = _onContentAvailable;
                _onContentAvailable = null;
            }

            _iterating = true;
        }

        Content output = null;
        boolean iterating = true;
        while (iterating)
        {
            if (onContentAvailable != null)
            {
                Throwable error = null;
                try
                {
                    onContentAvailable.run();
                }
                catch (Throwable t)
                {
                    error = t;
                }
                finally
                {
                    onContentAvailable = null;
                    try (AutoLock ignored = _lock.lock())
                    {
                        if (error != null)
                        {
                            if (_output != null)
                                _output.release();
                            _output = new Content.Error(error, false);
                        }

                        if (_onContentAvailable == null)
                            _iterating = iterating = false;
                        else if (_output != null)
                        {
                            onContentAvailable = _onContentAvailable;
                            _onContentAvailable = null;
                        }
                    }
                }
            }
            else
            {
                Content input = null;
                try
                {
                    output = onContentAvailableCalled ? null : process(null);
                    if (output == null)
                    {
                        input = getReader().readContent();
                        output = process(input);
                        onContentAvailableCalled = false;
                        if (output == null && input == null)
                            getReader().demandContent(this::onContentAvailable);
                    }
                }
                catch (Throwable t)
                {
                    if (output != null)
                        output.release();
                    output = new Content.Error(t, input != null && input.isLast());
                    onContentAvailableCalled = false;
                }
                finally
                {
                    try (AutoLock ignored = _lock.lock())
                    {
                        _output = output;
                        if (_output == null)
                        {
                            if (_available)
                            {
                                onContentAvailableCalled = true;
                                _available = false;
                            }
                            else if (input == null)
                            {
                                iterating = false;
                            }
                        }
                        else if (_onContentAvailable == null)
                        {
                            iterating = false;
                        }
                        else
                        {
                            onContentAvailable = _onContentAvailable;
                            _onContentAvailable = null;
                        }
                        _iterating = iterating;
                    }
                }
            }
        }
    }

    private void onContentAvailable()
    {
        iterate(true, null);
    }

    protected abstract Content process(Content content);
}
