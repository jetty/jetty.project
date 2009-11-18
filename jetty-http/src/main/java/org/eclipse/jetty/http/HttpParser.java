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

package org.eclipse.jetty.http;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------------------------- */
/**
 * 
 */
public class HttpParser implements Parser
{
    // States
    public static final int STATE_START=-13;
    public static final int STATE_FIELD0=-12;
    public static final int STATE_SPACE1=-11;
    public static final int STATE_FIELD1=-10;
    public static final int STATE_SPACE2=-9;
    public static final int STATE_END0=-8;
    public static final int STATE_END1=-7;
    public static final int STATE_FIELD2=-6;
    public static final int STATE_HEADER=-5;
    public static final int STATE_HEADER_NAME=-4;
    public static final int STATE_HEADER_IN_NAME=-3;
    public static final int STATE_HEADER_VALUE=-2;
    public static final int STATE_HEADER_IN_VALUE=-1;
    public static final int STATE_END=0;
    public static final int STATE_EOF_CONTENT=1;
    public static final int STATE_CONTENT=2;
    public static final int STATE_CHUNKED_CONTENT=3;
    public static final int STATE_CHUNK_SIZE=4;
    public static final int STATE_CHUNK_PARAMS=5;
    public static final int STATE_CHUNK=6;

    private final EventHandler _handler;
    private final Buffers _buffers; // source of buffers
    private final EndPoint _endp;
    private Buffer _header; // Buffer for header data (and small _content)
    private Buffer _body; // Buffer for large content
    private Buffer _buffer; // The current buffer in use (either _header or _content)
    private final View  _contentView=new View(); // View of the content in the buffer for {@link Input}
    private CachedBuffer _cached;
    private View.CaseInsensitive _tok0; // Saved token: header name, request method or response version
    private View.CaseInsensitive _tok1; // Saved token: header value, request URI or response code
    private String _multiLineValue;
    private int _responseStatus; // If >0 then we are parsing a response
    private boolean _forceContentBuffer;
    
    /* ------------------------------------------------------------------------------- */
    protected int _state=STATE_START;
    protected byte _eol;
    protected int _length;
    protected long _contentLength;
    protected long _contentPosition;
    protected int _chunkLength;
    protected int _chunkPosition;
    
    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     */
    public HttpParser(Buffer buffer, EventHandler handler)
    {
        _endp=null;
        _buffers=null;
        _header=buffer;
        _buffer=buffer;
        _handler=handler;

        if (buffer != null)
        {
            _tok0=new View.CaseInsensitive(buffer);
            _tok1=new View.CaseInsensitive(buffer);
            _tok0.setPutIndex(_tok0.getIndex());
            _tok1.setPutIndex(_tok1.getIndex());
        }
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     * @param headerBufferSize size in bytes of header buffer  
     * @param contentBufferSize size in bytes of content buffer
     */
    public HttpParser(Buffers buffers, EndPoint endp, EventHandler handler)
    {
        _buffers=buffers;
        _endp=endp;
        _handler=handler;
    }

    /* ------------------------------------------------------------------------------- */
    public long getContentLength()
    {
        return _contentLength;
    }
    
    public long getContentRead()
    {
        return _contentPosition;
    }

    /* ------------------------------------------------------------------------------- */
    public int getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state > 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state < 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _contentLength==HttpTokens.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return isState(STATE_START);
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return isState(STATE_END);
    }
    
