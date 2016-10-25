//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Part;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * MultiPartInputStream
 *
 * Handle a MultiPart Mime input stream, breaking it up on the boundary into files and strings.
 */
public class MultiPartInputStreamParser
{
    private static final Logger LOG = Log.getLogger(MultiPartInputStreamParser.class);
    public static final MultipartConfigElement  __DEFAULT_MULTIPART_CONFIG = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
    public static final MultiMap<Part> EMPTY_MAP = new MultiMap(Collections.emptyMap());
    /**
     * The size of the chunks to read from the input stream.
     * <p>Intended to be edited only for testing.</p>
     *  
     */
    static int CHUNK_READ_SIZE = 4096;
    protected InputStream _in;
    protected MultipartConfigElement _config;
    protected String _contentType;
    protected MultiMap<Part> _parts;
    protected Exception _err;
    protected File _tmpDir;
    protected File _contextTmpDir;
    protected boolean _deleteOnExit;
    protected boolean _writeFilesWithFilenames;



    public class MultiPart implements Part
    {
        protected String _name;
        protected String _filename;
        protected File _file;
        protected OutputStream _out;
        protected ByteArrayOutputStream2 _bout;
        protected String _contentType;
        protected MultiMap<String> _headers;
        protected long _size = 0;
        protected boolean _temporary = true;

        public MultiPart (String name, String filename)
        throws IOException
        {
            _name = name;
            _filename = filename;
        }

        @Override
        public String toString()
        {
            return String.format("Part{n=%s,fn=%s,ct=%s,s=%d,t=%b,f=%s}",_name,_filename,_contentType,_size,_temporary,_file);
        }
        protected void setContentType (String contentType)
        {
            _contentType = contentType;
        }


        protected void open()
        throws IOException
        {
            //We will either be writing to a file, if it has a filename on the content-disposition
            //and otherwise a byte-array-input-stream, OR if we exceed the getFileSizeThreshold, we
            //will need to change to write to a file.
            if (isWriteFilesWithFilenames() && _filename != null && _filename.trim().length() > 0)
            {
                createFile();
            }
            else
            {
                //Write to a buffer in memory until we discover we've exceed the
                //MultipartConfig fileSizeThreshold
                _out = _bout= new ByteArrayOutputStream2();
            }
        }

        protected void close()
        throws IOException
        {
            _out.close();
        }


        protected void write (int b)
        throws IOException
        {
            if (MultiPartInputStreamParser.this._config.getMaxFileSize() > 0 && _size + 1 > MultiPartInputStreamParser.this._config.getMaxFileSize())
                throw new IllegalStateException ("Multipart Mime part "+_name+" exceeds max filesize");

            if (MultiPartInputStreamParser.this._config.getFileSizeThreshold() > 0 && _size + 1 > MultiPartInputStreamParser.this._config.getFileSizeThreshold() && _file==null)
                createFile();

            _out.write(b);
            _size ++;
        }

        protected void write (byte[] bytes, int offset, int length)
        throws IOException
        {
            if (MultiPartInputStreamParser.this._config.getMaxFileSize() > 0 && _size + length > MultiPartInputStreamParser.this._config.getMaxFileSize())
                throw new IllegalStateException ("Multipart Mime part "+_name+" exceeds max filesize");

            if (MultiPartInputStreamParser.this._config.getFileSizeThreshold() > 0 && _size + length > MultiPartInputStreamParser.this._config.getFileSizeThreshold() && _file==null)
                createFile();

            _out.write(bytes, offset, length);
            _size += length;
        }

        protected void createFile ()
        throws IOException
        {
            _file = File.createTempFile("MultiPart", "", MultiPartInputStreamParser.this._tmpDir);

            if (_deleteOnExit)
                _file.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(_file);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            if (_size > 0 && _out != null)
            {
                //already written some bytes, so need to copy them into the file
                _out.flush();
                _bout.writeTo(bos);
                _out.close();
            }
            _bout = null;
            _out = bos;
        }



        protected void setHeaders(MultiMap<String> headers)
        {
            _headers = headers;
        }

        /**
         * @see javax.servlet.http.Part#getContentType()
         */
        public String getContentType()
        {
            return _contentType;
        }

        /**
         * @see javax.servlet.http.Part#getHeader(java.lang.String)
         */
        public String getHeader(String name)
        {
            if (name == null)
                return null;
            return _headers.getValue(name.toLowerCase(Locale.ENGLISH), 0);
        }

