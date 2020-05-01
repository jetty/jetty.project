//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.EOFException;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import javax.servlet.ReadListener;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpInputTest
{
    private final Queue<String> _history = new LinkedBlockingQueue<>();
    private final Queue<String> _fillAndParseSimulate = new LinkedBlockingQueue<>();
    private final ReadListener _listener = new ReadListener()
    {
        @Override
        public void onError(Throwable t)
        {
            _history.add("l.onError:" + t);
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            _history.add("l.onDataAvailable");
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            _history.add("l.onAllDataRead");
        }
    };
    private HttpInput _in;

    public class TContent extends HttpInput.Content
    {
        private final String _content;

        public TContent(String content)
        {
            super(BufferUtil.toBuffer(content));
            _content = content;
        }

        @Override
        public void succeeded()
        {
            _history.add("Content succeeded " + _content);
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _history.add("Content failed " + _content);
            super.failed(x);
        }
    }
    
    public class TestHttpInput extends HttpInput
    {
        public TestHttpInput(HttpChannelState state)
        {
            super(state);
        }

        @Override
        protected void produceContent() throws IOException
        {
            _history.add("produceContent " + _fillAndParseSimulate.size());

            for (String s = _fillAndParseSimulate.poll(); s != null; s = _fillAndParseSimulate.poll())
            {
                if ("_EOF_".equals(s))
                    _in.eof();
                else
                    _in.addContent(new TContent(s));
            }
        }

        @Override
        protected void blockForContent() throws IOException
        {
            _history.add("blockForContent");
            super.blockForContent();
        }
    }

    public class TestHttpChannelState extends HttpChannelState
    {
        private boolean _fakeAsyncState;

        public TestHttpChannelState(HttpChannel channel)
        {
            super(channel);
        }

        public boolean isFakeAsyncState()
        {
            return _fakeAsyncState;
        }

        public void setFakeAsyncState(boolean fakeAsyncState)
        {
            _fakeAsyncState = fakeAsyncState;
        }

        @Override
        public boolean isAsyncStarted()
        {
            if (isFakeAsyncState())
                return true;
            return super.isAsyncStarted();
        }

        @Override
        public void onReadUnready()
        {
            _history.add("s.onReadUnready");
            super.onReadUnready();
        }

        @Override
        public boolean onReadPossible()
        {
            _history.add("s.onReadPossible");
            return super.onReadPossible();
        }

        @Override
        public boolean onContentAdded()
        {
            _history.add("s.onDataAvailable");
            return super.onContentAdded();
        }

        @Override
        public boolean onReadReady()
        {
            _history.add("s.onReadReady");
            return super.onReadReady();
        }
    }

    @BeforeEach
    public void before()
    {
        _in = new TestHttpInput(new TestHttpChannelState(new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public void onAsyncWaitForContent()
            {
                _history.add("asyncReadInterested");
            }
        })
      );
    }

    @AfterEach
    public void after()
    {
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testEmpty() throws Exception
    {
        assertThat(_in.available(), equalTo(0));
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.isReady(), equalTo(true));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testRead() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        assertThat(_in.available(), equalTo(2));
        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.isReady(), equalTo(true));

        assertThat(_in.getContentConsumed(), equalTo(0L));
        assertThat(_in.read(), equalTo((int)'A'));
        assertThat(_in.getContentConsumed(), equalTo(1L));
        assertThat(_in.read(), equalTo((int)'B'));
        assertThat(_in.getContentConsumed(), equalTo(2L));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'C'));
        assertThat(_in.read(), equalTo((int)'D'));

        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'E'));
        assertThat(_in.read(), equalTo((int)'F'));

        assertThat(_history.poll(), equalTo("produceContent 2"));
        assertThat(_history.poll(), equalTo("Content succeeded EF"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'G'));
        assertThat(_in.read(), equalTo((int)'H'));

        assertThat(_history.poll(), equalTo("Content succeeded GH"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.getContentConsumed(), equalTo(8L));

        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testBlockingRead() throws Exception
    {
        new Thread(() ->
        {
            try
            {
                Thread.sleep(500);
                _in.addContent(new TContent("AB"));
            }
            catch (Throwable th)
            {
                th.printStackTrace();
            }
        }).start();

        assertThat(_in.read(), equalTo((int)'A'));

        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("blockForContent"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'B'));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testReadEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _in.eof();

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.available(), equalTo(2));
        assertThat(_in.isFinished(), equalTo(false));

        assertThat(_in.read(), equalTo((int)'A'));
        assertThat(_in.read(), equalTo((int)'B'));
        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'C'));
        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.read(), equalTo((int)'D'));
        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), nullValue());
        assertThat(_in.isFinished(), equalTo(false));

        assertThat(_in.read(), equalTo(-1));
        assertThat(_in.isFinished(), equalTo(true));

        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testReadEarlyEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _in.earlyEOF();

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.available(), equalTo(2));
        assertThat(_in.isFinished(), equalTo(false));

        assertThat(_in.read(), equalTo((int)'A'));
        assertThat(_in.read(), equalTo((int)'B'));

        assertThat(_in.read(), equalTo((int)'C'));
        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.read(), equalTo((int)'D'));

        assertThrows(EOFException.class, () -> _in.read());
        assertTrue(_in.isFinished());

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testBlockingEOF() throws Exception
    {
        new Thread(() ->
        {
            try
            {
                Thread.sleep(500);
                _in.eof();
            }
            catch (Throwable th)
            {
                th.printStackTrace();
            }
        }).start();

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.read(), equalTo(-1));
        assertThat(_in.isFinished(), equalTo(true));

        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("blockForContent"));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testAsyncEmpty() throws Exception
    {
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testAsyncRead() throws Exception
    {
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);

        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), nullValue());

        _in.addContent(new TContent("AB"));
        _fillAndParseSimulate.add("CD");

        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());
        _in.run();
        assertThat(_history.poll(), equalTo("l.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.read(), equalTo((int)'A'));

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.read(), equalTo((int)'B'));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_history.poll(), equalTo("produceContent 1"));
        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo((int)'C'));

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.read(), equalTo((int)'D'));
        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testAsyncEOF() throws Exception
    {
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());

        _in.eof();
        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.read(), equalTo(-1));
        assertThat(_in.isFinished(), equalTo(true));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testAsyncReadEOF() throws Exception
    {        
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), nullValue());

        _in.addContent(new TContent("AB"));
        _fillAndParseSimulate.add("_EOF_");

        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        _in.run();
        assertThat(_history.poll(), equalTo("l.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.read(), equalTo((int)'A'));

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_in.read(), equalTo((int)'B'));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.isReady(), equalTo(true));
        assertThat(_history.poll(), equalTo("produceContent 1"));
        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isFinished(), equalTo(false));
        assertThat(_in.read(), equalTo(-1));
        assertThat(_in.isFinished(), equalTo(true));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(true));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testAsyncError() throws Exception
    {
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), equalTo("s.onReadUnready"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(false));
        assertThat(_history.poll(), nullValue());

        _in.failed(new TimeoutException());
        assertThat(_history.poll(), equalTo("s.onDataAvailable"));
        assertThat(_history.poll(), nullValue());

        _in.run();
        assertThat(_in.isFinished(), equalTo(true));
        assertThat(_history.poll(), equalTo("l.onError:java.util.concurrent.TimeoutException"));
        assertThat(_history.poll(), nullValue());

        assertThat(_in.isReady(), equalTo(true));

        IOException e = assertThrows(IOException.class, () -> _in.read());
        assertThat(e.getCause(), instanceOf(TimeoutException.class));
        assertThat(_in.isFinished(), equalTo(true));

        assertThat(_history.poll(), nullValue());
    }
    
    @Test
    public void testSetListenerWithNull() throws Exception
    {
        //test can't be null
        assertThrows(NullPointerException.class, () ->
        {
            _in.setReadListener(null);
        });
    }
    
    @Test
    public void testSetListenerNotAsync() throws Exception
    {
        //test not async
        assertThrows(IllegalStateException.class, () ->
        {
            _in.setReadListener(_listener);
        });
    }
    
    @Test
    public void testSetListenerAlreadySet() throws Exception
    {
        //set up a listener
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(true);
        _in.setReadListener(_listener);
        //throw away any events generated by setting the listener
        _history.clear();
        ((TestHttpChannelState)_in.getHttpChannelState()).setFakeAsyncState(false);
        //now test that you can't set another listener
        assertThrows(IllegalStateException.class, () ->
        {
            _in.setReadListener(_listener);
        });
    }

    @Test
    public void testRecycle() throws Exception
    {
        testAsyncRead();
        _in.recycle();
        testAsyncRead();
        _in.recycle();
        testReadEOF();
    }

    @Test
    public void testConsumeAll() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        assertThat(_in.read(), equalTo((int)'A'));

        assertFalse(_in.consumeAll());
        assertThat(_in.getContentConsumed(), equalTo(8L));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), equalTo("produceContent 2"));
        assertThat(_history.poll(), equalTo("Content succeeded EF"));
        assertThat(_history.poll(), equalTo("Content succeeded GH"));
        assertThat(_history.poll(), equalTo("produceContent 0"));
        assertThat(_history.poll(), nullValue());
    }

    @Test
    public void testConsumeAllEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        _fillAndParseSimulate.offer("_EOF_");
        assertThat(_in.read(), equalTo((int)'A'));

        assertTrue(_in.consumeAll());
        assertThat(_in.getContentConsumed(), equalTo(8L));

        assertThat(_history.poll(), equalTo("Content succeeded AB"));
        assertThat(_history.poll(), equalTo("Content succeeded CD"));
        assertThat(_history.poll(), equalTo("produceContent 3"));
        assertThat(_history.poll(), equalTo("Content succeeded EF"));
        assertThat(_history.poll(), equalTo("Content succeeded GH"));
        assertThat(_history.poll(), nullValue());
    }
}
