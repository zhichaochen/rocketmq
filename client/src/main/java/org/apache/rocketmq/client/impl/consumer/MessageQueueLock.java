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
package org.apache.rocketmq.client.impl.consumer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * Message lock,strictly ensure the single queue only one thread at a time consuming
 *
 * 消费队列锁：严格保证一个消费队列，一次只能由一个线程消费
 */
public class MessageQueueLock {
    /**
     * 缓存的消费队列
     * 其中：key：消费队列，value：上锁的对象
     */
    private ConcurrentMap<MessageQueue, Object> mqLockTable =
        new ConcurrentHashMap<MessageQueue, Object>();

    /**
     * 获取上锁的对象。
     *
     * 上锁的本质来说，是锁定某个对象，此处获取该对象
     * @param mq
     * @return
     */
    public Object fetchLockObject(final MessageQueue mq) {
        Object objLock = this.mqLockTable.get(mq);
        if (null == objLock) {
            objLock = new Object();
            Object prevLock = this.mqLockTable.putIfAbsent(mq, objLock);
            if (prevLock != null) {
                objLock = prevLock;
            }
        }

        return objLock;
    }
}
