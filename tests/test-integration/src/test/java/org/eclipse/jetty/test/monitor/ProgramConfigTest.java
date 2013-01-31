//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test.monitor;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.MBeanServerConnection;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.monitor.JMXMonitor;
import org.eclipse.jetty.monitor.jmx.ConsoleNotifier;
import org.eclipse.jetty.monitor.jmx.EventNotifier;
import org.eclipse.jetty.monitor.jmx.EventState;
import org.eclipse.jetty.monitor.jmx.EventTrigger;
import org.eclipse.jetty.monitor.jmx.MonitorAction;
import org.eclipse.jetty.monitor.triggers.GreaterThanAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.LessThanOrEqualToAttrEventTrigger;
import org.eclipse.jetty.monitor.triggers.OrEventTrigger;
import org.eclipse.jetty.toolchain.jmx.JmxServiceConnection;
import org.eclipse.jetty.toolchain.test.JettyDistro;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class ProgramConfigTest
{
    private static final Logger LOG = Log.getLogger(ProgramConfigTest.class);

    private static JettyDistro jetty;
    
    @BeforeClass
    public static void initJetty() throws Exception
    {
        jetty = new JettyDistro(ProgramConfigTest.class);

        jetty.delete("contexts/javadoc.xml");
        
        jetty.overlayConfig("monitor");
        
        jetty.start();

        JMXMonitor.setServiceUrl(jetty.getJmxUrl());
    }

    @AfterClass
    public static void shutdownJetty() throws Exception
    {
        if (jetty != null)
        {
            jetty.stop();
        }
    }

    @Test
    public void testThreadPoolMXBean()
        throws Exception
    {
        int testRangeLow  = 4;
        int testRangeHigh = 7;
                
        LessThanOrEqualToAttrEventTrigger<Integer> trigger1 =
            new LessThanOrEqualToAttrEventTrigger<Integer>("org.eclipse.jetty.util.thread:type=queuedthreadpool,id=0", "idleThreads",
                                                testRangeLow);
        GreaterThanAttrEventTrigger<Integer> trigger2 =
            new GreaterThanAttrEventTrigger<Integer>("org.eclipse.jetty.util.thread:type=queuedthreadpool,id=0", "idleThreads",
                                                testRangeHigh);
        OrEventTrigger trigger = new OrEventTrigger(trigger1, trigger2);
        EventNotifier notifier = new ConsoleNotifier("%s");
        final AtomicLong counter = new AtomicLong();
        MonitorAction action = new MonitorAction(trigger, notifier, 500) {
                @Override
                public void execute(EventTrigger trigger, EventState<?> state, long timestamp)
                {
                    System.out.println(counter.incrementAndGet());
                }
            };
        JMXMonitor.addMonitorActions(action);

        final int threadCount = 100;
        final long requestCount = 100;
        final String requestUrl = jetty.getBaseUri().resolve("d.txt").toASCIIString();
        final CountDownLatch gate = new CountDownLatch(threadCount);
        ThreadPool worker = new ExecutorThreadPool(threadCount,threadCount,60,TimeUnit.SECONDS);
        for (int idx=0; idx < threadCount; idx++)
        {
            worker.dispatch(new Runnable() {
                        public void run()
                        {
                            runTest(requestUrl, requestCount);
                            gate.countDown();
                        }
                    });
            Thread.sleep(100);
         }
        gate.await();
        JMXMonitor.removeMonitorActions(action);
        assertTrue(true);
    }

    protected static void runTest(String requestUrl, long count)
    {
        HttpClient client = new HttpClient();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        try
        {
            client.start();
        }
        catch (Exception ex)
        {
            LOG.debug(ex);
        }

        if (client != null)
        {
            for (long cnt=0; cnt < count; cnt++)
            {
                try
                {
                    ContentExchange getExchange = new ContentExchange();
                    getExchange.setURL(requestUrl);
                    getExchange.setMethod(HttpMethods.GET);

                    client.send(getExchange);
                    getExchange.waitForDone();

                    String content = "";
                    int responseStatus = getExchange.getResponseStatus();
                    if (responseStatus == HttpStatus.OK_200)
                    {
                        content = getExchange.getResponseContent();
                    }

                    Thread.sleep(100);
                }
                catch (InterruptedException ex)
                {
                    break;
                }
                catch (IOException ex)
                {
                    LOG.debug(ex);
                }
            }

            try
            {
                client.stop();
            }
            catch (Exception ex)
            {
                LOG.debug(ex);
            }
        }
    }
}