        /**
         * @see javax.servlet.http.Part#getHeaderNames()
         */
        public Collection<String> getHeaderNames()
        {
            return _headers.keySet();
        }

        /**
         * @see javax.servlet.http.Part#getHeaders(java.lang.String)
         */
        public Collection<String> getHeaders(String name)
        {
           return _headers.getValues(name);
        }

        /**
         * @see javax.servlet.http.Part#getInputStream()
         */
        public InputStream getInputStream() throws IOException
        {
           if (_file != null)
           {
               //written to a file, whether temporary or not
               return new BufferedInputStream (new FileInputStream(_file));
           }
           else
           {
               //part content is in memory
               return new ByteArrayInputStream(_bout.getBuf(),0,_bout.size());
           }
        }


        /**
         * @see javax.servlet.http.Part#getSubmittedFileName()
         */
        @Override
        public String getSubmittedFileName()
        {
            return getContentDispositionFilename();
        }

        public byte[] getBytes()
        {
            if (_bout!=null)
                return _bout.toByteArray();
            return null;
        }

        /**
         * @see javax.servlet.http.Part#getName()
         */
        public String getName()
        {
           return _name;
        }

        /**
         * @see javax.servlet.http.Part#getSize()
         */
        public long getSize()
        {
            return _size;
        }

        /**
         * @see javax.servlet.http.Part#write(java.lang.String)
         */
        public void write(String fileName) throws IOException
        {
            if (_file == null)
            {
                _temporary = false;

                //part data is only in the ByteArrayOutputStream and never been written to disk
                _file = new File (_tmpDir, fileName);

                BufferedOutputStream bos = null;
                try
                {
                    bos = new BufferedOutputStream(new FileOutputStream(_file));
                    _bout.writeTo(bos);
                    bos.flush();
                }
                finally
                {
                    if (bos != null)
                        bos.close();
                    _bout = null;
                }
            }
            else
            {
                //the part data is already written to a temporary file, just rename it
                _temporary = false;

                Path src = _file.toPath();
                Path target = src.resolveSibling(fileName);
                Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                _file = target.toFile();
            }
        }

        /**
         * Remove the file, whether or not Part.write() was called on it
         * (ie no longer temporary)
         * @see javax.servlet.http.Part#delete()
         */
        public void delete() throws IOException
        {
            if (_file != null && _file.exists())
                _file.delete();
        }

        /**
         * Only remove tmp files.
         *
         * @throws IOException if unable to delete the file
         */
        public void cleanUp() throws IOException
        {
            if (_temporary && _file != null && _file.exists())
                _file.delete();
        }


        /**
         * Get the file
         * @return the file, if any, the data has been written to.
         */
        public File getFile ()
        {
            return _file;
        }


        /**
         * Get the filename from the content-disposition.
         * @return null or the filename
         */
        public String getContentDispositionFilename ()
        {
            return _filename;
        }
    }




    /**
     * @param in Request input stream
     * @param contentType Content-Type header
     * @param config MultipartConfigElement
     * @param contextTmpDir javax.servlet.context.tempdir
     */
    public MultiPartInputStreamParser (InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir)
    {
        _contentType = contentType;
        _config = config;
        _contextTmpDir = contextTmpDir;
        if (_contextTmpDir == null)
            _contextTmpDir = new File (System.getProperty("java.io.tmpdir"));

        if (_config == null)
            _config = new MultipartConfigElement(_contextTmpDir.getAbsolutePath());
        
        if (in instanceof ServletInputStream)
        {
            if (((ServletInputStream)in).isFinished())
            {
                _parts = EMPTY_MAP;
                return;
            }
        }
        _in = new ReadLineInputStream(in);
    }

    /**
     * Get the already parsed parts.
     * @return the parts that were parsed
     */
    public Collection<Part> getParsedParts()
    {
        if (_parts == null)
            return Collections.emptyList();

        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o: values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }

    /**
     * Delete any tmp storage for parts, and clear out the parts list.
     *
     * @throws MultiException if unable to delete the parts
     */
    public void deleteParts ()
    throws MultiException
    {
        Collection<Part> parts = getParsedParts();
        MultiException err = new MultiException();
        for (Part p:parts)
        {
            try
            {
                ((MultiPartInputStreamParser.MultiPart)p).cleanUp();
            }
            catch(Exception e)
            {
                err.add(e);
            }
        }
        _parts.clear();

        err.ifExceptionThrowMulti();
    }