    /* ------------------------------------------------------------ */
    public boolean isMoreInBuffer()
    throws IOException
    {
        return ( _header!=null && _header.hasContent() ||
             _body!=null && _body.hasContent());
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(int state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until {@link #STATE_END END} state.
     * If the parser is already in the END state, then it is {@link #reset reset} and re-parsed.
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public void parse() throws IOException
    {
        if (_state==STATE_END)
            reset(false);
        if (_state!=STATE_START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (_state != STATE_END)
            parseNext();
    }
    
    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until END state.
     * This method will parse any remaining content in the current buffer. It does not care about the 
     * {@link #getState current state} of the parser.
     * @see #parse
     * @see #parseNext
     */
    public long parseAvailable() throws IOException
    {
        long len = parseNext();
        long total=len>0?len:0;
        
        // continue parsing
        while (!isComplete() && _buffer!=null && _buffer.length()>0)
        {
            len = parseNext();
            if (len>0)
                total+=len;
        }
        return total;
    }


    
    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @returns number of bytes filled from endpoint or -1 if fill never called.
     */
    public long parseNext() throws IOException
    {
        long total_filled=-1;

        if (_state == STATE_END) 
            return -1;
        
        if (_buffer==null)
        {
            if (_header == null)
            {
                _header=_buffers.getHeader();
            }
            _buffer=_header;
            _tok0=new View.CaseInsensitive(_header);
            _tok1=new View.CaseInsensitive(_header);
            _tok0.setPutIndex(_tok0.getIndex());
            _tok1.setPutIndex(_tok1.getIndex());
        }
        
        
        if (_state == STATE_CONTENT && _contentPosition == _contentLength)
        {
            _state=STATE_END;
            _handler.messageComplete(_contentPosition);
            return total_filled;
        }
        
        int length=_buffer.length();
        
        // Fill buffer if we can
        if (length == 0)
        {
            int filled=-1;
            if (_body!=null && _buffer!=_body)
            {
                _buffer=_body;
                filled=_buffer.length();
            }
                
            if (_buffer.markIndex() == 0 && _buffer.putIndex() == _buffer.capacity())
                    throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413, "FULL");
            
            IOException ioex=null;
            
            if (_endp != null && filled<=0)
            {
                // Compress buffer if handling _content buffer
                // TODO check this is not moving data too much
                if (_buffer == _body) 
                    _buffer.compact();

                if (_buffer.space() == 0) 
                    throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413, "FULL "+(_buffer==_body?"body":"head"));                
                try
                {
                    if (total_filled<0)
                        total_filled=0;
                    filled=_endp.fill(_buffer);
                    if (filled>0)
                        total_filled+=filled;
                }
                catch(IOException e)
                {
                    Log.debug(e);
                    ioex=e;
                    filled=-1;
                }
            }

            if (filled < 0) 
            {
                if ( _state == STATE_EOF_CONTENT)
                {
                    if (_buffer.length()>0)
                    {
                        // TODO should we do this here or fall down to main loop?
                        Buffer chunk=_buffer.get(_buffer.length());
                        _contentPosition += chunk.length();
                        _contentView.update(chunk);
                        _handler.content(chunk); // May recurse here 
                    }
                    _state=STATE_END;
                    _handler.messageComplete(_contentPosition);
                    return total_filled;
                }
                reset(true);
                throw new EofException(ioex);
            }
            length=_buffer.length();
        }

        
        // EventHandler header
        byte ch;
        byte[] array=_buffer.array();
        
        while (_state<STATE_END && length-->0)
        {
            ch=_buffer.get();
            
            if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
            {
                _eol=HttpTokens.LINE_FEED;
                continue;
            }
            _eol=0;
            
            switch (_state)
            {
                case STATE_START:
                    _contentLength=HttpTokens.UNKNOWN_CONTENT;
                    _cached=null;
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        _buffer.mark();
                        _state=STATE_FIELD0;
                    }
                    break;

                case STATE_FIELD0:
                    if (ch == HttpTokens.SPACE)
                    {
                        _tok0.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                        _state=STATE_SPACE1;
                        continue;
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);
                    }
                    break;

                case STATE_SPACE1:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        _buffer.mark();
                        _state=STATE_FIELD1;
                    }
                    else if (ch < HttpTokens.SPACE)
                    {
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);
                    }
                    break;

                case STATE_FIELD1:
                    if (ch == HttpTokens.SPACE)
                    {
                        _tok1.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                        _state=STATE_SPACE2;
                        continue;
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        // HTTP/0.9
                        _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _buffer
                                .sliceFromMark(), null);
                        _state=STATE_END;
                        _handler.headerComplete();
                        _handler.messageComplete(_contentPosition);
                        return total_filled;
                    }
                    break;

                case STATE_SPACE2:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        _buffer.mark();
                        _state=STATE_FIELD2;
                    }
                    else if (ch < HttpTokens.SPACE)
                    {
                        // HTTP/0.9
                        _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _tok1, null);
                        _state=STATE_END;
                        _handler.headerComplete();
                        _handler.messageComplete(_contentPosition);
                        return total_filled;
                    }
                    break;

