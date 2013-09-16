//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NestedGenerator extends AbstractGenerator
{
    private static final Logger LOG = Log.getLogger(NestedGenerator.class);

    final HttpServletResponse _response;
    final String _nestedIn;
    
    public NestedGenerator(Buffers buffers, EndPoint io, HttpServletResponse response, String nestedIn)
    {
        super(buffers,io);
        _response=response;
        _nestedIn=nestedIn;
    }

    public void addContent(Buffer content, boolean last) throws IOException
    {
        LOG.debug("addContent {} {}",content.length(),last);
        if (_noContent)
        {
            content.clear();
            return;
        }

        if (content.isImmutable())
            throw new IllegalArgumentException("immutable");

        if (_last || _state == STATE_END)
        {
            LOG.debug("Ignoring extra content {}", content);
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
        else if (!last || _buffer!=null)
        {
            // Yes - so we better check we have a buffer
            initBuffer();
            // Copy _content to buffer;
            int len = 0;
            len = _buffer.put(_content);

            // make sure there is space for a trailing null   (???)
            if (len > 0 && _buffer.space() == 0)
            {
                len--;
                _buffer.setPutIndex(_buffer.putIndex() - 1);
            }
            
            LOG.debug("copied {} to buffer",len);

            _content.skip(len);

            if (_content.length() == 0)
                _content = null;
        }
    }

    public boolean addContent(byte b) throws IOException
    {
        // LOG.debug("addContent 1");
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
        initBuffer();

        // Copy _content to buffer;

        _buffer.put(b);

        return _buffer.space() <= 1;
    }

    /* ------------------------------------------------------------ */
    private void initBuffer() throws IOException
    {
        if (_buffer == null)
        {
            // LOG.debug("initContent");
            _buffer = _buffers.getBuffer();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isRequest()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isResponse()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int prepareUncheckedAddContent() throws IOException
    {
        initBuffer();
        return _buffer.space();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("completeHeader: {}",fields.toString().trim().replace("\r\n","|"));
        if (_state != STATE_HEADER)
            return;

        if (_last && !allContentAdded)
            throw new IllegalStateException("last?");
        _last = _last | allContentAdded;

        if (_persistent==null)
            _persistent=(_version > HttpVersions.HTTP_1_0_ORDINAL);


        if (_reason == null)
            _response.setStatus(_status);
        else
            _response.setStatus(_status,_reason.toString());

        if (_status == 100 || _status == 204 || _status == 304)
        {
            _noContent = true;
            _content = null;
        }


        boolean has_server = false;
        if (fields != null)
        {
            // Add headers
            int s=fields.size();
            for (int f=0;f<s;f++)
            {
                HttpFields.Field field = fields.getField(f);
                if (field==null)
                    continue;
                _response.setHeader(field.getName(),field.getValue());
            }
        }

        if (!has_server && _status > 100 && getSendServerVersion())
            _response.setHeader(HttpHeaders.SERVER,"Jetty("+Server.getVersion()+",nested in "+_nestedIn+")");

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
            _state = STATE_FLUSHING;

        flushBuffer();
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flushBuffer() throws IOException
    {
        if (_state == STATE_HEADER)
            throw new IllegalStateException("State==HEADER");
        
        int len = 0;
        
        if (_buffer==null)
        {
            
            if (_content!=null && _content.length()>0)
            {
                // flush content directly
                len = _endp.flush(_content);
                if (len>0)
                    _content.skip(len);
            }
        }
        else
        {
            if (_buffer.length()==0 && _content!=null && _content.length()>0)
            {
                // Copy content to buffer
                _content.skip(_buffer.put(_content));
            }

            int size=_buffer.length();
            len =_endp.flush(_buffer);
            LOG.debug("flushBuffer {} of {}",len,size);
            if (len>0)
                _buffer.skip(len);
        }
        
        if (_content!=null && _content.length()==0)
            _content=null;
        if (_buffer!=null && _buffer.length()==0 && _content==null)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
        
        if (_state==STATE_FLUSHING && _buffer==null && _content==null)
            _state=STATE_END;

        return len;
    }

}
