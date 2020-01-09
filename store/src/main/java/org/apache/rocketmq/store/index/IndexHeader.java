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
package org.apache.rocketmq.store.index;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引文件的头信息
 */
public class IndexHeader {
    //索引头总共有40个字节
    public static final int INDEX_HEADER_SIZE = 40;

    /**
     * ==============头信息索引=================
     *
     * 后面的数字表示占用多少个字节。总共40个字节
     */
    //开始时间戳：8个字节
    private static int beginTimestampIndex = 0;
    //结束时间戳：8个字节。
    private static int endTimestampIndex = 8;
    //索引文件的开始offset：8个字节
    private static int beginPhyoffsetIndex = 16;
    //结束的offset：8个字节，记录CommitLog最新提交到IndexFile的Offset
    private static int endPhyoffsetIndex = 24;
    //hash槽：4个字节（36-32），最大500玩
    private static int hashSlotcountIndex = 32;
    //索引总数：4个字节，最大2000万
    private static int indexCountIndex = 36;
    //字节缓存
    private final ByteBuffer byteBuffer;
    //===========================================

    /**
     * 记录开始时间戳
     * （索引文件中写入的第一条消息的在【CommitLog中的存储时间】）
     */
    private AtomicLong beginTimestamp = new AtomicLong(0);
    //记录结束时间戳
    private AtomicLong endTimestamp = new AtomicLong(0);

    /**
     *  记录开始的磁盘的offset(索引文件第一条消息在CommitLog文件中的offset)
     */
    private AtomicLong beginPhyOffset = new AtomicLong(0);
    //记录写入磁盘的end offset（最后一条消息的offset，每写入一条更新一次）
    private AtomicLong endPhyOffset = new AtomicLong(0);
    //记录hash曹数量
    private AtomicInteger hashSlotCount = new AtomicInteger(0);
    //记录当前索引数量
    private AtomicInteger indexCount = new AtomicInteger(1);

    public IndexHeader(final ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
    }

    public void load() {
        this.beginTimestamp.set(byteBuffer.getLong(beginTimestampIndex));
        this.endTimestamp.set(byteBuffer.getLong(endTimestampIndex));
        this.beginPhyOffset.set(byteBuffer.getLong(beginPhyoffsetIndex));
        this.endPhyOffset.set(byteBuffer.getLong(endPhyoffsetIndex));

        this.hashSlotCount.set(byteBuffer.getInt(hashSlotcountIndex));
        this.indexCount.set(byteBuffer.getInt(indexCountIndex));

        if (this.indexCount.get() <= 0) {
            this.indexCount.set(1);
        }
    }

    /**
     * 当一个IndexFile写满的时候，更新索引文件的头信息。然后刷入磁盘
     */
    public void updateByteBuffer() {
        this.byteBuffer.putLong(beginTimestampIndex, this.beginTimestamp.get());
        this.byteBuffer.putLong(endTimestampIndex, this.endTimestamp.get());
        this.byteBuffer.putLong(beginPhyoffsetIndex, this.beginPhyOffset.get());
        this.byteBuffer.putLong(endPhyoffsetIndex, this.endPhyOffset.get());
        this.byteBuffer.putInt(hashSlotcountIndex, this.hashSlotCount.get());
        this.byteBuffer.putInt(indexCountIndex, this.indexCount.get());
    }

    public long getBeginTimestamp() {
        return beginTimestamp.get();
    }

    public void setBeginTimestamp(long beginTimestamp) {
        this.beginTimestamp.set(beginTimestamp);
        this.byteBuffer.putLong(beginTimestampIndex, beginTimestamp);
    }

    public long getEndTimestamp() {
        return endTimestamp.get();
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp.set(endTimestamp);
        this.byteBuffer.putLong(endTimestampIndex, endTimestamp);
    }

    public long getBeginPhyOffset() {
        return beginPhyOffset.get();
    }

    public void setBeginPhyOffset(long beginPhyOffset) {
        this.beginPhyOffset.set(beginPhyOffset);
        this.byteBuffer.putLong(beginPhyoffsetIndex, beginPhyOffset);
    }

    public long getEndPhyOffset() {
        return endPhyOffset.get();
    }

    public void setEndPhyOffset(long endPhyOffset) {
        this.endPhyOffset.set(endPhyOffset);
        this.byteBuffer.putLong(endPhyoffsetIndex, endPhyOffset);
    }

    public AtomicInteger getHashSlotCount() {
        return hashSlotCount;
    }

    public void incHashSlotCount() {
        int value = this.hashSlotCount.incrementAndGet();
        this.byteBuffer.putInt(hashSlotcountIndex, value);
    }

    public int getIndexCount() {
        return indexCount.get();
    }

    public void incIndexCount() {
        int value = this.indexCount.incrementAndGet();
        this.byteBuffer.putInt(indexCountIndex, value);
    }
}