    /**
     * Parse, if necessary, the multipart data and return the list of Parts.
     *
     * @return the parts
     * @throws IOException if unable to get the parts
     */
    public Collection<Part> getParts()
    throws IOException
    {
        parse();
        throwIfError();

        
        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o: values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }


    /**
     * Get the named Part.
     *
     * @param name the part name
     * @return the parts
     * @throws IOException if unable to get the part
     */
    public Part getPart(String name)
    throws IOException
    {
        parse();
        throwIfError();   
        return _parts.getValue(name, 0);
    }

    /**
     * Throws an exception if one has been latched.
     * 
     * @throws IOException the exception (if present)
     */
    protected void throwIfError ()
    throws IOException
    {
        if (_err != null)
        {
            if (_err instanceof IOException)
                throw (IOException)_err;
            if (_err instanceof IllegalStateException)
                throw (IllegalStateException)_err;
            throw new IllegalStateException(_err);
        }
    }

    /**
     * Parse, if necessary, the multipart stream.
     *
     */
    protected void parse ()
    {
        //have we already parsed the input?
        if (_parts != null || _err != null)
            return;


        //initialize
        long total = 0; //keep running total of size of bytes read from input and throw an exception if exceeds MultipartConfigElement._maxRequestSize
        _parts = new MultiMap<>();

        //if its not a multipart request, don't parse it
        if (_contentType == null || !_contentType.startsWith("multipart/form-data"))
            return;

        try
        {
            //sort out the location to which to write the files

            if (_config.getLocation() == null)
                _tmpDir = _contextTmpDir;
            else if ("".equals(_config.getLocation()))
                _tmpDir = _contextTmpDir;
            else
            {
                File f = new File (_config.getLocation());
                if (f.isAbsolute())
                    _tmpDir = f;
                else
                    _tmpDir = new File (_contextTmpDir, _config.getLocation());
            }

            if (!_tmpDir.exists())
                _tmpDir.mkdirs();

            String contentTypeBoundary = "";
            int bstart = _contentType.indexOf("boundary=");
            if (bstart >= 0)
            {
                int bend = _contentType.indexOf(";", bstart);
                bend = (bend < 0? _contentType.length(): bend);
                contentTypeBoundary = QuotedStringTokenizer.unquote(value(_contentType.substring(bstart,bend)).trim());
            }

        String boundary="--"+contentTypeBoundary;
        String lastBoundary=boundary+"--";
        
        byte[] boundaryWithNewLinePrefix=("\n--"+contentTypeBoundary).getBytes(StandardCharsets.ISO_8859_1);

            // Get first boundary
            String line = null;
            try
            {
                line=((ReadLineInputStream)_in).readLine();
            }
            catch (IOException e)
            {
                LOG.warn("Badly formatted multipart request");
                throw e;
            }

            if (line == null)
                throw new IOException("Missing content for multipart request");

            boolean badFormatLogged = false;
            line=line.trim();
            while (line != null && !line.equals(boundary) && !line.equals(lastBoundary))
            {
                if (!badFormatLogged)
                {
                    LOG.warn("Badly formatted multipart request");
                    badFormatLogged = true;
                }
                line=((ReadLineInputStream)_in).readLine();
                line=(line==null?line:line.trim());
            }

            if (line == null)
                throw new IOException("Missing initial multi part boundary");

            // Empty multipart.
            if (line.equals(lastBoundary))
                return;

            // Read each part
            boolean lastPart=false;
            
            ReadLineInputStream is = (ReadLineInputStream) _in;
            
            
            outer:while(!lastPart)
            {
                String contentDisposition=null;
                String contentType=null;
                String contentTransferEncoding=null;

                MultiMap<String> headers = new MultiMap<>();
                
                
                while(true)
                {
                    line=is.readLine();

                    //No more input
                    if(line==null)
                        break outer;

                    //end of headers:
                    if("".equals(line))
                        break;

                    total += line.length();
                    if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                        throw new IllegalStateException ("Request exceeds maxRequestSize ("+_config.getMaxRequestSize()+")");

                    //get content-disposition and content-type
                    int c=line.indexOf(':',0);
                    if(c>0)
                    {
                        String key=line.substring(0,c).trim().toLowerCase(Locale.ENGLISH);
                        String value=line.substring(c+1,line.length()).trim();
                        headers.put(key, value);
                        if (key.equalsIgnoreCase("content-disposition"))
                            contentDisposition=value;
                        if (key.equalsIgnoreCase("content-type"))
                            contentType = value;
                        if(key.equals("content-transfer-encoding"))
                            contentTransferEncoding=value;
                    }
                }

                // Extract content-disposition
                boolean form_data=false;
                if(contentDisposition==null)
                {
                    throw new IOException("Missing content-disposition");
                }

                QuotedStringTokenizer tok=new QuotedStringTokenizer(contentDisposition,";", false, true);
                String name=null;
                String filename=null;
                while(tok.hasMoreTokens())
                {
                    String t=tok.nextToken().trim();
                    String tl=t.toLowerCase(Locale.ENGLISH);
                    if(t.startsWith("form-data"))
                        form_data=true;
                    else if(tl.startsWith("name="))
                        name=value(t);
                    else if(tl.startsWith("filename="))
                        filename=filenameValue(t);
                }

                // Check disposition
                if(!form_data)
                {
                    continue;
                }
                //It is valid for reset and submit buttons to have an empty name.
                //If no name is supplied, the browser skips sending the info for that field.
                //However, if you supply the empty string as the name, the browser sends the
                //field, with name as the empty string. So, only continue this loop if we
                //have not yet seen a name field.
                if(name==null)
                {
                    continue;
                }

                //Have a new Part
                MultiPart part = new MultiPart(name, filename);
                part.setHeaders(headers);
                part.setContentType(contentType);
                _parts.add(name, part);
                part.open();

                InputStream partInput = null;
                if ("base64".equalsIgnoreCase(contentTransferEncoding))
                {
                    partInput = new Base64InputStream(is);
                }
                else if ("quoted-printable".equalsIgnoreCase(contentTransferEncoding))
                {
                    partInput = new FilterInputStream(is)
                    {
                        @Override
                        public int read() throws IOException
                        {
                            int c = in.read();
                            if (c >= 0 && c == '=')
                            {
                                int hi = in.read();
                                int lo = in.read();
                                if (hi < 0 || lo < 0)
                                {
                                    throw new IOException("Unexpected end to quoted-printable byte");
                                }
                                char[] chars = new char[] { (char)hi, (char)lo };
                                c = Integer.parseInt(new String(chars),16);
                            }
                            return c;
                        }
                        
                        @Override
                        public int read(byte b[]) throws IOException {
                            return read(b, 0, b.length);
                        }
                        
                        @Override
                        public int read(byte b[], int off, int len) throws IOException {
                            int i = 0;
                            for(i = 0; i < len; i++) {
                                int c = this.read();
                                if(c == -1) {
                                    return i;
                                }
                                b[off + i] = (byte) c;
                            }
                            return i;
                        }
                        
                        @Override
                        public void mark(int readlimit) {
                            this.in.mark(readlimit);
                        }

                        @Override
                        public void reset() throws IOException {
                            this.in.reset();
                        }

                        @Override
                        public boolean markSupported() {
                            return this.in.markSupported();
                        }
                        
                        
                        @Override
                        public long skip(long n) throws IOException {
                            for(long i = 0; i < n; i++) {
                                if(this.read() == -1) return i;
                            }
                            return n;
                        }
                        
                        
                    };
                }
                else
                    partInput = is;

                boolean trimLastChar = false;

                try
                {
					//Read the config noew so we don't need to keep re-reading it.
                    long maxRequestSize = Long.MAX_VALUE;
                    if (_config.getMaxRequestSize() > 0) maxRequestSize = _config.getMaxRequestSize();

                    
                    int chr = -1;
                    int bi = 0;
                    
                    int lastCharWritten = -1;
                    
                    //A buffer used when reading from the input stream.
                    byte[] bufin = new byte[CHUNK_READ_SIZE];
                    int offsetIntoBufin = -1;
                    int bufInSize;
                    while(true) {
                        //If mark is not supported the stream must ensure that we will
                        //never read too much, i.e. not more than the boundary (excluding the trailing new line
                        // or double dash).
                        if(partInput.markSupported()) {
                            partInput.mark(bufin.length);
                        }
                        //read a chunk
                        bufInSize = partInput.read(bufin);
                        if(bufInSize == -1) break;
                        for(offsetIntoBufin = 0; offsetIntoBufin < bufInSize;offsetIntoBufin++) {

                            chr = bufin[offsetIntoBufin];

                            //We allow boundaries to start with either CR or LF
                            if(chr == '\r' || chr == '\n') {
                                
                                if(bi != 0) {
                                    //We where already processing what looked like a boundary, write out
                                    //anything we missed.
                                    if (offsetIntoBufin < bi) {
                                        //write all of the boundary up to the point the boundary is in this array.
                                        part.write(boundaryWithNewLinePrefix,0,bi - offsetIntoBufin);
                                        lastCharWritten = boundaryWithNewLinePrefix[bi - offsetIntoBufin-1];
                                        total += bi - offsetIntoBufin;
                                        if (total > maxRequestSize)
                                            throw new IllegalStateException("Request exceeds maxRequestSize ("+_config.getMaxRequestSize()+")");
                                    }
                                }
                                bi = 0;
                                //Remember what this boundary started with.
                                boundaryWithNewLinePrefix[bi] = (byte) chr;
                                bi++;
                            } else if(boundaryWithNewLinePrefix[bi] == chr) {
                                bi++;
                                if(bi == boundaryWithNewLinePrefix.length) {
                                    //boundary found
                                    //As we leave the loop early we need to increase offsetIntoBuffin
                                    //to ensure where we are up to into the buffer is consistent.
                                    offsetIntoBufin++;
                                    break;
                                }
                            } else {
                                if(bi != 0) {
                                    if (offsetIntoBufin < bi) {
                                        //write all of the boundary up to the point the boundary is in this array.
                                        part.write(boundaryWithNewLinePrefix,0,bi - offsetIntoBufin);
                                        lastCharWritten = boundaryWithNewLinePrefix[bi - offsetIntoBufin-1];
                                        total += bi - offsetIntoBufin;
                                        if (total > maxRequestSize)
                                            throw new IllegalStateException("Request exceeds maxRequestSize ("+_config.getMaxRequestSize()+")");
                                    }
                                }
                                bi = 0;
                            }
                        }
                        //write up to boundary limit
                        int toWrite = offsetIntoBufin;
                        if(bi > 0) {
                            //if we are looking at a boundary only write up to just before we think the
                            //boundary starts, again this will not include the prevchar which we may write out
                            //later on.
                            toWrite = offsetIntoBufin - bi;
                        }

                        if (toWrite > 0) {
                            total += toWrite;
                            if (total > maxRequestSize)
                                throw new IllegalStateException("Request exceeds maxRequestSize ("+_config.getMaxRequestSize()+")");
                            lastCharWritten = bufin[toWrite-1];
                            part.write(bufin, 0, toWrite);
                        }

                        if(bi == boundaryWithNewLinePrefix.length) {                        
                            //boundary found
                            break;
                        }
                    }
                    if(partInput.markSupported()) {
                        partInput.reset();
                        long actuallySkipped = partInput.skip(offsetIntoBufin);
                        for(; actuallySkipped<offsetIntoBufin; actuallySkipped++) {
                            partInput.read();
                        }
                    }

                    if(bi != boundaryWithNewLinePrefix.length) {
                        throw new IllegalArgumentException("Missing trailing boundary");
                    }

                    //backup if the boundary was preceeded with \r\n, as we wrote out the \r
                    if(boundaryWithNewLinePrefix[0] == '\n' && lastCharWritten == '\r') {
                        //It ended with \r\n remove the extra written out \r
                        if(part._bout == part._out) {
                            //We are not writing to a file.
                            int lastchar = part._bout.getCount() - 1;
                            part._bout.setCount(lastchar);
                            part._size--;
                        } else {
                            //we are writing to a file, we will trim the last char file.
                            trimLastChar = true;
                        }
                    }

                    
                    //Is this the end?
                    if( chr == -1) { 
                        lastPart = true;
                    } else {
                        int c2ndLastChar = partInput.read();
                        if(c2ndLastChar == -1) {
                            lastPart = true;
                        } else if(c2ndLastChar == '-') {
                            int lastChar = partInput.read();
                            if(lastChar == -1 || lastChar == '-') {
                                lastPart = true;
                            }
                        } else if (c2ndLastChar == '\r') {
                            //This ends well enough but we need to check the next char.
                            //we need to be careful what we do here this might be the end it or we may find we have
                            //more to deal with
                            if(partInput.markSupported()) {
                                partInput.mark(1);
                            }
                            int lastchar = partInput.read();
                            if(lastchar == '\n') {
                                
                            } else if (lastchar == -1) {
                                lastPart = true;
                            } else {
                                if(partInput.markSupported()) {
                                    partInput.reset();
                                } else {
                                    throw new RuntimeException("The stream must support marking or "
                                        + "ensure the boundary is not terminated with \\r.");
                                }
                            }
                        } else if (c2ndLastChar == '\n') {
                            //ok as well
                        } else {
                            throw new IllegalArgumentException("Boundary must be followed by a new line.");
                        }

                    }
                }
                finally
                {
                    part.close();
                    //In the case of a file we may need to trim the last char.
                    if(trimLastChar && part._file != null && part._file.exists()) {
                        File f = part._file;
                        FileChannel outChan = new FileOutputStream(f, true).getChannel();
                        outChan.truncate(f.length() - 1);
                        outChan.close();
                        part._size--;
                    }
                }
            }
            if (lastPart)
            {
                while(line!=null)
                    line=is.readLine();
            }
            else
                throw new IOException("Incomplete parts");
        }
        catch (Exception e)
        {
            _err = e;
        }
    }

