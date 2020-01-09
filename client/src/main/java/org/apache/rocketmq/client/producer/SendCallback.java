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
package org.apache.rocketmq.client.producer;

/**
 * 消息发送回调函数。
 * 在异步发送消息的时候，我们需要实现该接口，等待接收发送结果。
 */
public interface SendCallback {
    /**
     * 消息发送成功做什么
     *
     * @param sendResult
     */
    void onSuccess(final SendResult sendResult);

    /**
     * 发送异常做什么。
     * @param e
     */
    void onException(final Throwable e);
}
