package org.eclipse.jetty.websocket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

/**
 * @version $Revision$ $Date$
 */
public class TestClient
{
    private final static Random __random = new SecureRandom();
    private static boolean _verbose=false;
    private final String _host;
    private final int _port;
    private final String _protocol;
    private int _size=64;
    private final Socket _socket;
    private final BufferedWriter _output;
    private final BufferedReader _input;
    private final SocketEndPoint _endp;
    private final WebSocketGeneratorD06 _generator;
    private final WebSocketParserD06 _parser;
    private int _framesSent;
    private int _messagesSent;
    private int _framesReceived;
    private int _messagesReceived;
    private long _totalTime;
    private long _minDuration=Long.MAX_VALUE;
    private long _maxDuration=Long.MIN_VALUE;
    private long _start;
    private BlockingQueue<Long> _starts = new LinkedBlockingQueue<Long>();
    int _messageBytes;
    int _frames;
    byte _opcode=-1;
    private final WebSocketParser.FrameHandler _handler = new WebSocketParser.FrameHandler()
    {
        public synchronized void onFrame(byte flags, byte opcode, Buffer buffer)
        {
            try
            {
                _framesReceived++;
                _frames++;
                if (opcode == WebSocketConnectionD06.OP_CLOSE)
                {
                    byte[] data=buffer.asArray();
                    System.err.println("CLOSED: "+((0xff&data[0])*0x100+(0xff&data[1]))+" "+new String(data,2,data.length-2,StringUtil.__UTF8));
                    _generator.addFrame((byte)0x8,WebSocketConnectionD06.OP_CLOSE,data,0,data.length,_socket.getSoTimeout());
                    _generator.flush(_socket.getSoTimeout());
                    _socket.shutdownOutput();
                    _socket.close();
                    return;
                }
                
                _messageBytes+=buffer.length();
                
                if (_opcode==-1)
                    _opcode=opcode;
                

                if (WebSocketConnectionD06.isLastFrame(flags))
                {
                    _messagesReceived++;
                    Long start=_starts.take();


                    long duration = System.nanoTime()-start.longValue();
                    if (duration>_maxDuration)
                        _maxDuration=duration;
                    if (duration<_minDuration)
                        _minDuration=duration;
                    _totalTime+=duration;
                    System.out.printf("%d bytes from %s: frames=%d req=%d time=%.1fms opcode=0x%s\n",_messageBytes,_host,_frames,_messagesReceived,((double)duration/1000000.0),TypeUtil.toHexString(_opcode));
                    _frames=0;
                    _messageBytes=0;
                    _opcode=-1;
                }
                
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

        }

        public void close(int code,String message)
        {
        }
    };
    
    
    public TestClient(String host, int port,String protocol, int timeoutMS) throws IOException
    {
        _host=host;
        _port=port;
        _protocol=protocol;
        _socket = new Socket(host, port);
        _socket.setSoTimeout(timeoutMS);
        _output = new BufferedWriter(new OutputStreamWriter(_socket.getOutputStream(), "ISO-8859-1"));
        _input = new BufferedReader(new InputStreamReader(_socket.getInputStream(), "ISO-8859-1"));

        _endp=new SocketEndPoint(_socket);
        _generator = new WebSocketGeneratorD06(new WebSocketBuffers(32*1024),_endp,new WebSocketGeneratorD06.FixedMaskGen(new byte[4]));
        _parser = new WebSocketParserD06(new WebSocketBuffers(32*1024),_endp,_handler,false);
    }

    public int getSize()
    {
        return _size;
    }

    public void setSize(int size)
    {
        _size = size;
    }

    private void open() throws IOException
    {
        System.out.println("Jetty WebSocket PING "+_host+":"+_port+
                " ("+_socket.getRemoteSocketAddress()+") " +_size+" bytes of data.");
        byte[] key = new byte[16];
        __random.nextBytes(key);
        
        
        _output.write("GET /chat HTTP/1.1\r\n"+
                "Host: "+_host+":"+_port+"\r\n"+
                "Upgrade: websocket\r\n"+
                "Connection: Upgrade\r\n"+
                "Sec-WebSocket-Key: "+new String(B64Code.encode(key))+"\r\n"+
                "Sec-WebSocket-Origin: http://example.com\r\n"+
                "Sec-WebSocket-Protocol: "+_protocol+"\r\n" +
                "Sec-WebSocket-Version: 6\r\n"+
        "\r\n");
        _output.flush();

        String responseLine = _input.readLine();
        if(!responseLine.startsWith("HTTP/1.1 101 Switching Protocols"))
            throw new IOException(responseLine);
        // Read until we find Response key
        String line;
        boolean accepted=false;
        String protocol="";
        while ((line = _input.readLine()) != null)
        {
            if (line.length() == 0)
                break;
            if (line.startsWith("Sec-WebSocket-Accept:"))
            {
                String accept=line.substring(21).trim();
                accepted=accept.equals(WebSocketConnectionD06.hashKey(new String(B64Code.encode(key))));
            }
            else if (line.startsWith("Sec-WebSocket-Protocol:"))
            {
                protocol=line.substring(24).trim();
            }
        }
        
        if (!accepted)
            throw new IOException("Bad Sec-WebSocket-Accept");
        System.out.println("handshake OK for protocol '"+protocol+"'");
        
        new Thread()
        {
            public void run()
            {
                while (_endp.isOpen())
                {
                    _parser.parseNext();
                }
            }
        }.start();
    }

    public void ping(int count,byte opcode,int fragment)
    {
        try
        {
            _start=System.currentTimeMillis();
            for (int i=0;i<count && !_socket.isClosed();i++)
            {
                if (_socket.isClosed())
                    break;
                byte data[]=null;

                if (opcode==WebSocketConnectionD06.OP_TEXT)
                {
                    StringBuilder b = new StringBuilder();
                    while (b.length()<_size)
                        b.append('A'+__random.nextInt(26));
                    data=b.toString().getBytes(StringUtil.__UTF8);
                }
                else
                {             
                    data= new byte[_size];
                    __random.nextBytes(data);
                }
                _starts.add(System.nanoTime());

                int off=0;
                int len=data.length;
                if (fragment>0&& len>fragment)
                    len=fragment;
                _messagesSent++;
                while(off<data.length)
                {                    
                    _framesSent++;
                    byte flags= (byte)(off+len==data.length?0x8:0);
                    byte op=(byte)(off==0?opcode:WebSocketConnectionD06.OP_CONTINUATION);

                    if (_verbose)                
                        System.err.printf("%s#addFrame %s|%s %s\n",this.getClass().getSimpleName(),TypeUtil.toHexString(flags),TypeUtil.toHexString(op),TypeUtil.toHexString(data,off,len));
                    _generator.addFrame(flags,op,data,off,len,_socket.getSoTimeout());

                    off+=len;
                    if(data.length-off>len)
                        len=data.length-off;
                    if (fragment>0&& len>fragment)
                        len=fragment;
                }

                _generator.flush(_socket.getSoTimeout());

                Thread.sleep(1000);
            }
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    public void dump() throws Exception
    {
        for (int i=0;i<250;i++)
        {
            if (_messagesSent==_messagesReceived)
                break;
            _generator.flush(10);
            Thread.sleep(100);
        }
        
        _socket.close();
        long duration=System.currentTimeMillis()-_start;
        System.out.println("--- "+_host+" websocket ping statistics using 1 connection ---");
        System.out.println(_framesSent+" frames transmitted, "+_framesReceived+" received, "+
                _messagesSent+" messages transmitted, "+_messagesReceived+" received, "+
                "time "+duration+"ms");
        System.out.printf("rtt min/ave/max = %.3f/%.3f/%.3f ms\n",_minDuration/1000000.0,_messagesReceived==0?0.0:(_totalTime/_messagesReceived/1000000.0),_maxDuration/1000000.0);
    }
    

    private static void usage(String[] args)
    {
        System.err.println("ERROR: "+Arrays.asList(args));
        System.err.println("USAGE: java -cp CLASSPATH "+TestClient.class+" [ OPTIONS ]");
        System.err.println("  -h|--host HOST  (default localhost)");
        System.err.println("  -p|--port PORT  (default 8080)");
        System.err.println("  -b|--binary");
        System.err.println("  -v|--verbose");
        System.err.println("  -c|--count n    (default 10)");
        System.err.println("  -s|--size n     (default 64)");
        System.err.println("  -f|--fragment n (default 4000) ");
        System.err.println("  -P|--protocol echo|echo-assemble|echo-fragment|echo-broadcast");
        System.exit(1);
    }
    
    public static void main(String[] args)
    {
        try
        {
            String host="localhost";
            int port=8080;
            String protocol=null;
            int count=10;
            int size=64;
            int fragment=4000;
            boolean binary=false;
            
            for (int i=0;i<args.length;i++)
            {
                String a=args[i];
                if ("-p".equals(a)||"--port".equals(a))
                    port=Integer.parseInt(args[++i]);
                else if ("-h".equals(a)||"--host".equals(a))
                    port=Integer.parseInt(args[++i]);
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
                else if (a.startsWith("-"))
                    usage(args);
            }
            

            TestClient client = new TestClient(host,port,protocol==null?null:("org.ietf.websocket.test-"+protocol),10000);
            client.setSize(size);

            try
            {
                client.open();
                if (protocol!=null && protocol.startsWith("echo"))
                    client.ping(count,binary?WebSocketConnectionD06.OP_BINARY:WebSocketConnectionD06.OP_TEXT,fragment);
                else
                    client.ping(count,WebSocketConnectionD06.OP_PING,-1);
            }
            finally
            {
                client.dump();
            }
            
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
    
    
    
}
