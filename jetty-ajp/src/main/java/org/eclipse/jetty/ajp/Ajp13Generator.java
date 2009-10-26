// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.ajp;

import java.io.IOException;
import java.util.HashMap;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

/**
 * 
 * 
 */                                                                                                       
public class Ajp13Generator extends AbstractGenerator
{
    private static HashMap __headerHash = new HashMap();

    static
    {
        byte[] xA001 =
        { (byte) 0xA0, (byte) 0x01 };
        byte[] xA002 =
        { (byte) 0xA0, (byte) 0x02 };
        byte[] xA003 =
        { (byte) 0xA0, (byte) 0x03 };
        byte[] xA004 =
        { (byte) 0xA0, (byte) 0x04 };
        byte[] xA005 =
        { (byte) 0xA0, (byte) 0x05 };
        byte[] xA006 =
        { (byte) 0xA0, (byte) 0x06 };
        byte[] xA007 =
        { (byte) 0xA0, (byte) 0x07 };
        byte[] xA008 =
        { (byte) 0xA0, (byte) 0x08 };
        byte[] xA009 =
        { (byte) 0xA0, (byte) 0x09 };
        byte[] xA00A =
        { (byte) 0xA0, (byte) 0x0A };
        byte[] xA00B =
        { (byte) 0xA0, (byte) 0x0B };
        __headerHash.put("Content-Type", xA001);
        __headerHash.put("Content-Language", xA002);
        __headerHash.put("Content-Length", xA003);
        __headerHash.put("Date", xA004);
        __headerHash.put("Last-Modified", xA005);
        __headerHash.put("Location", xA006);
        __headerHash.put("Set-Cookie", xA007);
        __headerHash.put("Set-Cookie2", xA008);
        __headerHash.put("Servlet-Engine", xA009);
        __headerHash.put("Status", xA00A);
        __headerHash.put("WWW-Authenticate", xA00B);

    }

    // A, B ajp response header
    // 0, 1 ajp int 1 packet length
    // 9 CPONG response Code
    private static final byte[] AJP13_CPONG_RESPONSE =
    { 'A', 'B', 0, 1, 9};

    private static final byte[] AJP13_END_RESPONSE =
    { 'A', 'B', 0, 2, 5, 1 };

    // AB ajp respose
    // 0, 3 int = 3 packets in length
    // 6, send signal to get more data
    // 31, -7 byte values for int 8185 = (8 * 1024) - 7 MAX_DATA
    private static final byte[] AJP13_MORE_CONTENT =
    { 'A', 'B', 0, 3, 6, 31, -7 };

    private static String SERVER = "Server: Jetty(6.0.x)";

    public static void setServerVersion(String version)
    {
        SERVER = "Jetty(" + version + ")";
    }

    /* ------------------------------------------------------------ */
    private boolean _expectMore = false;

    private boolean _needMore = false;

    private boolean _needEOC = false;

    private boolean _bufferPrepared = false;

