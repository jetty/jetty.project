//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.ReadListener;

import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.server.HttpInput.EARLY_EOF_CONTENT;
import static org.eclipse.jetty.server.HttpInput.EOF_CONTENT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * this tests HttpInput and its interaction with HttpChannelState
 */

public class HttpInputAsyncStateTest
{

    private static final Queue<String> __history = new LinkedBlockingQueue<>();
    private ByteBuffer _expected = BufferUtil.allocate(16 * 1024);
    private boolean _eof;
    private boolean _noReadInDataAvailable;
    private boolean _completeInOnDataAvailable;

    private final ReadListener _listener = new ReadListener()
    {
        @Override
        public void onError(Throwable t)
        {
            __history.add("onError:" + t);
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            __history.add("onDataAvailable");
            if (!_noReadInDataAvailable && readAvailable() && _completeInOnDataAvailable)
            {
                __history.add("complete");
                _state.complete();
            }
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            __history.add("onAllDataRead");
        }
    };
    private HttpInput _in;
    HttpChannelState _state;

    public static class TContent extends HttpInput.Content
    {
        public TContent(String content)
        {
            super(BufferUtil.toBuffer(content));
        }
    }

    @BeforeEach
    public void before()
    {
        _noReadInDataAvailable = false;
        _in = new HttpInput(new HttpChannelState(new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public void onAsyncWaitForContent()
            {
                __history.add("onAsyncWaitForContent");
            }

            @Override
            public Scheduler getScheduler()
            {
                return null;
            }
        })
        {
            @Override
            public void onReadUnready()
            {
                super.onReadUnready();
                __history.add("onReadUnready");
            }

            @Override
            public boolean onContentAdded()
            {
                boolean wake = super.onContentAdded();
                __history.add("onReadPossible " + wake);
                return wake;
            }

            @Override
            public boolean onReadReady()
            {
                boolean wake = super.onReadReady();
                __history.add("onReadReady " + wake);
                return wake;
            }
        })
        {
            @Override
            public void wake()
            {
                __history.add("wake");
            }
        };

