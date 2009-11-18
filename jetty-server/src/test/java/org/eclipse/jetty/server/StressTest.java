// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Queue;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class StressTest extends TestCase
{
    protected Server _server = new Server();
    protected TestHandler _handler = new TestHandler();
    protected Connector _connector;
    protected InetAddress _addr;
    protected int _port;
    protected volatile AtomicInteger[] _loops;
    protected QueuedThreadPool _threads=new QueuedThreadPool(new BlockingArrayQueue<Runnable>(4,4));
    // protected ExecutorThreadPool _threads=new ExecutorThreadPool(100,500,10000,TimeUnit.MILLISECONDS);
    protected boolean _stress;
    private AtomicInteger _handled=new AtomicInteger(0);
    private ConcurrentLinkedQueue[] _latencies= {
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>()
            };
    private Random _random=new Random();

    @Override
    protected void setUp() throws Exception
    {
        _stress= Boolean.getBoolean("STRESS");

        _threads.setMaxThreads(500);
        _server.setThreadPool(_threads);
        SelectChannelConnector c_connector=new SelectChannelConnector();
        c_connector.setAcceptors(1);
        c_connector.setAcceptQueueSize(1000);
        
        // c_connector.setPort(8080);
        
        _connector=c_connector;
        _connector.setMaxIdleTime(30000);
        
        _server.setConnectors(new Connector[]{ _connector });
        _server.setHandler(_handler);
        _server.start();
        _port=_connector.getLocalPort();
        _addr=InetAddress.getLocalHost();
        // _addr=Inet4Address.getByName("10.10.1.16");
        // System.err.println("ADDR "+_addr+":"+_port);
        
        for (Queue q:_latencies)
            q.clear();
        _handled.set(0);
    }

    @Override
    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    final static String[] __tests = 
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
    
    
    public void doPaths(int thread,String name,boolean persistent) throws Exception
    {
        if (persistent)
        {
            long start=System.currentTimeMillis();
            Socket socket= new Socket(_addr,_port);
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
            assertEquals(name,__tests.length,bodies);bodies = count(response,"HTTP/1.1 200 OK");
            
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

                Socket socket = new Socket(_addr,_port);
                socket.setSoTimeout(10000);
                socket.setSoLinger(false,0);
                
                _latencies[0].add(new Long(System.currentTimeMillis()-start));

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();


                _latencies[1].add(new Long(System.currentTimeMillis()-start));

                String response = IO.toString(socket.getInputStream());
                socket.close();
                long end=System.currentTimeMillis();

                response=response.substring(response.indexOf("\r\n\r\n")+4);

                assertTrue(uri,response.startsWith("DATA "+__tests[i]));
                long latency=end-start;

                _latencies[5].add(new Long(latency));
            }
        }
    }
    
    public void doLoops(int thread, String name, int loops,boolean persistent) throws Exception
    {
        try
        {
            for (int i=0;i<loops;i++)
            {
                _loops[thread].set(i);
                doPaths(thread,name+"-"+i,persistent);
                Thread.sleep(1+_random.nextInt(10)*_random.nextInt(10));
                Thread.sleep(10);
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

    public void doThreads(int threads,final int loops,final boolean persistent) throws Throwable
    {
        final Throwable[] throwable=new Throwable[threads];
        final Thread[] thread=new Thread[threads];

        try
        {
            for (int i=0;i<threads;i++)
            {
                final int id=i;
                final String name = "T"+i;
                thread[i]=new Thread()
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
                            throwable[id]=th;
                        }
                        finally
                        {
                        }
                    }
                };
            }

            _loops=new AtomicInteger[threads];
            for (int i=0;i<threads;i++)
            {
                _loops[i]=new AtomicInteger(0);
                thread[i].start();
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
                for (int i=0;i<threads;i++)
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
                String status = "min/ave/max/target="+min+"/"+(total/threads)+"/"+max+"/"+loops+" errors/finished/loops="+errors+"/"+finished+"/"+threads+" idle/threads="+(_threads.getIdleThreads())+"/"+_threads.getThreads();
                if (status.equals(last))
                {
                    if (same++>5)
                    {
                        System.err.println("STALLED!!!");
                        System.err.println(_server.getThreadPool().toString());
                        ((SelectChannelConnector)(_server.getConnectors()[0])).dump();
                        Thread.sleep(5000);
                        System.exit(1);
                    }
                }
                else
                    same=0;
                last=status;
                Log.info(_server.getThreadPool().toString()+" "+status);
                if ((finished+errors)==threads)
                    break;
            }

            for (int i=0;i<threads;i++)
                thread[i].join();

            for (int i=0;i<threads;i++)
                if (throwable[i]!=null)
                    throw throwable[i];
            
            for (int i=0;i<_latencies.length;i++)
                assertEquals(_handled.get(),_latencies[i].size());
        }
        finally
        {
            System.err.println();
            final int quantums=48;
            final int[][] count = new int[_latencies.length][quantums];
            final int length[] = new int[_latencies.length];
            final int other[] = new int[_latencies.length];

            for (int i=0;i<_latencies.length;i++)
            {
                Queue<Long> latencies=_latencies[i];
                length[i] = latencies.size();

                loop:
                    for (long latency:(Queue<Long>)(_latencies[i]))
                    {
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
                System.out.print(q+"00<=latency<"+(q+1)+"00");
                for (int i=0;i<_latencies.length;i++)
                    System.out.print("\t"+count[i][q]);
                System.out.println();
            }

            System.out.print("other            ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+other[i]);
            System.out.println();

            System.out.print("HANDLED          ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+_handled.get());
            System.out.println();
            System.out.print("TOTAL             ");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+length[i]);
            System.out.println();
        }
    }

    public void testNonPersistent() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS!");
            doThreads(200,100,false);
        }
        else
            doThreads(10,20,false);
    }

    public void testPersistent() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS!");
            doThreads(200,400,true);
        }
        else
            doThreads(20,40,true);
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
    
    private class TestHandler extends HandlerWrapper
    {
        private Timer _timer;
        
        public TestHandler()
        {
            _timer=new Timer();
        }
        
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
            long end=System.currentTimeMillis();
            
            _latencies[4].add(new Long(System.currentTimeMillis()-start));
            
            return;
        }
    }
    
    
}
