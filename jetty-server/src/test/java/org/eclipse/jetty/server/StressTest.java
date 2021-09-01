//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.net.Socket;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AdvancedRunner.class)
@Ignore
public class StressTest
{
    private static final Logger LOG = Log.getLogger(StressTest.class);

    private static QueuedThreadPool _threads;
    private static Server _server;
    private static ServerConnector _connector;
    private static final AtomicInteger _handled=new AtomicInteger(0);
    private static final ConcurrentLinkedQueue[] _latencies= {
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>()
            };

    private volatile AtomicInteger[] _loops;
    private final Random _random=new Random();
    private static final String[] __tests =
    {
        "/path/0",
        "/path/1",
        "/path/2",
        "/path/3",
        "/path/4",
        "/path/5",
        "/path/6",
        "/path/7",
        "/path/8",
        "/path/9",
        "/path/a",
        "/path/b",
        "/path/c",
        "/path/d",
        "/path/e",
        "/path/f",
    };

    @BeforeClass
    public static void init() throws Exception
    {
        _threads = new QueuedThreadPool();
        _threads.setMaxThreads(200);

        _server = new Server(_threads);
        _server.manage(_threads);
        _connector = new ServerConnector(_server,null,null,null,1, 1,new HttpConnectionFactory());
        _connector.setAcceptQueueSize(5000);
        _connector.setIdleTimeout(30000);
        _server.addConnector(_connector);

        TestHandler _handler = new TestHandler();
        _server.setHandler(_handler);

        _server.start();
    }

    @AfterClass
    public static void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Before
    public void reset()
    {
        _handled.set(0);
        for (Queue q : _latencies)
            q.clear();
    }

    @Test
    public void testMinNonPersistent() throws Throwable
    {
        assumeTrue(!OS.IS_OSX);
        doThreads(10,10,false);
    }

    @Test
    @Stress("Hey, its called StressTest for a reason")
    public void testNonPersistent() throws Throwable
    {
        // TODO needs to be further investigated
        assumeTrue(!OS.IS_OSX);

        doThreads(20,20,false);
        Thread.sleep(1000);
        doThreads(200,10,false);
        Thread.sleep(1000);
        doThreads(200,200,false);
    }

    @Test
    public void testMinPersistent() throws Throwable
    {
        // TODO needs to be further investigated
        assumeTrue(!OS.IS_OSX);
        doThreads(10,10,true);
    }
    
    @Test
    @Stress("Hey, its called StressTest for a reason")
    public void testPersistent() throws Throwable
    {
        // TODO needs to be further investigated
        assumeTrue(!OS.IS_OSX);
        doThreads(40,40,true);
        Thread.sleep(1000);
        doThreads(200,10,true);
        Thread.sleep(1000);
        doThreads(200,200,true);
    }

