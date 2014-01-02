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

package org.eclipse.jetty.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.util.StringUtil;

/**
 * A exchange that retains response content for later use.
 */
public class ContentExchange extends CachedExchange
{
    private int _bufferSize = 4096;
    private String _encoding = "utf-8";
    private ByteArrayOutputStream _responseContent;
    private File _fileForUpload;

    public ContentExchange()
    {
        super(false);
    }

    public ContentExchange(boolean cacheFields)
    {
        super(cacheFields);
    }

    public synchronized String getResponseContent() throws UnsupportedEncodingException
    {
        if (_responseContent != null)
            return _responseContent.toString(_encoding);
        return null;
    }

    public synchronized byte[] getResponseContentBytes()
    {
        if (_responseContent != null)
            return _responseContent.toByteArray();
        return null;
    }

    @Override
    protected synchronized void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
        if (_responseContent!=null)
            _responseContent.reset();
        super.onResponseStatus(version,status,reason);
    }

    @Override
    protected synchronized void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
        super.onResponseHeader(name, value);
        int header = HttpHeaders.CACHE.getOrdinal(name);
        switch (header)
        {
            case HttpHeaders.CONTENT_LENGTH_ORDINAL:
                _bufferSize = BufferUtil.toInt(value);
                break;
            case HttpHeaders.CONTENT_TYPE_ORDINAL:
                String mime = StringUtil.asciiToLowerCase(value.toString());
                int i = mime.indexOf("charset=");
                if (i > 0)
                {
                    _encoding = mime.substring(i + 8);
                    i = _encoding.indexOf(';');
                    if (i > 0)
                        _encoding = _encoding.substring(0, i);
                }
                break;
        }
    }

    @Override
    protected synchronized void onResponseContent(Buffer content) throws IOException
    {
        super.onResponseContent(content);
        if (_responseContent == null)
            _responseContent = new ByteArrayOutputStream(_bufferSize);
        content.writeTo(_responseContent);
    }

    @Override
    protected synchronized void onRetry() throws IOException
    {
        if (_fileForUpload != null)
        {
            setRequestContent(null);
            setRequestContentSource(getInputStream());
        }
        else
            super.onRetry();
    }

    private synchronized InputStream getInputStream() throws IOException
    {
        return new FileInputStream(_fileForUpload);
    }

    public synchronized File getFileForUpload()
    {
        return _fileForUpload;
    }

    public synchronized void setFileForUpload(File fileForUpload) throws IOException
    {
        this._fileForUpload = fileForUpload;
        setRequestContentSource(getInputStream());
    }
}
