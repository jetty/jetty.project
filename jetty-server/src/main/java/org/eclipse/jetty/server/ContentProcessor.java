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

package org.eclipse.jetty.server;

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

    public ContentProcessor(Content.Provider provider)
    {
        super(provider);
    }

    @Override
    public Content readContent()
    {
        boolean available;
        try (AutoLock ignored = _lock.lock())
        {
            if (_reading)
                return null;

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

        try
        {
            while (true)
            {
                Content output = available ? null : produce(null);
                if (output != null)
                    return output;
                available = false;
                Content input = getProvider().readContent();
                output = produce(input);
                if (output != null)
                    return output;
                if (input == null)
                    return null;
            }
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

    private void iterate(boolean available, Runnable onContentAvailable)
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

            _available |= available;

            if (_iterating)
                return;

            if (_output == null)
            {
                available = _available;
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
                try
                {
                    onContentAvailable.run();
                    iterating = false;
                }
                finally
                {
                    try (AutoLock ignored = _lock.lock())
                    {
                        if (iterating)
                            // exception must have been thrown
                            _iterating = iterating = false;
                        else if (_onContentAvailable != null)
                        {
                            iterating = true;
                            onContentAvailable = null;
                        }
                        else
                        {
                            _iterating = iterating;
                        }
                    }
                }
            }
            else
            {
                Content input = null;
                try
                {
                    output = available ? null : produce(null);
                    if (output == null)
                    {
                        input = getProvider().readContent();
                        output = produce(input);
                        available = false;
                        if (output == null && input == null)
                            getProvider().demandContent(this::onContentAvailable);
                    }
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
                                available = true;
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

    protected abstract Content produce(Content content);
}