    private void doThreads(int threadCount, final int loops, final boolean persistent) throws Throwable
    {
        final Throwable[] throwables = new Throwable[threadCount];
        final Thread[] threads = new Thread[threadCount];

        try
        {
            for (int i=0;i< threadCount;i++)
            {
                final int id=i;
                final String name = "T"+i;
                Thread.sleep(_random.nextInt(100));
                threads[i]=new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            doLoops(id,name,loops,persistent);
                        }
                        catch(Throwable th)
                        {
                            th.printStackTrace();
                            throwables[id]=th;
                        }
                    }
                };
            }

            _loops=new AtomicInteger[threadCount];
            for (int i=0;i< threadCount;i++)
            {
                _loops[i]=new AtomicInteger(0);
                threads[i].start();
            }

            String last=null;
            int same=0;

            while(true)
            {
                Thread.sleep(1000L);
                int finished=0;
                int errors=0;
                int min=loops;
                int max=0;
                int total=0;
                for (int i=0;i< threadCount;i++)
                {
                    int l=_loops[i].get();
                    if (l<0)
                    {
                        errors++;
                        total-=l;
                    }
                    else
                    {
                        if (l<min)
                            min=l;
                        if (l>max)
                            max=l;
                        total+=l;
                        if (l==loops)
                            finished++;
                    }
                }
                String status = "min/ave/max/target="+min+"/"+(total/ threadCount)+"/"+max+"/"+loops+" errors/finished/loops="+errors+"/"+finished+"/"+ threadCount +" idle/threads="+(_threads.getIdleThreads())+"/"+_threads.getThreads();
                if (status.equals(last))
                {
                    if (same++>5)
                    {
                        System.err.println("STALLED!!!");
                        System.err.println(_server.getThreadPool().toString());
                        Thread.sleep(5000);
                        System.exit(1);
                    }
                }
                else
                    same=0;
                last=status;
                LOG.info(_server.getThreadPool().toString()+" "+status);
                if ((finished+errors)== threadCount)
                    break;
            }

            for (Thread thread : threads)
                thread.join();

            for (Throwable throwable : throwables)
                if (throwable!=null)
                    throw throwable;

            for (ConcurrentLinkedQueue _latency : _latencies)
                assertEquals(_handled.get(), _latency.size());
        }
        finally
        {
            // System.err.println();
            final int quantums=48;
            final int[][] count = new int[_latencies.length][quantums];
            final int length[] = new int[_latencies.length];
            final int other[] = new int[_latencies.length];

            long total=0;

            for (int i=0;i<_latencies.length;i++)
            {
                Queue<Long> latencies=_latencies[i];
                length[i] = latencies.size();

                loop:
                for (long latency : latencies)
                {
                    if (i==4)
                        total+=latency;
                    for (int q=0;q<quantums;q++)
                    {
                        if (latency>=(q*100) && latency<((q+1)*100))
                        {
                            count[i][q]++;
                            continue loop;
                        }
                    }
                    other[i]++;

                }
            }

            System.out.println("           stage:\tbind\twrite\trecv\tdispatch\twrote\ttotal");
            for (int q=0;q<quantums;q++)
            {
                System.out.printf("%02d00<=l<%02d00",q,(q+1));
                for (int i=0;i<_latencies.length;i++)
                    System.out.print("\t"+count[i][q]);
                System.out.println();
            }

            System.out.print("other       ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+other[i]);
            System.out.println();

            System.out.print("HANDLED     ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+_handled.get());
            System.out.println();
            System.out.print("TOTAL       ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+length[i]);
            System.out.println();
            long ave=total/_latencies[4].size();
            System.out.println("ave="+ave);
        }
    }

    private void doLoops(int thread, String name, int loops,boolean persistent) throws Exception
    {
        try
        {
            for (int i=0;i<loops;i++)
            {
                _loops[thread].set(i);
                doPaths(thread,name+"-"+i,persistent);
                Thread.sleep(1+_random.nextInt(20)*_random.nextInt(20));
                Thread.sleep(20);
            }
            _loops[thread].set(loops);
        }
        catch(Exception e)
        {
            System.err.println(e);
            _loops[thread].set(-_loops[thread].get());
            throw e;
        }
    }

    private void doPaths(int thread,String name,boolean persistent) throws Exception
    {
        if (persistent)
        {
            long start=System.currentTimeMillis();
            Socket socket= new Socket("localhost", _connector.getLocalPort());
            socket.setSoTimeout(30000);
            socket.setSoLinger(false,0);

            long connected=System.currentTimeMillis();

            for (int i=0;i<__tests.length;i++)
            {
                String uri=__tests[i]+"/"+name+"/"+i;

                String close=((i+1)<__tests.length)?"":"Connection: close\r\n";
                String request =
                        "GET "+uri+" HTTP/1.1\r\n"+
                                "Host: localhost\r\n"+
                                "start: "+start+"\r\n"+
                                close+"\r\n";

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();
                Thread.yield();
            }

            long written=System.currentTimeMillis();

            String response = IO.toString(socket.getInputStream());
            socket.close();

            long end=System.currentTimeMillis();

            int bodies = count(response,"HTTP/1.1 200 OK");
            if (__tests.length!=bodies)
                System.err.println("responses=\n"+response+"\n---");
            assertEquals(name,__tests.length,bodies);

            long bind=connected-start;
            long flush=(written-connected)/__tests.length;
            long read=(end-written)/__tests.length;

            int offset=0;
            for (int i=0;i<__tests.length;i++)
            {
                offset=response.indexOf("DATA "+__tests[i],offset);
                assertTrue(offset>=0);
                offset+=__tests[i].length()+5;

                if (bind<0 || flush<0 || read <0)
                {
                    System.err.println(bind+","+flush+","+read);
                }

                _latencies[0].add((i==0)?new Long(bind):0);
                _latencies[1].add((i==0)?new Long(bind+flush):flush);
                _latencies[5].add((i==0)?new Long(bind+flush+read):(flush+read));
            }
        }
        else
        {
            for (int i=0;i<__tests.length;i++)
            {
                String uri=__tests[i]+"/"+name+"/"+i;

                long start=System.currentTimeMillis();
                String close="Connection: close\r\n";
                String request =
                        "GET "+uri+" HTTP/1.1\r\n"+
                                "Host: localhost\r\n"+
                                "start: "+start+"\r\n"+
                                close+"\r\n";

                Socket socket = new Socket("localhost", _connector.getLocalPort());
                socket.setSoTimeout(10000);
                socket.setSoLinger(false,0);

                _latencies[0].add(new Long(System.currentTimeMillis()-start));

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();

                _latencies[1].add(new Long(System.currentTimeMillis()-start));

                String response = IO.toString(socket.getInputStream());
                socket.close();
                long end=System.currentTimeMillis();

                String endOfResponse = "\r\n\r\n";
                assertTrue("response = '" + response + "'", response.contains(endOfResponse));
                response=response.substring(response.indexOf(endOfResponse) + endOfResponse.length());

                assertTrue(uri,response.startsWith("DATA "+__tests[i]));
                long latency=end-start;

                _latencies[5].add(new Long(latency));
            }
        }
    }

    private int count(String s,String sub)
    {
        int count=0;
        int index=s.indexOf(sub);

        while(index>=0)
        {
            count++;
            index=s.indexOf(sub,index+sub.length());
        }
        return count;
    }

    private static class TestHandler extends HandlerWrapper
    {
        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            long now=System.currentTimeMillis();
            long start=Long.parseLong(baseRequest.getHeader("start"));
            long received=baseRequest.getTimeStamp();

            _handled.incrementAndGet();
            long delay=received-start;
            if (delay<0)
                delay=0;
            _latencies[2].add(new Long(delay));
            _latencies[3].add(new Long(now-start));

            response.setStatus(200);
            response.getOutputStream().print("DATA "+request.getPathInfo()+"\n\n");
            baseRequest.setHandled(true);

            _latencies[4].add(new Long(System.currentTimeMillis()-start));

            return;
        }
    }
}