                case STATE_FIELD2:
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                    {

                        // TODO - we really should know if we are parsing request or response!
                        final Buffer method = HttpMethods.CACHE.lookup(_tok0);
                        if (method==_tok0 && _tok1.length()==3 && Character.isDigit(_tok1.peek()))
                        {
                            _responseStatus = BufferUtil.toInt(_tok1);
                            _handler.startResponse(HttpVersions.CACHE.lookup(_tok0), _responseStatus,_buffer.sliceFromMark());
                        }
                        else
                            _handler.startRequest(method, _tok1,HttpVersions.CACHE.lookup(_buffer.sliceFromMark()));
                        _eol=ch;
                        _state=STATE_HEADER;
                        _tok0.setPutIndex(_tok0.getIndex());
                        _tok1.setPutIndex(_tok1.getIndex());
                        _multiLineValue=null;
                        continue;
                    }
                    break;

                case STATE_HEADER:
                    switch(ch)
                    {
                        case HttpTokens.COLON:
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                        {
                            // header value without name - continuation?
                            _length=-1;
                            _state=STATE_HEADER_VALUE;
                            break;
                        }
                        
                        default:
                        {
                            // handler last header if any
                            if (_cached!=null || _tok0.length() > 0 || _tok1.length() > 0 || _multiLineValue != null)
                            {
                                
                                Buffer header=_cached!=null?_cached:HttpHeaders.CACHE.lookup(_tok0);
                                _cached=null;
                                Buffer value=_multiLineValue == null ? _tok1 : new ByteArrayBuffer(_multiLineValue);
                                
                                int ho=HttpHeaders.CACHE.getOrdinal(header);
                                if (ho >= 0)
                                {
                                    int vo; 
                                    
                                    switch (ho)
                                    {
                                        case HttpHeaders.CONTENT_LENGTH_ORDINAL:
                                            if (_contentLength != HttpTokens.CHUNKED_CONTENT)
                                            {
                                                try
                                                {
                                                    _contentLength=BufferUtil.toLong(value);
                                                }
                                                catch(NumberFormatException e)
                                                {
                                                    Log.ignore(e);
                                                    throw new HttpException(HttpStatus.BAD_REQUEST_400);
                                                }
                                                if (_contentLength <= 0)
                                                    _contentLength=HttpTokens.NO_CONTENT;
                                            }
                                            break;
                                            
                                        case HttpHeaders.TRANSFER_ENCODING_ORDINAL:
                                            value=HttpHeaderValues.CACHE.lookup(value);
                                            vo=HttpHeaderValues.CACHE.getOrdinal(value);
                                            if (HttpHeaderValues.CHUNKED_ORDINAL == vo)
                                                _contentLength=HttpTokens.CHUNKED_CONTENT;
                                            else
                                            {
                                                String c=value.toString(StringUtil.__ISO_8859_1);
                                                if (c.endsWith(HttpHeaderValues.CHUNKED))
                                                    _contentLength=HttpTokens.CHUNKED_CONTENT;
                                                
                                                else if (c.indexOf(HttpHeaderValues.CHUNKED) >= 0)
                                                    throw new HttpException(400,null);
                                            }
                                            break;
                                    }
                                }
                                
                                _handler.parsedHeader(header, value);
                                _tok0.setPutIndex(_tok0.getIndex());
                                _tok1.setPutIndex(_tok1.getIndex());
                                _multiLineValue=null;
                            }
                            
                            
                            // now handle ch
                            if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                            {
                                // End of header

                                // work out the _content demarcation
                                if (_contentLength == HttpTokens.UNKNOWN_CONTENT)
                                {
                                    if (_responseStatus == 0  // request
                                    || _responseStatus == 304 // not-modified response
                                    || _responseStatus == 204 // no-content response
                                    || _responseStatus < 200) // 1xx response
                                        _contentLength=HttpTokens.NO_CONTENT;
                                    else
                                        _contentLength=HttpTokens.EOF_CONTENT;
                                }

                                _contentPosition=0;
                                _eol=ch;
                                if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                    _eol=_buffer.get();
                                
                                // We convert _contentLength to an int for this switch statement because
                                // we don't care about the amount of data available just whether there is some.
                                switch (_contentLength > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) _contentLength)
                                {
                                    case HttpTokens.EOF_CONTENT:
                                        _state=STATE_EOF_CONTENT;
                                        if(_body==null && _buffers!=null)
                                            _body=_buffers.getBuffer();
                                        
                                        _handler.headerComplete(); // May recurse here !
                                        break;
                                        
                                    case HttpTokens.CHUNKED_CONTENT:
                                        _state=STATE_CHUNKED_CONTENT;
                                        if (_body==null && _buffers!=null)
                                            _body=_buffers.getBuffer();
                                        _handler.headerComplete(); // May recurse here !
                                        break;
                                        
                                    case HttpTokens.NO_CONTENT:
                                        _state=STATE_END;
                                        _handler.headerComplete(); 
                                        _handler.messageComplete(_contentPosition);
                                        break;
                                        
                                    default:
                                        _state=STATE_CONTENT;
                                        if(_forceContentBuffer || 
                                          (_buffers!=null && _body==null && _buffer==_header && _contentLength>=(_header.capacity()-_header.getIndex())))
                                            _body=_buffers.getBuffer();
                                        _handler.headerComplete(); // May recurse here !
                                        break;
                                }
                                return total_filled;
                            }
                            else
                            {
                                // New header
                                _length=1;
                                _buffer.mark();
                                _state=STATE_HEADER_NAME;
                                
                                // try cached name!
                                if (array!=null)
                                {
                                    _cached=HttpHeaders.CACHE.getBest(array, _buffer.markIndex(), length+1);

                                    if (_cached!=null)
                                    {
                                        _length=_cached.length();
                                        _buffer.setGetIndex(_buffer.markIndex()+_length);
                                        length=_buffer.length();
                                    }
                                }
                            } 
                        }
                    }
                    
                    break;

                case STATE_HEADER_NAME:
                    switch(ch)
                    {
                        case HttpTokens.CARRIAGE_RETURN:
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                            _eol=ch;
                            _state=STATE_HEADER;
                            break;
                        case HttpTokens.COLON:
                            if (_length > 0 && _cached==null)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                            _length=-1;
                            _state=STATE_HEADER_VALUE;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            break;
                        default: 
                        {
                            _cached=null;
                            if (_length == -1) 
                                _buffer.mark();
                            _length=_buffer.getIndex() - _buffer.markIndex();
                            _state=STATE_HEADER_IN_NAME;  
                        }
                    }
     
                    break;

                case STATE_HEADER_IN_NAME:
                    switch(ch)
                    {
                        case HttpTokens.CARRIAGE_RETURN:
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                            _eol=ch;
                            _state=STATE_HEADER;
                            break;
                        case HttpTokens.COLON:
                            if (_length > 0 && _cached==null)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                            _length=-1;
                            _state=STATE_HEADER_VALUE;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _state=STATE_HEADER_NAME;
                            break;
                        default:
                        {
                            _cached=null;
                            _length++;
                        }
                    }
                    break;

                case STATE_HEADER_VALUE:
                    switch(ch)
                    {
                        case HttpTokens.CARRIAGE_RETURN:
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                            {
                                if (_tok1.length() == 0)
                                    _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                else
                                {
                                    // Continuation line!
                                    if (_multiLineValue == null) _multiLineValue=_tok1.toString(StringUtil.__ISO_8859_1);
                                    _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                    _multiLineValue += " " + _tok1.toString(StringUtil.__ISO_8859_1);
                                }
                            }
                            _eol=ch;
                            _state=STATE_HEADER;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            break;
                        default:
                        {
                            if (_length == -1) 
                                _buffer.mark();
                            _length=_buffer.getIndex() - _buffer.markIndex();
                            _state=STATE_HEADER_IN_VALUE;
                        }       
                    }
                    break;

                case STATE_HEADER_IN_VALUE:
                    switch(ch)
                    {
                        case HttpTokens.CARRIAGE_RETURN:
                        case HttpTokens.LINE_FEED:
                            if (_length > 0)
                            {
                                if (_tok1.length() == 0)
                                    _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                else
                                {
                                    // Continuation line!
                                    if (_multiLineValue == null) _multiLineValue=_tok1.toString(StringUtil.__ISO_8859_1);
                                    _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                    _multiLineValue += " " + _tok1.toString(StringUtil.__ISO_8859_1);
                                }
                            }
                            _eol=ch;
                            _state=STATE_HEADER;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _state=STATE_HEADER_VALUE;
                            break;
                        default:
                            _length++;
                    }
                    break;
            }
        } // end of HEADER states loop
        
        // ==========================
        
        // Handle _content
        length=_buffer.length();
        Buffer chunk; 
        while (_state > STATE_END && length > 0)
        {
            if (_eol == HttpTokens.CARRIAGE_RETURN && _buffer.peek() == HttpTokens.LINE_FEED)
            {
                _eol=_buffer.get();
                length=_buffer.length();
                continue;
            }
            _eol=0;
            switch (_state)
            {
                case STATE_EOF_CONTENT:
                    chunk=_buffer.get(_buffer.length());
                    _contentPosition += chunk.length();
                    _contentView.update(chunk);
                    _handler.content(chunk); // May recurse here 
                    // TODO adjust the _buffer to keep unconsumed content
                    return total_filled;

                case STATE_CONTENT: 
                {
                    long remaining=_contentLength - _contentPosition;
                    if (remaining == 0)
                    {
                        _state=STATE_END;
                        _handler.messageComplete(_contentPosition);
                        return total_filled;
                    }
                    
                    if (length > remaining) 
                    {
                        // We can cast reamining to an int as we know that it is smaller than
                        // or equal to length which is already an int. 
                        length=(int)remaining;
                    }
                    
                    chunk=_buffer.get(length);
                    _contentPosition += chunk.length();
                    _contentView.update(chunk);
                    _handler.content(chunk); // May recurse here 
                    
                    if(_contentPosition == _contentLength)
                    {
                        _state=STATE_END;
                        _handler.messageComplete(_contentPosition);
                    }                    
                    // TODO adjust the _buffer to keep unconsumed content
                    return total_filled;
                }

                case STATE_CHUNKED_CONTENT:
                {
                    ch=_buffer.peek();
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        _eol=_buffer.get();
                    else if (ch <= HttpTokens.SPACE)
                        _buffer.get();
                    else
                    {
                        _chunkLength=0;
                        _chunkPosition=0;
                        _state=STATE_CHUNK_SIZE;
                    }
                    break;
                }

                case STATE_CHUNK_SIZE:
                {
                    ch=_buffer.get();
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                    {
                        _eol=ch;
                        
                        if (_chunkLength == 0)
                        {
                            if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                _eol=_buffer.get();
                            _state=STATE_END;
                            _handler.messageComplete(_contentPosition);
                            return total_filled;
                        }
                        else
                            _state=STATE_CHUNK;
                    }
                    else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
                        _state=STATE_CHUNK_PARAMS;
                    else if (ch >= '0' && ch <= '9')
                        _chunkLength=_chunkLength * 16 + (ch - '0');
                    else if (ch >= 'a' && ch <= 'f')
                        _chunkLength=_chunkLength * 16 + (10 + ch - 'a');
                    else if (ch >= 'A' && ch <= 'F')
                        _chunkLength=_chunkLength * 16 + (10 + ch - 'A');
                    else
                        throw new IOException("bad chunk char: " + ch);
                    break;
                }

                case STATE_CHUNK_PARAMS:
                {
                    ch=_buffer.get();
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                    {
                        _eol=ch;
                        if (_chunkLength == 0)
                        {
                            if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                _eol=_buffer.get();
                            _state=STATE_END;
                            _handler.messageComplete(_contentPosition);
                            return total_filled;
                        }
                        else
                            _state=STATE_CHUNK;
                    }
                    break;
                }
                
                case STATE_CHUNK: 
                {
                    int remaining=_chunkLength - _chunkPosition;
                    if (remaining == 0)
                    {
                        _state=STATE_CHUNKED_CONTENT;
                        break;
                    }
                    else if (length > remaining) 
                        length=remaining;
                    chunk=_buffer.get(length);
                    _contentPosition += chunk.length();
                    _chunkPosition += chunk.length();
                    _contentView.update(chunk);
                    _handler.content(chunk); // May recurse here 
                    // TODO adjust the _buffer to keep unconsumed content
                    return total_filled;
                }
            }

            length=_buffer.length();
        }
        return total_filled;
    }

    /* ------------------------------------------------------------------------------- */
    /** fill the buffers from the endpoint
     * 
     */
    public long fill() throws IOException
    {
        if (_buffer==null)
        {
            _buffer=_header=getHeaderBuffer();
            _tok0=new View.CaseInsensitive(_buffer);
            _tok1=new View.CaseInsensitive(_buffer);
        }
        if (_body!=null && _buffer!=_body)
            _buffer=_body;
        if (_buffer == _body)
            //noinspection ConstantConditions
            _buffer.compact();
        
        int space=_buffer.space();
        
        // Fill buffer if we can
        if (space == 0) 
            throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413, "FULL "+(_buffer==_body?"body":"head"));
        else
        {
            int filled=-1;
            
            if (_endp != null )
            {
                try
                {
                    filled=_endp.fill(_buffer);
                }
                catch(IOException e)
                {
                    Log.debug(e);
                    reset(true);
                    throw (e instanceof EofException) ? e:new EofException(e);
                }
            }
            
            return filled;
        }
    }

    /* ------------------------------------------------------------------------------- */
    /** Skip any CRLFs in buffers
     * 
     */
    public void skipCRLF()
    {

        while (_header!=null && _header.length()>0)
        {
            byte ch = _header.peek();
            if (ch==HttpTokens.CARRIAGE_RETURN || ch==HttpTokens.LINE_FEED)
            {
                _eol=ch;
                _header.skip(1);
            }
            else
                break;
        }

        while (_body!=null && _body.length()>0)
        {
            byte ch = _body.peek();
            if (ch==HttpTokens.CARRIAGE_RETURN || ch==HttpTokens.LINE_FEED)
            {
                _eol=ch;
                _body.skip(1);
            }
            else
                break;
        }
        
    }
    /* ------------------------------------------------------------------------------- */
    public void reset(boolean returnBuffers)
    {   
        _contentView.setGetIndex(_contentView.putIndex());

        _state=STATE_START;
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
        _contentPosition=0;
        _length=0;
        _responseStatus=0;

        if (_eol == HttpTokens.CARRIAGE_RETURN && _buffer!=null && _buffer.hasContent() && _buffer.peek() == HttpTokens.LINE_FEED)
            _eol=_buffer.get();

        if (_body!=null)
        {   
            if (_body.hasContent())
            {
                // There is content in the body after the end of the request.
                // This is probably a pipelined header of the next request, so we need to
                // copy it to the header buffer.
                _header.setMarkIndex(-1);
                _header.compact();
                int take=_header.space();
                if (take>_body.length())
                    take=_body.length();
                _body.peek(_body.getIndex(),take);
                _body.skip(_header.put(_body.peek(_body.getIndex(),take)));
            }

            if (_body.length()==0)
            {
                if (_buffers!=null && returnBuffers)
                    _buffers.returnBuffer(_body);
                _body=null; 
            }
            else
            {
                _body.setMarkIndex(-1);
                _body.compact();
            }
        }

        if (_header!=null)
        {
            _header.setMarkIndex(-1);
            if (!_header.hasContent() && _buffers!=null && returnBuffers)
            {
                _buffers.returnBuffer(_header);
                _header=null;
            }   
            else
            {
                _header.compact();
                _tok0.update(_header);
                _tok0.update(0,0);
                _tok1.update(_header);
                _tok1.update(0,0);
            }
        }

        _buffer=_header;
    }

    /* ------------------------------------------------------------------------------- */
    public void setState(int state)
    {
        this._state=state;
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    public String toString(Buffer buf)
    {
        return "state=" + _state + " length=" + _length + " buf=" + buf.hashCode();
    }
    
    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return "state=" + _state + " length=" + _length + " len=" + _contentLength;
    }    

    /* ------------------------------------------------------------ */
    public Buffer getHeaderBuffer()
    {
        if (_header == null)
        {
            _header=_buffers.getHeader();
        }
        return _header;
    }
    
    /* ------------------------------------------------------------ */
    public Buffer getBodyBuffer()
    {
        return _body;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param force True if a new buffer will be forced to be used for content and the header buffer will not be used.
     */
    public void setForceContentBuffer(boolean force)
    {
        _forceContentBuffer=force;
    } 
    

    
    /* ------------------------------------------------------------ */
    public Buffer blockForContent(long maxIdleTime) throws IOException
    {
        if (_contentView.length()>0)
            return _contentView;
        if (getState() <= HttpParser.STATE_END) 
            return null;
        
        // Handle simple end points.
        if (_endp==null)
            parseNext();
        
        // Handle blocking end points
        else if (_endp.isBlocking())
        {
            try
            {
                parseNext();

                // parse until some progress is made (or IOException thrown for timeout)
                while(_contentView.length() == 0 && !isState(HttpParser.STATE_END) && _endp.isOpen())
                {
                    // Try to get more _parser._content
                    parseNext();
                }
            }
            catch(IOException e)
            {
                _endp.close();
                throw e;
            }
        }
        else // Handle non-blocking end point
        {
            parseNext();
            
            // parse until some progress is made (or IOException thrown for timeout)
            while(_contentView.length() == 0 && !isState(HttpParser.STATE_END) && _endp.isOpen())
            {
                if (_endp.isBufferingInput() && parseNext()>0)
                    continue;
                
                if (!_endp.blockReadable(maxIdleTime))
                {
                    _endp.close();
                    throw new EofException("timeout");
                }

                // Try to get more _parser._content
                parseNext();
            }
        }
        
        return _contentView.length()>0?_contentView:null; 
    }   

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see java.io.InputStream#available()
     */
    public int available() throws IOException
    {
        if (_contentView!=null && _contentView.length()>0)
            return _contentView.length();
        if (!_endp.isBlocking())
            parseNext();
        
        return _contentView==null?0:_contentView.length();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static abstract class EventHandler
    {
        public abstract void content(Buffer ref) throws IOException;

        public void headerComplete() throws IOException
        {
        }

        public void messageComplete(long contentLength) throws IOException
        {
        }

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         */
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
        }

        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startRequest(Buffer method, Buffer url, Buffer version)
                throws IOException;
        
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startResponse(Buffer version, int status, Buffer reason)
                throws IOException;
    }



    
}
