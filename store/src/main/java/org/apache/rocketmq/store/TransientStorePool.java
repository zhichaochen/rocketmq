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

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.util.LibC;
import sun.nio.ch.DirectBuffer;

/**
 * 短暂的存储池
 *
 * 用于池化管理多个Direct 分配 的ByteBuffer对象,进行借与还的操作
 *
 * 为什么？？？
 *      因为allocateDirect，是调用操作系统进行分配，是一个重量级的操作，故而，要建立缓存池。
 *
 *      allocateDirect：分配缓慢，但是读写快。
 *
 */
public class TransientStorePool {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    /**
     * 池的大小有多少,默认5
     */
    private final int poolSize;
    /**
     * 每个commitLog文件大小，默认1G
     */
    private final int fileSize;
    /**
     * 双端队列用于缓存ByteBuffer
     */
    private final Deque<ByteBuffer> availableBuffers;
    /**
     * 存储配置文件
     */
    private final MessageStoreConfig storeConfig;

    public TransientStorePool(final MessageStoreConfig storeConfig) {
        //消息存储的配置文件
        this.storeConfig = storeConfig;
        this.poolSize = storeConfig.getTransientStorePoolSize();
        this.fileSize = storeConfig.getMappedFileSizeCommitLog();
        this.availableBuffers = new ConcurrentLinkedDeque<>();
    }

    /**
     * It's a heavy init method.
     * 初始化函数，分配poolSize个fileSize的堆外空间，
     * 并缓存在availableBuffers队列中。
     */
    public void init() {
        for (int i = 0; i < poolSize; i++) {
            /**
             * allocateDirect ：直接分配堆外面的内存。
             * 切记：不是堆的内存。
             */
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(fileSize);

            //开始地址
            final long address = ((DirectBuffer) byteBuffer).address();
            //创建指针
            Pointer pointer = new Pointer(address);
            /**
             * 锁定内存，防止内存
             */
            LibC.INSTANCE.mlock(pointer, new NativeLong(fileSize));
            /**
             * 缓存进入队列。
             */
            availableBuffers.offer(byteBuffer);
        }
    }

    /**
     *  销毁
     *  释放availableBuffers中所有buffer缓存。
     */
    public void destroy() {
        for (ByteBuffer byteBuffer : availableBuffers) {
            final long address = ((DirectBuffer) byteBuffer).address();
            Pointer pointer = new Pointer(address);
            LibC.INSTANCE.munlock(pointer, new NativeLong(fileSize));
        }
    }

    //用完了之后,返还一个buffer,对buffer数据进行清理
    public void returnBuffer(ByteBuffer byteBuffer) {
        byteBuffer.position(0);
        byteBuffer.limit(fileSize);
        this.availableBuffers.offerFirst(byteBuffer);
    }

    //借一个buffer出去
    public ByteBuffer borrowBuffer() {
        ByteBuffer buffer = availableBuffers.pollFirst();
        if (availableBuffers.size() < poolSize * 0.4) {
            log.warn("TransientStorePool only remain {} sheets.", availableBuffers.size());
        }
        return buffer;
    }

    /**
     * 缓存起来的ByteBuffer。
     */
    public int availableBufferNums() {
        if (storeConfig.isTransientStorePoolEnable()) {
            return availableBuffers.size();
        }
        return Integer.MAX_VALUE;
    }
}
