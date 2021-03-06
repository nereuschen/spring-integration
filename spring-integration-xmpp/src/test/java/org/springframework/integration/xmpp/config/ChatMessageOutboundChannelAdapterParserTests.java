/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.xmpp.config;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.jivesoftware.smack.XMPPConnection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.Message;
import org.springframework.integration.MessageChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.SubscribableChannel;
import org.springframework.integration.endpoint.EventDrivenConsumer;
import org.springframework.integration.endpoint.PollingConsumer;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.integration.xmpp.XmppHeaders;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author Oleg Zhurakousky
 * @author Mark Fisher
 */
@ContextConfiguration
@RunWith(SpringJUnit4ClassRunner.class)
public class ChatMessageOutboundChannelAdapterParserTests {

	@Autowired
	private ApplicationContext context;

	@Test
	public void testPollingConsumer() {
		Object pollingConsumer = context.getBean("outboundPollingAdapter");
		QueueChannel channel = (QueueChannel) TestUtils.getPropertyValue(pollingConsumer, "inputChannel");
		assertEquals("outboundPollingChannel", channel.getComponentName());
		assertTrue(pollingConsumer instanceof PollingConsumer);
	}

	@Test
	public void testEventConsumerWithNoChannel() {
		Object eventConsumer = context.getBean("outboundNoChannelAdapter");
		assertTrue(eventConsumer instanceof SubscribableChannel);
	}

	@Test
	public void testEventConsumer() {
		Object eventConsumer = context.getBean("outboundEventAdapter");
		assertTrue(eventConsumer instanceof EventDrivenConsumer);
	}

	@Test
	public void testPollingConsumerUsage() throws Exception{
		Object pollingConsumer = context.getBean("outboundPollingAdapter");
		assertTrue(pollingConsumer instanceof PollingConsumer);
		MessageChannel channel = context.getBean("outboundEventChannel", MessageChannel.class);
		Message<?> message = MessageBuilder.withPayload("hello").setHeader(XmppHeaders.CHAT_TO, "oleg").build();
		XMPPConnection connection = context.getBean("testConnection", XMPPConnection.class);
		channel.send(message);
		verify(connection, times(1)).sendPacket(Mockito.any(org.jivesoftware.smack.packet.Message.class));
	}

}
