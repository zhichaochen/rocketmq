/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import java.nio.ByteBuffer;

/**
 * MappedBuffer的查询结果（可能包含多条消息）
 *
 * 消息在文件中的位置。表示消息的查询结果。
 */
public class SelectMappedBufferResult {
    //开始位置
    private final long startOffset;

    //ByteBuffer对象
    private final ByteBuffer byteBuffer;

    /**
     * 数据的长度
     * （在主从同步的时候，使用master的max offset - slave的 max offset，这部分数据都传输过去了。）
     */
    private int size;

    //消息所在的文件
    private MappedFile mappedFile;

    public SelectMappedBufferResult(long startOffset, ByteBuffer byteBuffer, int size, MappedFile mappedFile) {
        this.startOffset = startOffset;
        this.byteBuffer = byteBuffer;
        this.size = size;
        this.mappedFile = mappedFile;
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    public int getSize() {
        return size;
    }

    public void setSize(final int s) {
        this.size = s;
        this.byteBuffer.limit(this.size);
    }

//    @Override
//    protected void finalize() {
//        if (this.mappedFile != null) {
//            this.release();
//        }
//    }

    /**
     * 释放mappedFile的引用
     */
    public synchronized void release() {
        if (this.mappedFile != null) {
            this.mappedFile.release();
            this.mappedFile = null;
        }
    }

    public long getStartOffset() {
        return startOffset;
    }
}
