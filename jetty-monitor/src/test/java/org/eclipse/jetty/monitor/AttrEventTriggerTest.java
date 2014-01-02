//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.monitor;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeSet;

import javax.management.MBeanServer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.monitor.jmx.ConsoleNotifier;
import org.eclipse.jetty.monitor.jmx.EventNotifier;
import org.eclipse.jetty.monitor.jmx.EventState;
import org.eclipse.jetty.monitor.jmx.EventState.TriggerState;
import org.eclipse.jetty.monitor.jmx.EventTrigger;
import org.eclipse.jetty.monitor.jmx.MonitorAction;
import org.eclipse.jetty.monitor.triggers.AndEventTrigger;
import org.eclipse.jetty.monitor.triggers.AttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.EqualToAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.GreaterThanAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.GreaterThanOrEqualToAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.LessThanAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.LessThanOrEqualToAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.OrEventTrigger;
import org.eclipse.jetty.monitor.triggers.RangeAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.RangeInclAttrEventTrigger;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class AttrEventTriggerTest
{
    private static final Logger LOG = Log.getLogger(AttrEventTriggerTest.class);

    private Server _server;
    private TestHandler _handler;
    private RequestCounter _counter;
    private JMXMonitor _monitor;
    private HttpClient _client;
    private String _requestUrl;
    private MBeanContainer _mBeanContainer;

    @Before
    public void setUp()
        throws Exception
    {
        File docRoot = new File("target/test-output/docroot/");
        docRoot.mkdirs();
        docRoot.deleteOnExit();

        System.setProperty("org.eclipse.jetty.util.log.DEBUG","");
        _server = new Server();

        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(0);
        _server.setConnectors(new Connector[] {connector});
        
        _handler = new TestHandler();
        _server.setHandler(_handler);

        MBeanContainer.resetUnique();
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        _mBeanContainer = new MBeanContainer(mBeanServer);
        _server.addBean(_mBeanContainer,true);
        _server.addBean(Log.getLog());
        
        _counter = _handler.getRequestCounter();
        _server.addBean(_counter);

        _server.start();

        startClient();

        _monitor = new JMXMonitor();

        int port = connector.getLocalPort();
        _requestUrl = "http://localhost:"+port+ "/";
    }

    @After
    public void tearDown()
        throws Exception
    {
        stopClient();

        _mBeanContainer.destroy();
        
        if (_server != null)
        {
            _server.stop();
            _server = null;
        }
    }

    @Test
    public void testNoCondition()
        throws Exception
    {
        long requestCount = 10;

        AttrEventTrigger<Long> trigger =
            new AttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter");

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(1,requestCount);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testEqual_TRUE()
        throws Exception
    {
        long requestCount = 10;
        long testValue = 5;

        EqualToAttrEventTrigger<Long> trigger =
            new EqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",testValue);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(testValue);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testEqual_FALSE()
        throws Exception
    {
        long requestCount = 10;
        long testValue = 11;

        EqualToAttrEventTrigger<Long> trigger =
            new EqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                              testValue);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet();
        assertEquals(result, action.getHits());
   }

    @Test
    public void testLowerLimit()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow = 5;

        GreaterThanAttrEventTrigger<Long> trigger =
            new GreaterThanAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                  testRangeLow);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(6,10);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testLowerLimitIncl()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow = 5;

        GreaterThanOrEqualToAttrEventTrigger<Long> trigger =
            new GreaterThanOrEqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                  testRangeLow);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(5,10);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testUpperLimit()
        throws Exception
    {
        long requestCount = 10;
        long testRangeHigh = 5;

        LessThanAttrEventTrigger<Long> trigger =
            new LessThanAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                  testRangeHigh);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(1,4);
        assertEquals(result, action.getHits());
    }


    @Test
    public void testUpperLimitIncl()
        throws Exception
    {
        long requestCount = 10;
        long testRangeHigh = 5;

        LessThanOrEqualToAttrEventTrigger<Long> trigger =
            new LessThanOrEqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                  testRangeHigh);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(1,5);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testRangeInclusive()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow  = 3;
        long testRangeHigh = 8;

        RangeInclAttrEventTrigger<Long> trigger =
            new RangeInclAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                testRangeLow, testRangeHigh);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(testRangeLow,testRangeHigh);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testInsideRangeExclusive()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow  = 3;
        long testRangeHigh = 8;

        RangeAttrEventTrigger<Long> trigger =
            new RangeAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                            testRangeLow, testRangeHigh);

        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(testRangeLow+1,testRangeHigh-1);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testRangeComposite()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow  = 4;
        long testRangeHigh = 7;

        GreaterThanAttrEventTrigger<Long> trigger1 =
            new GreaterThanAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                testRangeLow);
        LessThanOrEqualToAttrEventTrigger<Long> trigger2 =
            new LessThanOrEqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                testRangeHigh);
        AndEventTrigger trigger = new AndEventTrigger(trigger1, trigger2);
        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(testRangeLow+1,testRangeHigh);
        assertEquals(result, action.getHits());
    }

    @Test
    public void testRangeOuter()
        throws Exception
    {
        long requestCount = 10;
        long testRangeLow  = 4;
        long testRangeHigh = 7;

        LessThanOrEqualToAttrEventTrigger<Long> trigger1 =
            new LessThanOrEqualToAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                testRangeLow);
        GreaterThanAttrEventTrigger<Long> trigger2 =
            new GreaterThanAttrEventTrigger<Long>("org.eclipse.jetty.monitor:type=requestcounter,id=0", "counter",
                                                testRangeHigh);
        OrEventTrigger trigger = new OrEventTrigger(trigger1, trigger2);
        EventNotifier notifier = new ConsoleNotifier("%s");
        CounterAction action = new CounterAction(trigger, notifier, 500, 100);

        performTest(action, requestCount, 1000);

        ResultSet result = new ResultSet(1,testRangeLow,testRangeHigh+1, requestCount);
        assertEquals(result, action.getHits());
    }

    protected void performTest(MonitorAction action, long count, long interval)
        throws Exception
    {
        _monitor.addActions(action);

        for (long cnt=0; cnt < count; cnt++)
        {
            try
            {
                //LOG.debug("Request: %s", _requestUrl);
                ContentResponse r3sponse = _client.GET(_requestUrl);           
                
                //ContentExchange getExchange = new ContentExchange();
                //getExchange.setURL(_requestUrl);
                //getExchange.setMethod(HttpMethods.GET);

                //_client.send(getExchange);
                //int state = getExchange.waitForDone();

                String content = "";
                //int responseStatus = getExchange.getResponseStatus();
                if (r3sponse.getStatus() == HttpStatus.OK_200)
                {
                    content = r3sponse.getContentAsString();
                }
                else 
                {
                    LOG.info("response status", r3sponse.getStatus());
                }

                assertEquals(HttpStatus.OK_200,r3sponse.getStatus());
                Thread.sleep(interval);
            }
            catch (InterruptedException ex)
            {
                break;
            }
        }

        Thread.sleep(interval);

        _monitor.removeActions(action);
    }

    protected void startClient()//Realm realm)
        throws Exception
    {
        _client = new HttpClient();
        //_client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        //if (realm != null){
//            _client.setRealmResolver(new SimpleRealmResolver(realm));
        //}
        _client.start();
    }

    protected void stopClient()
        throws Exception
    {
        if (_client != null)
        {
            _client.stop();
            _client = null;
        }
    }

    protected static class TestHandler
        extends AbstractHandler
    {
        private RequestCounter _counter = new RequestCounter();

        public void handle(String target, Request baseRequest,
                HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            if (baseRequest.isHandled()) {
                return;
            }
            _counter.increment();

            response.setContentType("text/plain");
            response.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = response.getWriter();
            writer.println("===TEST RESPONSE===");
            baseRequest.setHandled(true);
        }

        public RequestCounter getRequestCounter()
        {
            return _counter;
        }
    }

    protected static class ResultSet extends TreeSet<Long>
    {
        public ResultSet() {}

        public ResultSet(long value)
        {
            add(value);
        }

        public ResultSet(long start, long end)
        {
            addEntries(start, end);
        }

        public ResultSet(long start, long pause, long resume, long end)
        {
            addEntries(start, pause);
            addEntries(resume, end);
        }

        public void addEntries(long start, long stop)
        {
            if (start > 0 && stop > 0)
            {
                for(long idx=start; idx <= stop; idx++)
                {
                    add(idx);
                }
            }
        }

        public boolean equals(ResultSet set)
        {
            return (this.size() == set.size()) && containsAll(set);
        }
    }

    protected static class CounterAction
        extends MonitorAction
    {
        private ResultSet _hits = new ResultSet();

        public CounterAction(EventTrigger trigger, EventNotifier notifier, long interval, long delay)
        {
            super(trigger, notifier, interval, delay);
        }

        public void execute(EventTrigger trigger, EventState<?> state, long timestamp)
        {
            if (trigger != null && state != null)
            {
                Collection<?> values = state.values();

                Iterator<?> it = values.iterator();
                while(it.hasNext())
                {
                    TriggerState<?> entry = (TriggerState<?>)it.next();
                    Object value = entry.getValue();
                    if (value != null)
                    {
                        _hits.add((Long)value);
                    }
                }
            }
        }

        public ResultSet getHits()
        {
            return _hits;
        }
    }
}
