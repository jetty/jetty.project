package org.eclipse.jetty.websocket;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

/**
 * @version $Revision$ $Date$
 * 
 * This is not a general purpose websocket client.
 * It's only for testing the websocket server and is hardwired to a specific draft version of the protocol.
 */
public class TestClient implements WebSocket.OnFrame
{
    private static WebSocketClientFactory __clientFactory = new WebSocketClientFactory();
    private static boolean _verbose=false;

    private static final Random __random = new Random();
    
    private final String _host;
    private final int _port;
    private final String _protocol;
    private final int _timeout;
    
    private static boolean __quiet;
    private static int __framesSent;
    private static int __messagesSent;
    private static AtomicInteger __framesReceived=new AtomicInteger();
    private static AtomicInteger __messagesReceived=new AtomicInteger();
    
    private static AtomicLong __totalTime=new AtomicLong();
    private static AtomicLong __minDuration=new AtomicLong(Long.MAX_VALUE);
    private static AtomicLong __maxDuration=new AtomicLong(Long.MIN_VALUE);
    private static long __start;
    private BlockingQueue<Long> _starts = new LinkedBlockingQueue<Long>();
    int _messageBytes;
    int _frames;
    byte _opcode=-1;
    private volatile WebSocket.FrameConnection _connection;
    private final CountDownLatch _handshook = new CountDownLatch(1);
    
    
    public void onOpen(Connection connection)
    {
        if (_verbose)
            System.err.printf("%s#onHandshake %s %s\n",this.getClass().getSimpleName(),connection,connection.getClass().getSimpleName());
    }

    public void onClose(int closeCode, String message)
    {
        _handshook.countDown();
    }