    /* ------------------------------------------------------------ */
    public Ajp13Generator(Buffers buffers, EndPoint io)
    {
        super(buffers, io);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void reset(boolean returnBuffers)
    {
        super.reset(returnBuffers);

        _needEOC = false;
        _needMore = false;
        _expectMore = false;
        _bufferPrepared = false;
        _last=false;



        _state = STATE_HEADER;

        _status = 0;
        _version = HttpVersions.HTTP_1_1_ORDINAL;
        _reason = null;
        _method = null;
        _uri = null;

        _contentWritten = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _last = false;
        _head = false;
        _noContent = false;
        _close = false;


       

       _header = null; // Buffer for HTTP header (and maybe small _content)
       _buffer = null; // Buffer for copy of passed _content
       _content = null; // Buffer passed to addContent


    }

    /* ------------------------------------------------------------ */
    /**
     * Add content.
     * 
     * @param content
     * @param last
     * @throws IllegalArgumentException
     *             if <code>content</code> is
     *             {@link Buffer#isImmutable immutable}.
     * @throws IllegalStateException
     *             If the request is not expecting any more content, or if the
     *             buffers are full and cannot be flushed.
     * @throws IOException
     *             if there is a problem flushing the buffers.
     */
    public void addContent(Buffer content, boolean last) throws IOException
    {
        if (_noContent)
        {
            content.clear();
            return;
        }

        if (content.isImmutable())
            throw new IllegalArgumentException("immutable");

        if (_last || _state == STATE_END)
        {
            Log.debug("Ignoring extra content {}", content);
            content.clear();
            return;
        }
        _last = last;

        if(!_endp.isOpen())
        {
            _state = STATE_END;
            return;
        }

        // Handle any unfinished business?
        if (_content != null && _content.length() > 0)
        {

            flushBuffer();
            if (_content != null && _content.length() > 0)
                throw new IllegalStateException("FULL");
        }

        _content = content;

        _contentWritten += content.length();

        // Handle the _content
        if (_head)
        {

            content.clear();
            _content = null;
        }
        else
        {
            // Yes - so we better check we have a buffer
            initContent();
            // Copy _content to buffer;
            int len = 0;
            len = _buffer.put(_content);

            // make sure there is space for a trailing null
            if (len > 0 && _buffer.space() == 0)
            {
                len--;
                _buffer.setPutIndex(_buffer.putIndex() - 1);
            }

            _content.skip(len);

            if (_content.length() == 0)
                _content = null;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Add content.
     * 
     * @param b
     *            byte
     * @return true if the buffers are full
     * @throws IOException
     */
    public boolean addContent(byte b) throws IOException
    {

        if (_noContent)
            return false;

        if (_last || _state == STATE_END)
            throw new IllegalStateException("Closed");


        if(!_endp.isOpen())
        {
            _state = STATE_END;
            return false;
        }

        // Handle any unfinished business?
        if (_content != null && _content.length() > 0)
        {
            flushBuffer();
            if (_content != null && _content.length() > 0)
                throw new IllegalStateException("FULL");
        }

        _contentWritten++;

        // Handle the _content
        if (_head)
            return false;

        // we better check we have a buffer
        initContent();

        // Copy _content to buffer;

        _buffer.put(b);

        return _buffer.space() <= 1;
    }

    /* ------------------------------------------------------------ */
    /**
     * Prepare buffer for unchecked writes. Prepare the generator buffer to
     * receive unchecked writes
     * 
     * @return the available space in the buffer.
     * @throws IOException
     */
    @Override
    public int prepareUncheckedAddContent() throws IOException
    {
        if (_noContent)
            return -1;

        if (_last || _state == STATE_END)
            throw new IllegalStateException("Closed");


        if(!_endp.isOpen())
        {
            _state = STATE_END;
            return -1;
        }

        // Handle any unfinished business?
        Buffer content = _content;
        if (content != null && content.length() > 0)
        {
            flushBuffer();
            if (content != null && content.length() > 0)
                throw new IllegalStateException("FULL");
        }

        // we better check we have a buffer
        initContent();

        _contentWritten -= _buffer.length();

        // Handle the _content
        if (_head)
            return Integer.MAX_VALUE;

        return _buffer.space() - 1;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
    {
        if (_state != STATE_HEADER)
            return;

        if (_last && !allContentAdded)
            throw new IllegalStateException("last?");
        _last = _last | allContentAdded;

        boolean has_server = false;
        if (_version == HttpVersions.HTTP_1_0_ORDINAL)
            _close = true;

        // get a header buffer
        if (_header == null)
            _header = _buffers.getHeader();

        Buffer tmpbuf = _buffer;
        _buffer = _header;

        try
        {
            // start the header
            _buffer.put((byte) 'A');
            _buffer.put((byte) 'B');
            addInt(0);
            _buffer.put((byte) 0x4);
            addInt(_status);
            if (_reason == null)
                _reason=HttpGenerator.getReasonBuffer(_status);
            if (_reason == null)
                _reason = new ByteArrayBuffer(TypeUtil.toString(_status));
            addBuffer(_reason);

            if (_status == 100 || _status == 204 || _status == 304)
            {
                _noContent = true;
                _content = null;
            }


            // allocate 2 bytes for number of headers
            int field_index = _buffer.putIndex();
            addInt(0);

            int num_fields = 0;

            if (fields != null)
            { 
                // Add headers
                int s=fields.size();
                for (int f=0;f<s;f++)
                {
                    HttpFields.Field field = fields.getField(f);
                    if (field==null)
                        continue;
                    num_fields++;
                    
                    byte[] codes = (byte[]) __headerHash.get(field.getName());
                    if (codes != null)
                    {
                        _buffer.put(codes);
                    }
                    else
                    {
                        addString(field.getName());
                    }
                    addString(field.getValue());
                }
            }

            if (!has_server && _status > 100 && getSendServerVersion())
            {
                num_fields++;
                addString("Server");
                addString(SERVER);
            }

            // TODO Add content length if last content known.

            // insert the number of headers
            int tmp = _buffer.putIndex();
            _buffer.setPutIndex(field_index);
            addInt(num_fields);
            _buffer.setPutIndex(tmp);

            // get the payload size ( - 4 bytes for the ajp header)
            // excluding the
            // ajp header
            int payloadSize = _buffer.length() - 4;
            // insert the total packet size on 2nd and 3rd byte that
            // was previously
            // allocated
            addInt(2, payloadSize);
        }
        finally
        {
            _buffer = tmpbuf;
        }


        _state = STATE_CONTENT;

    }

    /* ------------------------------------------------------------ */
    /**
     * Complete the message.
     * 
     * @throws IOException
     */
    @Override
    public void complete() throws IOException
    {
        if (_state == STATE_END)
            return;

        super.complete();

        if (_state < STATE_FLUSHING)
        {
            _state = STATE_FLUSHING;
            _needEOC = true;
        }

        flushBuffer();
    }

    /* ------------------------------------------------------------ */
    @Override
    public long flushBuffer() throws IOException
    {
        try
        {
            if (_state == STATE_HEADER  && !_expectMore)
                throw new IllegalStateException("State==HEADER");
            prepareBuffers();

            if (_endp == null)
            {
                // TODO - probably still needed!
                // if (_rneedMore && _buffe != null)
                // {
                // if(!_hasSentEOC)
                // _buffer.put(AJP13_MORE_CONTENT);
                // }
                if (!_expectMore && _needEOC && _buffer != null)
                {
                    _buffer.put(AJP13_END_RESPONSE);
                }
                _needEOC = false;
                return 0;
            }

            // Keep flushing while there is something to flush
            // (except break below)
            int total = 0;
            long last_len = -1;
            Flushing: while (true)
            {
                int len = -1;
                int to_flush = ((_header != null && _header.length() > 0) ? 4 : 0) | ((_buffer != null && _buffer.length() > 0) ? 2 : 0);
                

                switch (to_flush)
                {
                case 7:
                    throw new IllegalStateException(); // should
                    // never
                    // happen!
                case 6:
                    len = _endp.flush(_header, _buffer, null);

                    break;
                case 5:
                    throw new IllegalStateException(); // should
                    // never
                    // happen!
                case 4:
                    len = _endp.flush(_header);
                    break;
                case 3:
                    throw new IllegalStateException(); // should
                    // never
                    // happen!
                case 2:
                    len = _endp.flush(_buffer);

                    break;
                case 1:
                    throw new IllegalStateException(); // should
                    // never
                    // happen!
                case 0:
                {
                    // Nothing more we can write now.
                    if (_header != null)
                        _header.clear();

                    _bufferPrepared = false;

                    if (_buffer != null)
                    {
                        _buffer.clear();

                        // reserve some space for the
                        // header
                        _buffer.setPutIndex(7);
                        _buffer.setGetIndex(7);

                        // Special case handling for
                        // small left over buffer from
                        // an addContent that caused a
                        // buffer flush.
                        if (_content != null && _content.length() < _buffer.space() && _state != STATE_FLUSHING)
                        {

                            _buffer.put(_content);
                            _content.clear();
                            _content = null;
                            break Flushing;
                        }

                    }



                    // Are we completely finished for now?
                    if (!_expectMore && !_needEOC && (_content == null || _content.length() == 0))
                    {
                        if (_state == STATE_FLUSHING)
                            _state = STATE_END;

//                        if (_state == STATE_END)
//                        {
//                            _endp.close();
//                        }
//

                        break Flushing;
                    }

                    // Try to prepare more to write.
                    prepareBuffers();
                }
                }

                // If we failed to flush anything twice in a row
                // break
                if (len <= 0)
                {
                    if (last_len <= 0)
                        break Flushing;
                    break;
                }
                last_len = len;
                total += len;
            }



            return total;
        }
        catch (IOException e)
        {
            Log.ignore(e);
            throw (e instanceof EofException) ? e : new EofException(e);
        }

    }

    /* ------------------------------------------------------------ */
    private void prepareBuffers()
    {
        if (!_bufferPrepared)
        {

            // Refill buffer if possible
            if (_content != null && _content.length() > 0 && _buffer != null && _buffer.space() > 0)
            {

                int len = _buffer.put(_content);

                // Make sure there is space for a trailing null
                if (len > 0 && _buffer.space() == 0)
                {
                    len--;
                    _buffer.setPutIndex(_buffer.putIndex() - 1);
                }
                _content.skip(len);

                if (_content.length() == 0)
                    _content = null;

                if (_buffer.length() == 0)
                {
                    _content = null;
                }
            }

            // add header if needed
            if (_buffer != null)
            {

                int payloadSize = _buffer.length();

                // 4 bytes for the ajp header
                // 1 byte for response type
                // 2 bytes for the response size
                // 1 byte because we count from zero??

                if (payloadSize > 0)
                {
                    _bufferPrepared = true;

                    _buffer.put((byte) 0);
                    int put = _buffer.putIndex();
                    _buffer.setGetIndex(0);
                    _buffer.setPutIndex(0);
                    _buffer.put((byte) 'A');
                    _buffer.put((byte) 'B');
                    addInt(payloadSize + 4);
                    _buffer.put((byte) 3);
                    addInt(payloadSize);
                    _buffer.setPutIndex(put);
                }
            }

            if (_needMore)
            {

                if (_header == null)
                {
                    _header = _buffers.getHeader();
                }

                if (_buffer == null && _header != null && _header.space() >= AJP13_MORE_CONTENT.length)
                {
                    _header.put(AJP13_MORE_CONTENT);
                    _needMore = false;
                }
                else if (_buffer != null && _buffer.space() >= AJP13_MORE_CONTENT.length)
                {
                    // send closing packet if all contents
                    // are added
                    _buffer.put(AJP13_MORE_CONTENT);
                    _needMore = false;
                    _bufferPrepared = true;
                }

            }

            if (!_expectMore && _needEOC)
            {
                if (_buffer == null && _header.space() >= AJP13_END_RESPONSE.length)
                {

                    _header.put(AJP13_END_RESPONSE);
                    _needEOC = false;
                }
                else if (_buffer != null && _buffer.space() >= AJP13_END_RESPONSE.length)
                {
                    // send closing packet if all contents
                    // are added

                    _buffer.put(AJP13_END_RESPONSE);
                    _needEOC = false;
                    _bufferPrepared = true;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isComplete()
    {
        return !_expectMore && _state == STATE_END;
    }

    /* ------------------------------------------------------------ */
    private void initContent() throws IOException
    {
        if (_buffer == null)
        {
            _buffer = _buffers.getBuffer();
            _buffer.setPutIndex(7);
            _buffer.setGetIndex(7);
        }
    }

    /* ------------------------------------------------------------ */
    private void addInt(int i)
    {
        _buffer.put((byte) ((i >> 8) & 0xFF));
        _buffer.put((byte) (i & 0xFF));
    }

    /* ------------------------------------------------------------ */
    private void addInt(int startIndex, int i)
    {
        _buffer.poke(startIndex, (byte) ((i >> 8) & 0xFF));
        _buffer.poke((startIndex + 1), (byte) (i & 0xFF));
    }

    /* ------------------------------------------------------------ */
    private void addString(String str)
    {
        if (str == null)
        {
            addInt(0xFFFF);
            return;
        }

        // TODO - need to use a writer to convert, to avoid this hacky
        // conversion and temp buffer
        byte[] b = str.getBytes();

        addInt(b.length);

        _buffer.put(b);
        _buffer.put((byte) 0);
    }

    /* ------------------------------------------------------------ */
    private void addBuffer(Buffer b)
    {
        if (b == null)
        {
            addInt(0xFFFF);
            return;
        }

        addInt(b.length());
        _buffer.put(b);
        _buffer.put((byte) 0);
    }

    /* ------------------------------------------------------------ */
    public void getBodyChunk() throws IOException
    {
        _needMore = true;
        _expectMore = true;
        flushBuffer();
    }

    /* ------------------------------------------------------------ */
    public void gotBody()
    {
        _needMore = false;
        _expectMore = false;
    }


    /* ------------------------------------------------------------ */
    public void sendCPong() throws IOException
    {

        Buffer buff = _buffers.getBuffer();
        buff.put(AJP13_CPONG_RESPONSE);

        // flushing cpong response
        do
        {
            _endp.flush(buff);
        }
        while(buff.length() >0);
        _buffers.returnBuffer(buff);

        reset(true);

    }



}
