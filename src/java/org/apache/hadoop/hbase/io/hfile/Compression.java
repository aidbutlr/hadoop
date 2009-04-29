/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.GzipCodec;

/**
 * Compression related stuff.
 * Copied from hadoop-3315 tfile.
 */
public final class Compression {
  static final Log LOG = LogFactory.getLog(Compression.class);

  /**
   * Prevent the instantiation of class.
   */
  private Compression() {
    super();
  }

  static class FinishOnFlushCompressionStream extends FilterOutputStream {
    public FinishOnFlushCompressionStream(CompressionOutputStream cout) {
      super(cout);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      CompressionOutputStream cout = (CompressionOutputStream) out;
      cout.finish();
      cout.flush();
      cout.resetState();
    }
  }

  /**
   * Compression algorithms.
   */
  public static enum Algorithm {
    LZO("lzo") {

      @Override
      CompressionCodec getCodec() {
        throw new UnsupportedOperationException("LZO compression is disabled for now");
      }
      @Override
      public InputStream createDecompressionStream(InputStream downStream, Decompressor decompressor, int downStreamBufferSize) throws IOException {
        throw new UnsupportedOperationException("LZO compression is disabled for now");
      }
      @Override
      public OutputStream createCompressionStream(OutputStream downStream, Compressor compressor, int downStreamBufferSize) throws IOException {
        throw new UnsupportedOperationException("LZO compression is disabled for now");
      }
    },
    GZ("gz") {
      private GzipCodec codec;

      @Override
      CompressionCodec getCodec() {
        if (codec == null) {
          Configuration conf = new Configuration();
          conf.setBoolean("hadoop.native.lib", true);
          codec = new GzipCodec();
          codec.setConf(conf);
        }

        return codec;
      }

      @Override
      public synchronized InputStream createDecompressionStream(
          InputStream downStream, Decompressor decompressor,
          int downStreamBufferSize) throws IOException {
        // Set the internal buffer size to read from down stream.
        if (downStreamBufferSize > 0) {
          codec.getConf().setInt("io.file.buffer.size", downStreamBufferSize);
        }
        CompressionInputStream cis =
            codec.createInputStream(downStream, decompressor);
        BufferedInputStream bis2 = new BufferedInputStream(cis, DATA_IBUF_SIZE);
        return bis2;
      }

      @Override
      public synchronized OutputStream createCompressionStream(
          OutputStream downStream, Compressor compressor,
          int downStreamBufferSize) throws IOException {
        OutputStream bos1 = null;
        if (downStreamBufferSize > 0) {
          bos1 = new BufferedOutputStream(downStream, downStreamBufferSize);
        }
        else {
          bos1 = downStream;
        }
        codec.getConf().setInt("io.file.buffer.size", 32 * 1024);
        CompressionOutputStream cos =
            codec.createOutputStream(bos1, compressor);
        BufferedOutputStream bos2 =
            new BufferedOutputStream(new FinishOnFlushCompressionStream(cos),
                DATA_OBUF_SIZE);
        return bos2;
      }
    },

    NONE("none") {
      @Override
      CompressionCodec getCodec() {
        return null;
      }

      @Override
      public synchronized InputStream createDecompressionStream(
          InputStream downStream, Decompressor decompressor,
          int downStreamBufferSize) throws IOException {
        if (downStreamBufferSize > 0) {
          return new BufferedInputStream(downStream, downStreamBufferSize);
        }
        // else {
          // Make sure we bypass FSInputChecker buffer.
        // return new BufferedInputStream(downStream, 1024);
        // }
        // }
        return downStream;
      }

      @Override
      public synchronized OutputStream createCompressionStream(
          OutputStream downStream, Compressor compressor,
          int downStreamBufferSize) throws IOException {
        if (downStreamBufferSize > 0) {
          return new BufferedOutputStream(downStream, downStreamBufferSize);
        }

        return downStream;
      }
    };

    private final String compressName;
	// data input buffer size to absorb small reads from application.
    private static final int DATA_IBUF_SIZE = 1 * 1024;
	// data output buffer size to absorb small writes from application.
    private static final int DATA_OBUF_SIZE = 4 * 1024;

    Algorithm(String name) {
      this.compressName = name;
    }

    abstract CompressionCodec getCodec();

    public abstract InputStream createDecompressionStream(
        InputStream downStream, Decompressor decompressor,
        int downStreamBufferSize) throws IOException;

    public abstract OutputStream createCompressionStream(
        OutputStream downStream, Compressor compressor, int downStreamBufferSize)
        throws IOException;

    public Compressor getCompressor() {
      CompressionCodec codec = getCodec();
      if (codec != null) {
        Compressor compressor = CodecPool.getCompressor(codec);
        if (compressor != null) {
          if (compressor.finished()) {
            // Somebody returns the compressor to CodecPool but is still using
            // it.
            LOG
                .warn("Compressor obtained from CodecPool is already finished()");
            // throw new AssertionError(
            // "Compressor obtained from CodecPool is already finished()");
          }
          compressor.reset();
        }
        return compressor;
      }
      return null;
    }

    public void returnCompressor(Compressor compressor) {
      if (compressor != null) {
        CodecPool.returnCompressor(compressor);
      }
    }

    public Decompressor getDecompressor() {
      CompressionCodec codec = getCodec();
      if (codec != null) {
        Decompressor decompressor = CodecPool.getDecompressor(codec);
        if (decompressor != null) {
          if (decompressor.finished()) {
            // Somebody returns the decompressor to CodecPool but is still using
            // it.
            LOG
                .warn("Deompressor obtained from CodecPool is already finished()");
            // throw new AssertionError(
            // "Decompressor obtained from CodecPool is already finished()");
          }
          decompressor.reset();
        }
        return decompressor;
      }

      return null;
    }

    public void returnDecompressor(Decompressor decompressor) {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
      }
    }

    public String getName() {
      return compressName;
    }
  }

  public static Algorithm getCompressionAlgorithmByName(String compressName) {
    Algorithm[] algos = Algorithm.class.getEnumConstants();

    for (Algorithm a : algos) {
      if (a.getName().equals(compressName)) {
        return a;
      }
    }

    throw new IllegalArgumentException(
        "Unsupported compression algorithm name: " + compressName);
  }

  static String[] getSupportedAlgorithms() {
    Algorithm[] algos = Algorithm.class.getEnumConstants();

    String[] ret = new String[algos.length];
    int i = 0;
    for (Algorithm a : algos) {
      ret[i++] = a.getName();
    }

    return ret;
  }
}
