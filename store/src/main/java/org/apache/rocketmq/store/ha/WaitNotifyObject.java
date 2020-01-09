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
package org.apache.rocketmq.store.ha;

import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

import java.util.HashMap;

/**
 * 等待，通知对象
 * 没有工作的时候休息，有工作的时候，唤醒线程起来工作。
 */
public class WaitNotifyObject {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    /**
     * 缓存所有正在等待的线程
     * 其中：key：线程Id，value：hasNotified
     */
    protected final HashMap<Long/* thread id */, Boolean/* notified */> waitingThreadTable =
        new HashMap<Long, Boolean>(16);

    /**
     * 是否是已通知状态
     * true：表示开始工作
     * false ：表示当前线程正在等待
     */
    protected volatile boolean hasNotified = false;

    /**
     * 唤醒工作线程
     */
    public void wakeup() {
        synchronized (this) {
            if (!this.hasNotified) {
                this.hasNotified = true;
                this.notify();
            }
        }
    }

    /**
     * 让正在运行的线程等待一会儿
     *
     * @param interval
     */
    protected void waitForRunning(long interval) {
        synchronized (this) {
            //改变hasNotified状态为false
            if (this.hasNotified) {
                this.hasNotified = false;
                this.onWaitEnd();
                return;
            }

            try {
                //当前线程等待
                this.wait(interval);
            } catch (InterruptedException e) {
                log.error("Interrupted", e);
            } finally {
                this.hasNotified = false;
                this.onWaitEnd();
            }
        }
    }

    protected void onWaitEnd() {
    }

    /**
     * 唤醒缓存的所有线程
     * handleHA(),调用了该方法。
     */
    public void wakeupAll() {
        synchronized (this) {
            boolean needNotify = false;

            for (Boolean value : this.waitingThreadTable.values()) {
                needNotify = needNotify || !value;
                value = true;
            }

            if (needNotify) {
                this.notifyAll();
            }
        }
    }

    /**
     * 当前线程wait一会儿，并加入缓存
     * 其实加入的是WriteSocketService。
     *
     * 目前来看，只有WriteSocketService用到了该方法，
     * 当没有数据可同步的时候，加入缓存，表示同步线程正在休息。
     *
     * @param interval
     */
    public void allWaitForRunning(long interval) {
        //当前线程
        long currentThreadId = Thread.currentThread().getId();
        synchronized (this) {
            /**
             * 缓存中已经存在，则更改状态。
             */
            Boolean notified = this.waitingThreadTable.get(currentThreadId);
            if (notified != null && notified) {
                this.waitingThreadTable.put(currentThreadId, false);
                this.onWaitEnd();
                return;
            }

            /**
             * 缓存中不存在，则等待，并加入缓存。
             */
            try {
                this.wait(interval);
            } catch (InterruptedException e) {
                log.error("Interrupted", e);
            } finally {
                this.waitingThreadTable.put(currentThreadId, false);
                this.onWaitEnd();
            }
        }
    }

    /**
     * 同步完成后，从缓存移除当前正在等待的线程。
     */
    public void removeFromWaitingThreadTable() {
        long currentThreadId = Thread.currentThread().getId();
        synchronized (this) {
            this.waitingThreadTable.remove(currentThreadId);
        }
    }
}
