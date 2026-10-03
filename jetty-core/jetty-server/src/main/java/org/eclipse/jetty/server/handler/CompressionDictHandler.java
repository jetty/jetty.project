//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.regex.PatternSyntaxException;

import com.github.luben.zstd.ZstdOutputStream;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.CompressionDictionary;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class CompressionDictHandler extends Handler.Abstract
{
    private CompressionDictionary dict;
    private PathMatcher pattern;
    private BrotliCompression bc = new BrotliCompression();
    private byte[] testPage;
    private static final byte[] ZSTD_MAGIC_NUMBER = {0x5e, 0x2a, 0x4d, 0x18, 0x20, 0x00, 0x00, 0x00};
    private static final byte[] BR_MAGIC_NUMBER = {(byte)0xff, 0x44, 0x43, 0x42};
    
    public CompressionDictHandler(CompressionDictionary dict, byte[] testPage) throws PatternSyntaxException, IOException
    {
        this.dict = dict;
        this.testPage = testPage;
        // It is the best matches URLPattern behavior; 
        FileSystem fs = FileSystems.getDefault();
        this.pattern = fs.getPathMatcher("glob:**" + dict.getMatch());
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (pattern.matches(Paths.get(request.getHttpURI().getPath())) && 
            !request.getHeaders().contains("Available-Dictionary") && 
            request.getMethod() == HttpMethod.GET.toString())
        {
            try (ByteArrayOutputStream buff = new ByteArrayOutputStream())
            {
                ByteBuffer compressedData = compressDataBrotli(dict.getDictionary().array());
                String match = String.format("match=\"%s\"", dict.getMatch());
                response.getHeaders().add("Use-As-Dictionary", match);
                response.getHeaders().add("Content-Encoding", "br");
                response.getHeaders().add("Content-type", "text/html");   
                response.getHeaders().add("Cache-Control", "public, max-age=31536000");
                response.write(true, compressedData, callback);
                callback.succeeded();
                return true;
            } 
            catch (Exception e)
            {
                e.printStackTrace();
                Response.writeError(request, response, callback, 0);
                return true;
            }
        }
        else
        {
            try
            {
                if (pattern.matches(Paths.get(request.getHttpURI().getPath())) &&
                    dict.getDictDigestBase64().equals(getClientDictHash(request)))
                {   
                    String dictType = dict.getType();
                    switch (dictType)
                    {
                        case "dcz":
                            ByteBuffer compressedData = compressDataDcz(dict, testPage);
                            response.getHeaders().add("Content-Encoding", dictType);
                            response.getHeaders().add("Content-type", "text/html");
                            response.write(true, compressedData, callback);
                            break;
                        case "dcb":
                            compressDataBcz(dict, BR_MAGIC_NUMBER);
                            break;
                        default:
                            response.getHeaders().add("Content-Encoding", "br");
                            response.getHeaders().add("Content-type", "text/html");
                            response.write(true, ByteBuffer.wrap(testPage), callback);
                            break;                
                     }
                    callback.succeeded();
                    return true;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Response.writeError(request, response, callback, 0);
                return true;
            }
        }
        return false;
    }
    
    private String getClientDictHash(Request request)
    {
        return request.getHeaders().get("Available-Dictionary").replaceAll(":", "");
    }
    
    private ByteBuffer compressDataDcz(CompressionDictionary dictionary, byte[] data) throws IOException
    {
        try (ByteArrayOutputStream buff = new ByteArrayOutputStream();)
        {
            buff.write(ZSTD_MAGIC_NUMBER);
            buff.write(dict.getDictDigest());
            ZstdOutputStream compressor = new ZstdOutputStream(buff);
            compressor.setDict(dictionary.getDictionary().array());
            compressor.write(data);
            compressor.close();
            return ByteBuffer.wrap(buff.toByteArray());
        }
    }
    
    // TODO
    private ByteBuffer compressDataBcz(CompressionDictionary dictionary, byte[] data)
    {
        return null;
    }
    
    private ByteBuffer compressDataBrotli(byte[] data) throws IOException
    {
        try (ByteArrayOutputStream buff = new ByteArrayOutputStream())
        {
            OutputStream compressor = bc.newEncoderOutputStream(buff);
            compressor.write(data);
            compressor.close();
            return ByteBuffer.wrap(buff.toByteArray());
        }
    }
}