    public void setDeleteOnExit(boolean deleteOnExit)
    {
        _deleteOnExit = deleteOnExit;
    }

    public void setWriteFilesWithFilenames (boolean writeFilesWithFilenames)
    {
        _writeFilesWithFilenames = writeFilesWithFilenames;
    }
    
    public boolean isWriteFilesWithFilenames ()
    {
        return _writeFilesWithFilenames;
    }

    public boolean isDeleteOnExit()
    {
        return _deleteOnExit;
    }


    /* ------------------------------------------------------------ */
    private String value(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx+1).trim();
        return QuotedStringTokenizer.unquoteOnly(value);
    }


    /* ------------------------------------------------------------ */
    private String filenameValue(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx+1).trim();

        if (value.matches(".??[a-z,A-Z]\\:\\\\[^\\\\].*"))
        {
            //incorrectly escaped IE filenames that have the whole path
            //we just strip any leading & trailing quotes and leave it as is
            char first=value.charAt(0);
            if (first=='"' || first=='\'')
                value=value.substring(1);
            char last=value.charAt(value.length()-1);
            if (last=='"' || last=='\'')
                value = value.substring(0,value.length()-1);

            return value;
        }
        else
            //unquote the string, but allow any backslashes that don't
            //form a valid escape sequence to remain as many browsers
            //even on *nix systems will not escape a filename containing
            //backslashes
            return QuotedStringTokenizer.unquoteOnly(value, true);
    }



    private static class Base64InputStream extends InputStream
    {
        ReadLineInputStream _in;
        String _line;
        byte[] _buffer;
        int _pos;
        String returnNext = null;

        public long skip(long n) throws IOException {
            return _in.skip(n);
        }

        public boolean markSupported() {
            return false;
        }

        public void close() throws IOException {
            _in.close();
        }

        public Base64InputStream(ReadLineInputStream rlis)
        {
            _in = rlis;
        }
        
        private boolean fillBuffer() throws IOException {
            if (_buffer==null || _pos>= _buffer.length)
            {
                
                if(returnNext != null) {
                    _buffer= returnNext.getBytes();
                    _pos = 0;
                    returnNext = null;
                    return true;
                }
                _line = _in.readLine();
                if (_line==null)
                    return false;  //nothing left
                if (_line.startsWith("--")) {
                    //Ensure the next call returns the parts after the boundary.
                    if(_line.endsWith("--")) {
                        returnNext = "--\r\n";
                    } else {
                        returnNext = "\r\n";
                    }
                    _buffer=(_line).getBytes(); //boundary marking end of part
                } else if (_line.length()==0)
                    _buffer="\r\n".getBytes(); //blank line
                else
                {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream((4*_line.length()/3)+2);
                    B64Code.decode(_line, baos);
                    baos.write(13);
                    baos.write(10);
                    _buffer = baos.toByteArray();
                }

                _pos=0;
            }
            return true;
        }

        @Override
        public int read() throws IOException
        {
            if(fillBuffer()) {
                return _buffer[_pos++];
            } 
            return -1;
        }
        
        @Override
        public int read(byte b[]) throws IOException {
            return read(b, 0, b.length);
        }
        
        @Override
        public int read(byte b[], int off, int len) throws IOException {
            int i = 0;
            if(!fillBuffer()) {
                return -1;
            }
            for(i = 0; i < len && _pos < _buffer.length ; i++) {
                b[off + i] = _buffer[_pos];
                _pos++;
            }
            return i;
        }
        
    }
}
