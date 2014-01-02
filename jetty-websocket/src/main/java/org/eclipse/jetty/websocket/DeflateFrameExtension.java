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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * TODO Implement proposed deflate frame draft
 */
public class DeflateFrameExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(DeflateFrameExtension.class);

    private int _minLength=8;
    private Deflater _deflater;
    private Inflater _inflater;

    public DeflateFrameExtension()
    {
        super("x-deflate-frame");
    }

    @Override
    public boolean init(Map<String, String> parameters)
    {
        if (!parameters.containsKey("minLength"))
            parameters.put("minLength",Integer.toString(_minLength));
        if(super.init(parameters))
        {
            _minLength=getInitParameter("minLength",_minLength);

            _deflater=new Deflater();
            _inflater=new Inflater();

            return true;
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.AbstractExtension#onFrame(byte, byte, org.eclipse.jetty.io.Buffer)
     */
    @Override
    public void onFrame(byte flags, byte opcode, Buffer buffer)
    {
        if (getConnection().isControl(opcode) || !isFlag(flags,1))
        {
            super.onFrame(flags,opcode,buffer);
            return;
        }

        if (buffer.array()==null)
            buffer=buffer.asMutableBuffer();

        int length=0xff&buffer.get();
        if (length>=0x7e)
        {
            int b=(length==0x7f)?8:2;
            length=0;
            while(b-->0)
                length=0x100*length+(0xff&buffer.get());
        }

        // TODO check a max framesize

        _inflater.setInput(buffer.array(),buffer.getIndex(),buffer.length());
        ByteArrayBuffer buf = new ByteArrayBuffer(length);
        try
        {
            while(_inflater.getRemaining()>0)
            {
                int inflated=_inflater.inflate(buf.array(),buf.putIndex(),buf.space());
                if (inflated==0)
                    throw new DataFormatException("insufficient data");
                buf.setPutIndex(buf.putIndex()+inflated);
            }

            super.onFrame(clearFlag(flags,1),opcode,buf);
        }
        catch(DataFormatException e)
        {
            LOG.warn(e);
            getConnection().close(WebSocketConnectionRFC6455.CLOSE_BAD_PAYLOAD,e.toString());
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.AbstractExtension#addFrame(byte, byte, byte[], int, int)
     */
    @Override
    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        if (getConnection().isControl(opcode) || length<_minLength)
        {
            super.addFrame(clearFlag(flags,1),opcode,content,offset,length);
            return;
        }

        // prepare the uncompressed input
        _deflater.reset();
        _deflater.setInput(content,offset,length);
        _deflater.finish();

        // prepare the output buffer
        byte[] out= new byte[length];
        int out_offset=0;

        // write the uncompressed length
        if (length>0xffff)
        {
            out[out_offset++]=0x7f;
            out[out_offset++]=(byte)0;
            out[out_offset++]=(byte)0;
            out[out_offset++]=(byte)0;
            out[out_offset++]=(byte)0;
            out[out_offset++]=(byte)((length>>24)&0xff);
            out[out_offset++]=(byte)((length>>16)&0xff);
            out[out_offset++]=(byte)((length>>8)&0xff);
            out[out_offset++]=(byte)(length&0xff);
        }
        else if (length >=0x7e)
        {
            out[out_offset++]=0x7e;
            out[out_offset++]=(byte)(length>>8);
            out[out_offset++]=(byte)(length&0xff);
        }
        else
        {
            out[out_offset++]=(byte)(length&0x7f);
        }

        int l = _deflater.deflate(out,out_offset,length-out_offset);

        if (_deflater.finished())
            super.addFrame(setFlag(flags,1),opcode,out,0,l+out_offset);
        else
            super.addFrame(clearFlag(flags,1),opcode,content,offset,length);
    }
}
