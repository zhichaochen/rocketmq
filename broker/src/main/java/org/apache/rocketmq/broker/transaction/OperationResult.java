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
package org.apache.rocketmq.broker.transaction;

import org.apache.rocketmq.common.message.MessageExt;

/**
 * 一条事务消息
 *
 * 这条消息要么被提交，要么需要回滚。
 */
public class OperationResult {
    /**
     * 二阶段提交中的prepare阶段发送的消息
     */
    private MessageExt prepareMessage;

    //响应code
    private int responseCode;

    //备注信息
    private String responseRemark;

    public MessageExt getPrepareMessage() {
        return prepareMessage;
    }

    public void setPrepareMessage(MessageExt prepareMessage) {
        this.prepareMessage = prepareMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseRemark() {
        return responseRemark;
    }

    public void setResponseRemark(String responseRemark) {
        this.responseRemark = responseRemark;
    }
}
