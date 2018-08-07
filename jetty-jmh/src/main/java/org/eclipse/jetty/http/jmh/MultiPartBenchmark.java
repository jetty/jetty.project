//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.jmh;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.http.MultiPartCaptureTest.MultipartExpectations;
import org.eclipse.jetty.toolchain.test.IO;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class MultiPartBenchmark
{
    
    public static final int MAX_FILE_SIZE = Integer.MAX_VALUE;
    public static final int MAX_REQUEST_SIZE = Integer.MAX_VALUE;
    public static final int FILE_SIZE_THRESHOLD = 50;
    
    public int count = 0;
    static String _contentType;
    static File _file;
    static int _numSections;
    static int _numBytesPerSection;
    
    
    public static List<String> data = new ArrayList<>();
    
    static
    {
        // Capture of raw request body contents from various browsers
        
        // simple form - 2 fields
        data.add("browser-capture-form1-android-chrome");
        data.add("browser-capture-form1-android-firefox");
        data.add("browser-capture-form1-chrome");
        data.add("browser-capture-form1-edge");
        data.add("browser-capture-form1-firefox");
        data.add("browser-capture-form1-ios-safari");
        data.add("browser-capture-form1-msie");
        data.add("browser-capture-form1-osx-safari");
        
        // form submitted as shift-jis
        data.add("browser-capture-sjis-form-edge");
        data.add("browser-capture-sjis-form-msie");
        
        // form submitted as shift-jis (with HTML5 specific hidden _charset_ field)
        data.add("browser-capture-sjis-charset-form-edge");
        data.add("browser-capture-sjis-charset-form-msie");
        
        // form submitted with simple file upload
        data.add("browser-capture-form-fileupload-android-chrome");
        data.add("browser-capture-form-fileupload-android-firefox");
        data.add("browser-capture-form-fileupload-chrome");
        data.add("browser-capture-form-fileupload-edge");
        data.add("browser-capture-form-fileupload-firefox");
        data.add("browser-capture-form-fileupload-ios-safari");
        data.add("browser-capture-form-fileupload-msie");
        data.add("browser-capture-form-fileupload-safari");
        
        // form submitted with 2 files (1 binary, 1 text) and 2 text fields
        data.add("browser-capture-form-fileupload-alt-chrome");
        data.add("browser-capture-form-fileupload-alt-edge");
        data.add("browser-capture-form-fileupload-alt-firefox");
        data.add("browser-capture-form-fileupload-alt-msie");
        data.add("browser-capture-form-fileupload-alt-safari");
    }
    
    
    @Param({"UTIL", "HTTP"})
    public static String parserType;
    
    @Setup(Level.Trial)
    public static void setupTrial() throws Exception
    {
        _file = File.createTempFile("test01", null);
        _file.deleteOnExit();
        
        _numSections = 1;
        _numBytesPerSection = 1024 * 1024 * 10;
        
        _contentType = "multipart/form-data, boundary=WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW";
        String initialBoundary = "--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n";
        String boundary = "\r\n--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n";
        String closingBoundary = "\r\n--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW--\r\n";
        String headerStart = "Content-Disposition: form-data; name=\"";
        
        
        for (int i = 0; i < _numSections; i++)
        {
            //boundary and headers
            if (i == 0)
                Files.write(_file.toPath(), initialBoundary.getBytes(), StandardOpenOption.APPEND);
            else
                Files.write(_file.toPath(), boundary.getBytes(), StandardOpenOption.APPEND);
            
            Files.write(_file.toPath(), headerStart.getBytes(), StandardOpenOption.APPEND);
            Files.write(_file.toPath(), ("part" + (i + 1)).getBytes(), StandardOpenOption.APPEND);
            Files.write(_file.toPath(), ("\"\r\n\r\n").getBytes(), StandardOpenOption.APPEND);
            
            //append random data
            byte[] data = new byte[_numBytesPerSection];
            new Random().nextBytes(data);
            Files.write(_file.toPath(), data, StandardOpenOption.APPEND);
        }
        
        //closing boundary
        Files.write(_file.toPath(), closingBoundary.getBytes(), StandardOpenOption.APPEND);
    }
    
    
    @Benchmark
    @BenchmarkMode({Mode.AverageTime})
    @SuppressWarnings("deprecation")
    public long testLargeGenerated() throws Exception
    {
        Path multipartRawFile = _file.toPath();
        Path outputDir = new File("/tmp").toPath();
        
        MultipartConfigElement config = newMultipartConfigElement(outputDir);
        
        try (InputStream in = Files.newInputStream(multipartRawFile))
        {
            switch (parserType)
            {
                case "HTTP":
                {
                    MultiPartFormInputStream parser = new MultiPartFormInputStream(in, _contentType, config, outputDir.toFile());
                    if (parser.getParts().size() != _numSections)
                        throw new IllegalStateException("Incorrect Parsing");
                    for (Part p : parser.getParts())
                    {
                        count += p.getSize();
                    }
                }
                break;
                
                case "UTIL":
                {
                    org.eclipse.jetty.util.MultiPartInputStreamParser parser = new org.eclipse.jetty.util.MultiPartInputStreamParser(in, _contentType, config, outputDir.toFile());
                    if (parser.getParts().size() != _numSections)
                        throw new IllegalStateException("Incorrect Parsing");
                    for (Part p : parser.getParts())
                    {
                        count += p.getSize();
                    }
                }
                break;
                
                default:
                    throw new IllegalStateException("Unknown parserType Parameter");
            }
        }
        
        return count;
    }
    
    
    @TearDown(Level.Trial)
    public static void stopTrial() throws Exception
    {
        _file = null;
    }
    
    private MultipartConfigElement newMultipartConfigElement(Path path)
    {
        return new MultipartConfigElement(path.toString(), MAX_FILE_SIZE, MAX_REQUEST_SIZE, FILE_SIZE_THRESHOLD);
    }
    
    @Benchmark
    @BenchmarkMode({Mode.AverageTime})
    @SuppressWarnings("deprecation")
    public long testParser() throws Exception
    {
        for (String multiPart : data)
        {
            //Path multipartRawFile = MavenTestingUtils.getTestResourcePathFile("multipart/" + multiPart + ".raw");
            String expectationPath = "multipart/" + multiPart + ".expected.txt";
            //Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + multiPart + ".expected.txt");

            File expectationFile = File.createTempFile( expectationPath, ".tmp" );

            try(InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(expectationPath);
                OutputStream os = Files.newOutputStream( expectationFile.toPath() )) {
                IO.copy( inputStream, os );
            }

            Path outputDir = Files.createTempDirectory( "expected_output_jmh_jetty" );// new File("/tmp").toPath();
            
            MultipartExpectations multipartExpectations = new MultipartExpectations(expectationFile.toPath());
            MultipartConfigElement config = newMultipartConfigElement(outputDir);
            
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream( "multipart/" + multiPart + ".raw" ))
            {
                switch (parserType)
                {
                    case "HTTP":
                    {
                        MultiPartFormInputStream parser = new MultiPartFormInputStream(in, multipartExpectations.contentType, config, outputDir.toFile());
                        for (Part p : parser.getParts())
                        {
                            count += p.getSize();
                        }
                    }
                    break;
                    case "UTIL":
                    {
                        org.eclipse.jetty.util.MultiPartInputStreamParser parser = new org.eclipse.jetty.util.MultiPartInputStreamParser(in, multipartExpectations.contentType, config, outputDir.toFile());
                        for (Part p : parser.getParts())
                        {
                            count += p.getSize();
                        }
                    }
                    break;
                    default:
                        throw new IllegalStateException("Unknown parserType Parameter");
                }
            }
            
        }
        return count;
    }
    
    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
                .include(MultiPartBenchmark.class.getSimpleName())
                .warmupIterations(20)
                .measurementIterations(10)
                .forks(1)
                .threads(1)
                .build();
        
        new Runner(opt).run();
    }
}


