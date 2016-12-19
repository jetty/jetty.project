//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

import javax.servlet.ReadListener;

import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpInputTest
{
    private final Queue<String> _history = new LinkedBlockingQueue<>();
    private final Queue<String> _fillAndParseSimulate = new LinkedBlockingQueue<>();
    private final ReadListener _listener = new ReadListener()
    {
        @Override
        public void onError(Throwable t)
        {
            _history.add("onError:" + t);
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            _history.add("onDataAvailable");
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            _history.add("onAllDataRead");
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

    @Before
    public void before()
    {
        _in = new HttpInput(new HttpChannelState(new HttpChannel(null, new HttpConfiguration(), null, null)
        {
            @Override
            public void asyncReadFillInterested()
            {
                _history.add("asyncReadFillInterested");
            }
        })
        {
            @Override
            public void onReadUnready()
            {
                _history.add("unready");
                super.onReadUnready();
            }

            @Override
            public boolean onReadPossible()
            {
                _history.add("onReadPossible");
                return super.onReadPossible();
            }

            @Override
            public boolean onReadReady()
            {
                _history.add("ready");
                return super.onReadReady();
            }
        })
        {
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
        };
    }

    @After
    public void after()
    {
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testEmpty() throws Exception
    {
        Assert.assertThat(_in.available(), Matchers.equalTo(0));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testRead() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        Assert.assertThat(_in.available(), Matchers.equalTo(2));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));

        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(0L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(1L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(2L));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'C'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'D'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'E'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'F'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 2"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded EF"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'G'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'H'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded GH"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(8L));

        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testReRead() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        Assert.assertThat(_in.available(), Matchers.equalTo(2));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));

        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(0L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(1L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(2L));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'C'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'D'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'E'));

        _in.prependContent(new HttpInput.Content(BufferUtil.toBuffer("abcde")));

        Assert.assertThat(_in.available(), Matchers.equalTo(5));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));

        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(0L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'a'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(1L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'b'));
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(2L));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'c'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'d'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'e'));

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'F'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 2"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded EF"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'G'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'H'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded GH"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(8L));

        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testBlockingRead() throws Exception
    {
        new Thread()
        {
            public void run()
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
            }
        }.start();

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("blockForContent"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testReadEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _in.eof();

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.available(), Matchers.equalTo(2));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'C'));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'D'));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));

        Assert.assertThat(_in.read(), Matchers.equalTo(-1));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));

        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testReadEarlyEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _in.earlyEOF();

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.available(), Matchers.equalTo(2));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'C'));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'D'));

        try
        {
            _in.read();
            Assert.fail();
        }
        catch (EOFException eof)
        {
            Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));
        }

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testBlockingEOF() throws Exception
    {
        new Thread()
        {
            public void run()
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
            }
        }.start();

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.read(), Matchers.equalTo(-1));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));

        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("blockForContent"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testAsyncEmpty() throws Exception
    {
        _in.setReadListener(_listener);
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testAsyncRead() throws Exception
    {
        _in.setReadListener(_listener);
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.addContent(new TContent("AB"));
        _fillAndParseSimulate.add("CD");

        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
        _in.run();
        Assert.assertThat(_history.poll(), Matchers.equalTo("onDataAvailable"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 1"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo((int)'C'));

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'D'));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testAsyncEOF() throws Exception
    {
        _in.setReadListener(_listener);
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.eof();
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.read(), Matchers.equalTo(-1));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testAsyncReadEOF() throws Exception
    {
        _in.setReadListener(_listener);
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.addContent(new TContent("AB"));
        _fillAndParseSimulate.add("_EOF_");

        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.run();
        Assert.assertThat(_history.poll(), Matchers.equalTo("onDataAvailable"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'B'));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 1"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isFinished(), Matchers.equalTo(false));
        Assert.assertThat(_in.read(), Matchers.equalTo(-1));
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testAsyncError() throws Exception
    {
        _in.setReadListener(_listener);
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(false));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("unready"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.failed(new TimeoutException());
        Assert.assertThat(_history.poll(), Matchers.equalTo("onReadPossible"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        _in.run();
        Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));
        Assert.assertThat(_history.poll(), Matchers.equalTo("onError:java.util.concurrent.TimeoutException"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());

        Assert.assertThat(_in.isReady(), Matchers.equalTo(true));
        try
        {
            _in.read();
            Assert.fail();
        }
        catch (IOException e)
        {
            Assert.assertThat(e.getCause(), Matchers.instanceOf(TimeoutException.class));
            Assert.assertThat(_in.isFinished(), Matchers.equalTo(true));
        }

        Assert.assertThat(_history.poll(), Matchers.nullValue());
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
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));

        Assert.assertFalse(_in.consumeAll());
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(8L));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 2"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded EF"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded GH"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 0"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }

    @Test
    public void testConsumeAllEOF() throws Exception
    {
        _in.addContent(new TContent("AB"));
        _in.addContent(new TContent("CD"));
        _fillAndParseSimulate.offer("EF");
        _fillAndParseSimulate.offer("GH");
        _fillAndParseSimulate.offer("_EOF_");
        Assert.assertThat(_in.read(), Matchers.equalTo((int)'A'));

        Assert.assertTrue(_in.consumeAll());
        Assert.assertThat(_in.getContentConsumed(), Matchers.equalTo(8L));

        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded AB"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded CD"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("produceContent 3"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded EF"));
        Assert.assertThat(_history.poll(), Matchers.equalTo("Content succeeded GH"));
        Assert.assertThat(_history.poll(), Matchers.nullValue());
    }
}