        _state = _in.getHttpChannelState();
        __history.clear();
    }

    private void check(String... history)
    {
        if (history == null || history.length == 0)
            assertThat(__history, empty());
        else
            assertThat(__history.toArray(new String[__history.size()]), Matchers.arrayContaining(history));
        __history.clear();
    }

    private void wake()
    {
        handle(null);
    }

    private void handle()
    {
        handle(null);
    }

    private void handle(Runnable run)
    {
        Action action = _state.handling();
        loop:
        while (true)
        {
            switch (action)
            {
                case DISPATCH:
                    if (run == null)
                        fail("Run is null during DISPATCH");
                    run.run();
                    break;

                case READ_CALLBACK:
                    _in.run();
                    break;

                case TERMINATED:
                case WAIT:
                    break loop;

                case COMPLETE:
                    __history.add("COMPLETE");
                    break;

                case READ_REGISTER:
                    _state.getHttpChannel().onAsyncWaitForContent();
                    break;

                default:
                    fail("Bad Action: " + action);
            }
            action = _state.unhandle();
        }
    }

    private void deliver(Content... content)
    {
        if (content != null)
        {
            for (Content c : content)
            {
                if (c == EOF_CONTENT)
                {
                    _in.eof();
                    _eof = true;
                }
                else if (c == HttpInput.EARLY_EOF_CONTENT)
                {
                    _in.earlyEOF();
                    _eof = true;
                }
                else
                {
                    _in.addContent(c);
                    BufferUtil.append(_expected, c.getByteBuffer().slice());
                }
            }
        }
    }

    boolean readAvailable() throws IOException
    {
        int len = 0;
        try
        {
            while (_in.isReady())
            {
                int b = _in.read();

                if (b < 0)
                {
                    if (len > 0)
                        __history.add("read " + len);
                    __history.add("read -1");
                    assertTrue(BufferUtil.isEmpty(_expected));
                    assertTrue(_eof);
                    return true;
                }
                else
                {
                    len++;
                    assertFalse(BufferUtil.isEmpty(_expected));
                    int a = 0xff & _expected.get();
                    assertThat(b, equalTo(a));
                }
            }
            __history.add("read " + len);
            assertTrue(BufferUtil.isEmpty(_expected));
        }
        catch (IOException e)
        {
            if (len > 0)
                __history.add("read " + len);
            __history.add("read " + e);
            throw e;
        }
        return false;
    }

    @AfterEach
    public void after()
    {
        assertThat(__history.poll(), Matchers.nullValue());
    }

    @Test
    public void testInitialEmptyListenInHandle() throws Exception
    {
        deliver(EOF_CONTENT);
        check();

        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");
        });

        check("onAllDataRead");
    }

    @Test
    public void testInitialEmptyListenAfterHandle() throws Exception
    {
        deliver(EOF_CONTENT);

        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onAllDataRead");
    }

    @Test
    public void testListenInHandleEmpty() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(EOF_CONTENT);
        check("onReadPossible true");
        handle();
        check("onAllDataRead");
    }

    @Test
    public void testEmptyListenAfterHandle() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        deliver(EOF_CONTENT);
        check();

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onAllDataRead");
    }

    @Test
    public void testListenAfterHandleEmpty() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onAsyncWaitForContent", "onReadUnready");

        deliver(EOF_CONTENT);
        check("onReadPossible true");

        handle();
        check("onAllDataRead");
    }

    @Test
    public void testInitialEarlyEOFListenInHandle() throws Exception
    {
        deliver(EARLY_EOF_CONTENT);
        check();

        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");
        });

        check("onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testInitialEarlyEOFListenAfterHandle() throws Exception
    {
        deliver(EARLY_EOF_CONTENT);

        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testListenInHandleEarlyEOF() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(EARLY_EOF_CONTENT);
        check("onReadPossible true");
        handle();
        check("onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testEarlyEOFListenAfterHandle() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        deliver(EARLY_EOF_CONTENT);
        check();

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testListenAfterHandleEarlyEOF() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onAsyncWaitForContent", "onReadUnready");

        deliver(EARLY_EOF_CONTENT);
        check("onReadPossible true");

        handle();
        check("onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testInitialAllContentListenInHandle() throws Exception
    {
        deliver(new TContent("Hello"), EOF_CONTENT);
        check();

        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");
        });

        check("onDataAvailable", "read 5", "read -1", "onAllDataRead");
    }

    @Test
    public void testInitialAllContentListenAfterHandle() throws Exception
    {
        deliver(new TContent("Hello"), EOF_CONTENT);

        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onDataAvailable", "read 5", "read -1", "onAllDataRead");
    }

    @Test
    public void testListenInHandleAllContent() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(new TContent("Hello"), EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");
        handle();
        check("onDataAvailable", "read 5", "read -1", "onAllDataRead");
    }

    @Test
    public void testAllContentListenAfterHandle() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        deliver(new TContent("Hello"), EOF_CONTENT);
        check();

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check("onDataAvailable", "read 5", "read -1", "onAllDataRead");
    }

    @Test
    public void testListenAfterHandleAllContent() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onAsyncWaitForContent", "onReadUnready");

        deliver(new TContent("Hello"), EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");

        handle();
        check("onDataAvailable", "read 5", "read -1", "onAllDataRead");
    }

    @Test
    public void testInitialIncompleteContentListenInHandle() throws Exception
    {
        deliver(new TContent("Hello"), EARLY_EOF_CONTENT);
        check();

        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadReady false");
        });

        check(
            "onDataAvailable",
            "read 5",
            "read org.eclipse.jetty.io.EofException: Early EOF",
            "onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testInitialPartialContentListenAfterHandle() throws Exception
    {
        deliver(new TContent("Hello"), EARLY_EOF_CONTENT);

        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check(
            "onDataAvailable",
            "read 5",
            "read org.eclipse.jetty.io.EofException: Early EOF",
            "onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testListenInHandlePartialContent() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(new TContent("Hello"), EARLY_EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");
        handle();
        check(
            "onDataAvailable",
            "read 5",
            "read org.eclipse.jetty.io.EofException: Early EOF",
            "onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testPartialContentListenAfterHandle() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        deliver(new TContent("Hello"), EARLY_EOF_CONTENT);
        check();

        _in.setReadListener(_listener);
        check("onReadReady true", "wake");
        wake();
        check(
            "onDataAvailable",
            "read 5",
            "read org.eclipse.jetty.io.EofException: Early EOF",
            "onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testListenAfterHandlePartialContent() throws Exception
    {
        handle(() ->
        {
            _state.startAsync(null);
            check();
        });

        _in.setReadListener(_listener);
        check("onAsyncWaitForContent", "onReadUnready");

        deliver(new TContent("Hello"), EARLY_EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");

        handle();
        check(
            "onDataAvailable",
            "read 5",
            "read org.eclipse.jetty.io.EofException: Early EOF",
            "onError:org.eclipse.jetty.io.EofException: Early EOF");
    }

    @Test
    public void testReadAfterOnDataAvailable() throws Exception
    {
        _noReadInDataAvailable = true;
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(new TContent("Hello"), EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");

        handle();
        check("onDataAvailable");

        readAvailable();
        check("wake", "read 5", "read -1");
        wake();
        check("onAllDataRead");
    }

    @Test
    public void testReadOnlyExpectedAfterOnDataAvailable() throws Exception
    {
        _noReadInDataAvailable = true;
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(new TContent("Hello"), EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");

        handle();
        check("onDataAvailable");

        byte[] buffer = new byte[_expected.remaining()];
        assertThat(_in.read(buffer), equalTo(buffer.length));
        assertThat(new String(buffer), equalTo(BufferUtil.toString(_expected)));
        BufferUtil.clear(_expected);
        check();

        assertTrue(_in.isReady());
        check();

        assertThat(_in.read(), equalTo(-1));
        check("wake");

        wake();
        check("onAllDataRead");
    }

    @Test
    public void testReadAndCompleteInOnDataAvailable() throws Exception
    {
        _completeInOnDataAvailable = true;
        handle(() ->
        {
            _state.startAsync(null);
            _in.setReadListener(_listener);
            check("onReadUnready");
        });

        check("onAsyncWaitForContent");

        deliver(new TContent("Hello"), EOF_CONTENT);
        check("onReadPossible true", "onReadPossible false");

        handle(() ->
        {
            __history.add(_state.getState().toString());
        });
        System.err.println(__history);
        check(
            "onDataAvailable",
            "read 5",
            "read -1",
            "complete",
            "COMPLETE"
        );
    }
}