    public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
    {
        try
        {
            if (_connection.isClose(opcode))
                return false;
            
            __framesReceived.incrementAndGet();
            _frames++;
            _messageBytes+=length;
            
            if (_opcode==-1)
                _opcode=opcode;
            
            if (_connection.isControl(opcode) || _connection.isMessageComplete(flags))
            {  
                int recv =__messagesReceived.incrementAndGet();
                Long start=_starts.poll();

                if (start!=null)
                {
                    long duration = System.nanoTime()-start.longValue();
                    long max=__maxDuration.get();
                    while(duration>max && !__maxDuration.compareAndSet(max,duration))
                        max=__maxDuration.get();
                    long min=__minDuration.get();
                    while(duration<min && !__minDuration.compareAndSet(min,duration))
                        min=__minDuration.get();
                    __totalTime.addAndGet(duration);
                    if (!__quiet)
                        System.out.printf("%d bytes from %s: frames=%d req=%d time=%.1fms opcode=0x%s\n",_messageBytes,_host,_frames,recv,((double)duration/1000000.0),TypeUtil.toHexString(_opcode));
                }
                _frames=0;
                _messageBytes=0;
                _opcode=-1;   
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        return false;
    }

    public void onHandshake(FrameConnection connection)
    {
        _connection=connection;
        _handshook.countDown();
    }
    
    public TestClient(String host, int port,String protocol, int timeoutMS) throws Exception
    {
        _host=host;
        _port=port;
        _protocol=protocol;
        _timeout=timeoutMS;
    }

    private void open() throws Exception
    {
        WebSocketClient client = new WebSocketClient(__clientFactory);
        client.setProtocol(_protocol);
        client.setMaxIdleTime(_timeout);
        client.open(new URI("ws://"+_host+":"+_port+"/"),this).get(10,TimeUnit.SECONDS);
    }

    public void ping(byte opcode,byte[] data,int fragment) throws Exception
    {
        _starts.add(System.nanoTime());

        int off=0;
        int len=data.length;
        if (fragment>0&& len>fragment)
            len=fragment;
        __messagesSent++;
        while(off<data.length)
        {                    
            __framesSent++;
            byte flags= (byte)(off+len==data.length?0x8:0);
            byte op=(byte)(off==0?opcode:WebSocketConnectionD13.OP_CONTINUATION);

            if (_verbose)                
                System.err.printf("%s#sendFrame %s|%s %s\n",this.getClass().getSimpleName(),TypeUtil.toHexString(flags),TypeUtil.toHexString(op),TypeUtil.toHexString(data,off,len));

            _connection.sendFrame(flags,op,data,off,len);

            off+=len;
            if(data.length-off>len)
                len=data.length-off;
            if (fragment>0&& len>fragment)
                len=fragment;
        }
    }

    public void disconnect() throws Exception
    {
        if (_connection!=null)
            _connection.disconnect();
    }
    

    private static void usage(String[] args)
    {
        System.err.println("ERROR: "+Arrays.asList(args));
        System.err.println("USAGE: java -cp CLASSPATH "+TestClient.class+" [ OPTIONS ]");
        System.err.println("  -h|--host HOST  (default localhost)");
        System.err.println("  -p|--port PORT  (default 8080)");
        System.err.println("  -b|--binary");
        System.err.println("  -v|--verbose");
        System.err.println("  -q|--quiet");
        System.err.println("  -c|--count n    (default 10)");
        System.err.println("  -s|--size n     (default 64)");
        System.err.println("  -f|--fragment n (default 4000) ");
        System.err.println("  -P|--protocol echo|echo-assemble|echo-fragment|echo-broadcast");
        System.err.println("  -C|--clients n  (default 1) ");
        System.err.println("  -d|--delay n    (default 1000ms) ");
        System.exit(1);
    }
    
    public static void main(String[] args) throws Exception
    {
        __clientFactory.start();

        String host="localhost";
        int port=8080;
        String protocol=null;
        int count=10;
        int size=64;
        int fragment=4000;
        boolean binary=false;
        int clients=1;
        int delay=1000;

        for (int i=0;i<args.length;i++)
        {
            String a=args[i];
            if ("-p".equals(a)||"--port".equals(a))
                port=Integer.parseInt(args[++i]);
            else if ("-h".equals(a)||"--host".equals(a))
                host=args[++i];
            else if ("-c".equals(a)||"--count".equals(a))
                count=Integer.parseInt(args[++i]);
            else if ("-s".equals(a)||"--size".equals(a))
                size=Integer.parseInt(args[++i]);
            else if ("-f".equals(a)||"--fragment".equals(a))
                fragment=Integer.parseInt(args[++i]);
            else if ("-P".equals(a)||"--protocol".equals(a))
                protocol=args[++i];
            else if ("-v".equals(a)||"--verbose".equals(a))
                _verbose=true;
            else if ("-b".equals(a)||"--binary".equals(a))
                binary=true;
            else if ("-C".equals(a)||"--clients".equals(a))
                clients=Integer.parseInt(args[++i]);
            else if ("-d".equals(a)||"--delay".equals(a))
                delay=Integer.parseInt(args[++i]);
            else if ("-q".equals(a)||"--quiet".equals(a))
                __quiet=true;
            else if (a.startsWith("-"))
                usage(args);
        }


        TestClient[] client = new TestClient[clients];
        
        try
        {
            __start=System.currentTimeMillis();
            protocol=protocol==null?"echo":protocol;
            
            for (int i=0;i<clients;i++)
            {
                client[i]=new TestClient(host,port,protocol==null?null:protocol,60000);
                client[i].open();
            }

            System.out.println("Jetty WebSocket PING "+host+":"+port+
                    " ("+ new InetSocketAddress(host,port)+") "+clients+" clients "+protocol);
            
            
            for (int p=0;p<count;p++)
            {
                long next = System.currentTimeMillis()+delay;
                
                byte opcode=binary?WebSocketConnectionD13.OP_BINARY:WebSocketConnectionD13.OP_TEXT;
                
                byte data[]=null;

                if (opcode==WebSocketConnectionD13.OP_TEXT)
                {
                    StringBuilder b = new StringBuilder();
                    while (b.length()<size)
                        b.append('A'+__random.nextInt(26));
                    data=b.toString().getBytes(StringUtil.__UTF8);
                }
                else
                {             
                    data= new byte[size];
                    __random.nextBytes(data);
                }

                for (int i=0;i<clients;i++)
                    client[i].ping(opcode,data,opcode==WebSocketConnectionD13.OP_PING?-1:fragment);
                
                while(System.currentTimeMillis()<next)
                    Thread.sleep(10);
            }
        }
        finally
        {
            for (int i=0;i<clients;i++)
                if (client[i]!=null)
                    client[i].disconnect();
            
            long duration=System.currentTimeMillis()-__start;
            System.out.println("--- "+host+" websocket ping statistics using "+clients+" connection"+(clients>1?"s":"")+" ---");
            System.out.printf("%d/%d frames sent/recv, %d/%d mesg sent/recv, time %dms %dm/s %.2fbps%n",
                    __framesSent,__framesReceived.get(),
                    __messagesSent,__messagesReceived.get(),
                    duration,(1000L*__messagesReceived.get()/duration),
                    1000.0D*__messagesReceived.get()*8*size/duration/1024/1024);
            System.out.printf("rtt min/ave/max = %.3f/%.3f/%.3f ms\n",__minDuration.get()/1000000.0,__messagesReceived.get()==0?0.0:(__totalTime.get()/__messagesReceived.get()/1000000.0),__maxDuration.get()/1000000.0);
            
            __clientFactory.stop();
        }

    }
    
    
    
}
