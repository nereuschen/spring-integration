/*
 * Copyright 2002-2009 the original author or authors.
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

package org.springframework.integration.config.xml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

/**
 * Parser for the &lt;channel&gt; element.
 * 
 * @author Mark Fisher
 * @author Iwein Fuld
 * @author Oleg Zhurakousky
 */
public class PointToPointChannelParser extends AbstractChannelParser {

	private static final String CHANNEL_PACKAGE = IntegrationNamespaceUtils.BASE_PACKAGE + ".channel";

	//private static final String DISPATCHER_PACKAGE = IntegrationNamespaceUtils.BASE_PACKAGE + ".dispatcher";

	private static final String STORE_PACKAGE = IntegrationNamespaceUtils.BASE_PACKAGE + ".store";

	private final Log logger = LogFactory.getLog(this.getClass());

	@Override
	protected BeanDefinitionBuilder buildBeanDefinition(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder builder = null;
		Element queueElement = null;

		// configure a queue-based channel if any queue sub-element is defined
		if ((queueElement = DomUtils.getChildElementByTagName(element, "queue")) != null) {
			builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".QueueChannel");
			boolean hasStoreRef = this.parseStoreRef(builder, queueElement, element.getAttribute(ID_ATTRIBUTE));
			boolean hasQueueRef = this.parseQueueRef(builder, queueElement);
			if (!hasStoreRef) {
				boolean hasCapacity = this.parseQueueCapacity(builder, queueElement);
				if (hasCapacity && hasQueueRef) {
					parserContext.getReaderContext().error(
							"The 'capacity' attribute is not allowed" + " when providing a 'ref' to a custom queue.",
							element);
				}
			}
			if (hasStoreRef && hasQueueRef) {
				parserContext.getReaderContext().error(
						"The 'message-store' attribute is not allowed" + " when providing a 'ref' to a custom queue.",
						element);
			}
		}
		else if ((queueElement = DomUtils.getChildElementByTagName(element, "priority-queue")) != null) {
			builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".PriorityChannel");
			this.parseQueueCapacity(builder, queueElement);
			String comparatorRef = queueElement.getAttribute("comparator");
			if (StringUtils.hasText(comparatorRef)) {
				builder.addConstructorArgReference(comparatorRef);
			}
		}
		else if ((queueElement = DomUtils.getChildElementByTagName(element, "rendezvous-queue")) != null) {
			builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".RendezvousChannel");
		}

		Element dispatcherElement = DomUtils.getChildElementByTagName(element, "dispatcher");

		// check for the dispatcher attribute (deprecated)
		String dispatcherAttribute = element.getAttribute("dispatcher");
		boolean hasDispatcherAttribute = StringUtils.hasText(dispatcherAttribute);
		if (hasDispatcherAttribute && logger.isWarnEnabled()) {
			logger.warn("The 'dispatcher' attribute on the 'channel' element is deprecated. "
					+ "Please use the 'dispatcher' sub-element instead.");
		}

		// verify that a dispatcher is not provided if a queue sub-element exists
		if (queueElement != null && (dispatcherElement != null || hasDispatcherAttribute)) {
			parserContext.getReaderContext().error(
					"The 'dispatcher' attribute or sub-element " + "and any queue sub-element are mutually exclusive.",
					element);
			return null;
		}

		if (queueElement != null) {
			return builder;
		}

		if (dispatcherElement != null && hasDispatcherAttribute) {
			parserContext.getReaderContext().error(
					"The 'dispatcher' attribute and 'dispatcher' "
							+ "sub-element are mutually exclusive. NOTE: the attribute is DEPRECATED. "
							+ "Please use the dispatcher sub-element instead.", element);
			return null;
		}

		if (hasDispatcherAttribute) {
			// this attribute is deprecated, but if set, we need to create a DirectChannel
			// without any LoadBalancerStrategy and the failover flag set to true (default).
			builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".DirectChannel");
			if ("failover".equals(dispatcherAttribute)) {
				// round-robin dispatcher is used by default, the "failover" value simply disables it
				builder.addConstructorArgValue(null);
			} 
		}
		else if (dispatcherElement == null) {
			// configure the default DirectChannel with a RoundRobinLoadBalancingStrategy
			builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".DirectChannel");
		}
		else {
			// configure either an ExecutorChannel or DirectChannel based on existence of 'task-executor'
			String taskExecutor = dispatcherElement.getAttribute("task-executor");
			if (StringUtils.hasText(taskExecutor)) {
				builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".ExecutorChannel");
				builder.addConstructorArgReference(taskExecutor);
			}
			else {
				builder = BeanDefinitionBuilder.genericBeanDefinition(CHANNEL_PACKAGE + ".DirectChannel");
			}
			// unless the 'load-balancer' attribute is explicitly set to 'none',
			// configure the default RoundRobinLoadBalancingStrategy
			String loadBalancer = dispatcherElement.getAttribute("load-balancer");
			if ("none".equals(loadBalancer)) {
				builder.addConstructorArgValue(null);
			} 
			IntegrationNamespaceUtils.setValueIfAttributeDefined(builder, dispatcherElement, "failover");
		}
		return builder;
	}

	private boolean parseQueueCapacity(BeanDefinitionBuilder builder, Element queueElement) {
		String capacity = queueElement.getAttribute("capacity");
		if (StringUtils.hasText(capacity)) {
			builder.addConstructorArgValue(capacity);
			return true;
		}
		return false;
	}

	private boolean parseQueueRef(BeanDefinitionBuilder builder, Element queueElement) {
		String queueRef = queueElement.getAttribute("ref");
		if (StringUtils.hasText(queueRef)) {
			builder.addConstructorArgReference(queueRef);
			return true;
		}
		return false;
	}

	private boolean parseStoreRef(BeanDefinitionBuilder builder, Element queueElement, String channel) {
		String storeRef = queueElement.getAttribute("message-store");
		if (StringUtils.hasText(storeRef)) {
			BeanDefinitionBuilder queueBuilder = BeanDefinitionBuilder
					.genericBeanDefinition(STORE_PACKAGE + ".MessageGroupQueue");
			queueBuilder.addConstructorArgReference(storeRef);
			queueBuilder.addConstructorArgValue(STORE_PACKAGE + ":" + channel);
			parseQueueCapacity(queueBuilder, queueElement);
			builder.addConstructorArgValue(queueBuilder.getBeanDefinition());
			return true;
		}
		return false;
	}

}
