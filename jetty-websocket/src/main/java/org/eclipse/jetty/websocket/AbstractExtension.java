package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.websocket.WebSocketParser.FrameHandler;

public class AbstractExtension implements Extension
{
    private final String _name;
    private final byte[] _dataOpcodes;
    private final byte[] _controlOpcodes;
    private final byte[] _bitMasks;
    private FrameHandler _inbound;
    private WebSocketGenerator _outbound;
    
    public AbstractExtension(String name,int dataCodes, int controlCodes, int flags)
    {
        _name = name;
        _dataOpcodes=new byte[dataCodes];
        _controlOpcodes=new byte[controlCodes];
        _bitMasks=new byte[flags];
    }
    
    public int getDataOpcodes()
    {
        return _dataOpcodes.length;
    }

    public int getControlOpcodes()
    {
        return _controlOpcodes.length;
    }

    public int getReservedBits()
    {
        return _bitMasks.length;
    }

    public void init(FrameHandler incoming, WebSocketGenerator outgoing, byte[] dataOpcodes, byte[] controlOpcodes, byte[] bitMasks)
    {
        _inbound=incoming;
        _outbound=outgoing;
        if (dataOpcodes!=null)
            System.arraycopy(dataOpcodes,0,_dataOpcodes,0,dataOpcodes.length);
        if (controlOpcodes!=null)
            System.arraycopy(controlOpcodes,0,_dataOpcodes,0,controlOpcodes.length);
        if (bitMasks!=null)
            System.arraycopy(bitMasks,0,_bitMasks,0,bitMasks.length);
    }

    public String getExtensionName()
    {
        return _name;
    }

    public void onFrame(byte flags, byte opcode, Buffer buffer)
    {
        // System.err.printf("onFrame %s %x %x %d\n",getExtensionName(),flags,opcode,buffer.length());
        _inbound.onFrame(flags,opcode,buffer);
    }

    public void close(int code, String message)
    {
        _inbound.close(code,message);
    }

    public int flush() throws IOException
    {
        return _outbound.flush();
    }

    public boolean isBufferEmpty()
    {
        return _outbound.isBufferEmpty();
    }

    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        // System.err.printf("addFrame %s %x %x %d\n",getExtensionName(),flags,opcode,length);
        _outbound.addFrame(flags,opcode,content,offset,length);
    }

    public byte dataOpcode(int i)
    {
        return _dataOpcodes[i];
    }

    public int dataIndex(byte op)
    {
        for (int i=0;i<_dataOpcodes.length;i++)
            if (_dataOpcodes[i]==op)
                return i;
        return -1;
    }

    public byte controlOpcode(int i)
    {
        return _dataOpcodes[i];
    }

    public int controlIndex(byte op)
    {
        for (int i=0;i<_controlOpcodes.length;i++)
            if (_controlOpcodes[i]==op)
                return i;
        return -1;
    }
    
    public byte setFlag(byte flags,int flag)
    {
        return (byte)(flags | _bitMasks[flag]);
    }
    
    public byte clearFlag(byte flags,int flag)
    {
        return (byte)(flags & ~_bitMasks[flag]);
    }

    public boolean isFlag(byte flags,int flag)
    {
        return (flags & _bitMasks[flag])!=0;
    }
}
