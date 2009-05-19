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
import java.util.Timer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class StressTest extends TestCase
{
    protected Server _server = new Server();
    protected SuspendHandler _handler = new SuspendHandler();
    protected Connector _connector;
    protected InetAddress _addr;
    protected int _port;
    protected int[] _loops;
    protected QueuedThreadPool _threads=new QueuedThreadPool();
    protected boolean _stress;
    private ConcurrentLinkedQueue[] _latencies= {
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>(),
            new ConcurrentLinkedQueue<Long>()
            };

    protected void setUp() throws Exception
    {
        _stress= Boolean.getBoolean("STRESS");
        _threads.setMaxThreads(500);
        _server.setThreadPool(_threads);
        SelectChannelConnector c_connector=new SelectChannelConnector();
        SocketConnector s_connector=new SocketConnector();
        
        _connector=s_connector;
        _connector.setMaxIdleTime(30000);
        
        _server.setConnectors(new Connector[]{ _connector });
        _server.setHandler(_handler);
        _server.start();
        _port=_connector.getLocalPort();
        _addr=Inet4Address.getLocalHost();
        
        for (Queue q:_latencies)
            q.clear();
    }

    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    final static String[][] __tests = 
    {
        {"/path/0","NORMAL"},
        {"/path/1","NORMAL"},
        {"/path/2","NORMAL"},
        {"/path/3","NORMAL"},
        {"/path/4","NORMAL"},
        {"/path/5","NORMAL"},
        {"/path/6","NORMAL"},
        {"/path/7","NORMAL"},
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
                //int timeout = __tests[i][1].equals("NORMAL")?0:(_random.nextInt(200)+1);
                int timeout = __tests[i][1].equals("NORMAL")?0:20;
                String uri=__tests[i][0];

                String close=((i+1)<__tests.length)?"":"Connection: close\r\n";
                String request = "GET "+uri+" HTTP/1.1\r\nHost: localhost\r\nstart: "+start+"\r\n"+close+"\r\n";

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();
            }

            long written=System.currentTimeMillis();

            String response = IO.toString(socket.getInputStream());
            socket.close();

            long end=System.currentTimeMillis();
            
            long bind=connected-start; 
            long flush=(written-connected)/__tests.length;   
            long read=(end-written)/__tests.length;
            
            int offset=0;
            for (int i=0;i<__tests.length;i++)
            {
                offset=response.indexOf(__tests[i][1],offset);
                assertTrue(offset>=0);
                offset+=__tests[i][1].length();
                
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
                //int timeout = __tests[i][1].equals("NORMAL")?0:(_random.nextInt(200)+1);
                int timeout = __tests[i][1].equals("NORMAL")?0:20;
                String uri=__tests[i][0];

                long start=System.currentTimeMillis();
                String close="Connection: close\r\n";
                String request = "GET "+uri+" HTTP/1.1\r\nHost: localhost\r\nstart: "+start+"\r\n"+close+"\r\n";

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

                String test=name+"-"+i+" "+uri+" "+__tests[i][1];
                assertEquals(test,__tests[i][1],response);
                long duration=end-start;
                assertTrue(test+" "+duration,duration+50>=timeout);

                long latency=duration-timeout;

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
                _loops[thread]=i;
                doPaths(thread,name+"-"+i,persistent);
                // Thread.sleep(1+_random.nextInt(10)*_random.nextInt(10));
                Thread.sleep(10);
            }
            _loops[thread]=loops;
        }
        catch(Exception e)
        {
            System.err.println(e);
            //_connector.dump();
            _loops[thread]=-_loops[thread];
            throw e;
        }
    }
    
    public void doThreads(int threads,final int loops,final boolean persistent) throws Throwable
    {
        final Throwable[] throwable=new Throwable[threads];
        final Thread[] thread=new Thread[threads];
        for (int i=0;i<threads;i++)
        {
            final int id=i;
            final String name = "T"+i;
            thread[i]=new Thread()
            {
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

        _loops=new int[threads];
        for (int i=0;i<threads;i++)
            thread[i].start();
        
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
                int l=_loops[i];
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
            
            Log.info("min/ave/max/target="+min+"/"+(total/threads)+"/"+max+"/"+loops+" errors/finished/loops="+errors+"/"+finished+"/"+threads+" idle/threads="+(_threads.getIdleThreads())+"/"+_threads.getThreads());
            if ((finished+errors)==threads)
                break;
        }
        
        for (int i=0;i<threads;i++)
            thread[i].join();
        
        for (int i=0;i<threads;i++)
            if (throwable[i]!=null)
                throw throwable[i];
        

        
        int quantums=16;
        int[][] count = new int[_latencies.length][quantums];                        
        int length[] = new int[_latencies.length];                       
        int other[] = new int[_latencies.length];
        
        for (int i=0;i<_latencies.length;i++)
        {
            Queue<Long> latencies=_latencies[i];
            length[i] = latencies.size();

            loop:
            for (long latency:(Queue<Long>)(_latencies[i]))
            {
                for (int q=0;q<quantums;q++)
                {
                    if (latency>=(q*1000) && latency<((q+1)*1000))
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
            System.out.print(q+"000<=latency<"+(q+1)+"000");
            for (int i=0;i<_latencies.length;i++)
                System.out.print("\t"+count[i][q]);
            System.out.println();
        }

        System.out.print("    <=latency    ");
        for (int i=0;i<_latencies.length;i++)
            System.out.print("\t"+other[i]);
        System.out.println();
        
        System.out.print("TOTAL             ");
        for (int i=0;i<_latencies.length;i++)
            System.out.print("\t"+length[i]);
        System.out.println();
        
    }

    public void testNonPersistent() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS!");
            doThreads(200,100,false);
        }
        else
            doThreads(100,25,false);
    }
    
    

    public void testPersistent() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS!");
            doThreads(200,400,true);
        }
        else
            doThreads(100,100,true);
    }
    
    private class SuspendHandler extends HandlerWrapper
    {
        private Timer _timer;
        
        public SuspendHandler()
        {
            _timer=new Timer();
        }
        
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            long start=Long.parseLong(baseRequest.getHeader("start"));
            long received=baseRequest.getTimeStamp();

            _latencies[2].add(new Long(received-start));
            _latencies[3].add(new Long(System.currentTimeMillis()-start));
            
            
            
            response.setStatus(200);
            response.getOutputStream().print("NORMAL");
            baseRequest.setHandled(true);
            long end=System.currentTimeMillis();
            
            _latencies[4].add(new Long(System.currentTimeMillis()-start));
            
            return;
        }
    }
    
    
}
