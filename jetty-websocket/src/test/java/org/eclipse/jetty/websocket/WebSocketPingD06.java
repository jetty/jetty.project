package org.eclipse.jetty.websocket;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.Assert;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.TypeUtil;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketPingD06
{
    private final static Random __random = new SecureRandom();
    private final String _host;
    private final int _port;
    private final int _size=64;
    private final Socket _socket;
    private final BufferedWriter _output;
    private final BufferedReader _input;
    private final SocketEndPoint _endp;
    private final WebSocketGeneratorD06 _generator;
    private final WebSocketParserD06 _parser;
    private int _sent;
    private int _received;
    private long _totalTime;
    private long _minDuration=Long.MAX_VALUE;
    private long _maxDuration=Long.MIN_VALUE;
    private long _start;
    private BlockingQueue<Long> _starts = new LinkedBlockingQueue<Long>();
    private BlockingQueue<String> _pending = new LinkedBlockingQueue<String>();
    private final WebSocketParser.FrameHandler _handler = new WebSocketParser.FrameHandler()
    {
        public synchronized void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
        {

            long start=_starts.poll();
            String data=_pending.poll();
            
            while (!data.equals(TypeUtil.toHexString(buffer.asArray())) && !_starts.isEmpty() && !_pending.isEmpty())
            {
                // Missed response
                start=_starts.poll();
                data=_pending.poll();
            }

            _received++;

            long duration = System.nanoTime()-start;
            if (duration>_maxDuration)
                _maxDuration=duration;
            if (duration<_minDuration)
                _minDuration=duration;
            _totalTime+=duration;
            System.out.print(buffer.length()+" bytes from "+_host+": req="+_received+" time=");
            System.out.printf("%.1fms\n",((double)duration/1000000.0));

        }
    };
    
    
    public WebSocketPingD06(String host, int port,int timeoutMS) throws IOException
    {
        _host=host;
        _port=port;
        _socket = new Socket(host, port);
        _socket.setSoTimeout(timeoutMS);
        _output = new BufferedWriter(new OutputStreamWriter(_socket.getOutputStream(), "ISO-8859-1"));
        _input = new BufferedReader(new InputStreamReader(_socket.getInputStream(), "ISO-8859-1"));

        _endp=new SocketEndPoint(_socket);
        _generator = new WebSocketGeneratorD06(new WebSocketBuffers(32*1024),_endp,new WebSocketGeneratorD06.FixedMaskGen());
        _parser = new WebSocketParserD06(new WebSocketBuffers(32*1024),_endp,_handler,false);
        
        

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
                "Sec-WebSocket-Protocol: lws-mirror-protocol\r\n" +
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
        System.out.println("handshake OK for protocol "+protocol);
        
        new Thread()
        {
            public void run()
            {
                while (_endp.isOpen())
                    _parser.parseNext();
            }
        }.start();
    }

    public void run()
    {
        _start=System.currentTimeMillis();
        for (int i=0;i<10;i++)
        {
            try
            {
                byte data[] = new byte[_size];
                __random.nextBytes(data);
                
                
                _starts.add(System.nanoTime());
                _pending.add(TypeUtil.toHexString(data));
                _sent++;
                _generator.addFrame(WebSocket.OP_PING,data,_socket.getSoTimeout());
                _generator.flush(_socket.getSoTimeout());

                Thread.sleep(1000);
               
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
    }


    public void dump() throws IOException
    {
        _socket.close();
        long duration=System.currentTimeMillis()-_start;
        System.out.println("--- "+_host+" websocket ping statistics using 1 connection ---");
        System.out.println(_sent+" packets transmitted, "+_received+" received, "+
                String.format("%d",100*(_sent-_received)/_sent)+"% loss, time "+duration+"ms");
        System.out.printf("rtt min/ave/max = %.3f/%.3f/%.3f ms\n",_minDuration/1000000.0,_totalTime/_received/1000000.0,_maxDuration/1000000.0);
    }
    
    public static void main(String[] args)
        throws Exception
    {
        
        WebSocketPingD06 ping = new WebSocketPingD06("localhost",8080,10000);
        
        try
        {
            ping.open();
            ping.run();
        }
        finally
        {
            ping.dump();
        }
    }
}
