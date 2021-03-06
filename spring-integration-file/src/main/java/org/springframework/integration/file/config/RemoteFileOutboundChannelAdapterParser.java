/*
 * Copyright 2002-2011 the original author or authors.
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

package org.springframework.integration.file.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.config.ExpressionFactoryBean;
import org.springframework.integration.config.xml.AbstractOutboundChannelAdapterParser;
import org.springframework.integration.config.xml.IntegrationNamespaceUtils;
import org.springframework.integration.file.DefaultFileNameGenerator;
import org.springframework.integration.file.remote.handler.FileTransferringMessageHandler;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;

/**
 * @author Oleg Zhurakousky
 * @author Mark Fisher
 * @since 2.0
 */
public class RemoteFileOutboundChannelAdapterParser extends AbstractOutboundChannelAdapterParser {
	private final Log logger = LogFactory.getLog(this.getClass());
	@Override
	protected AbstractBeanDefinition parseConsumer(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder handlerBuilder = BeanDefinitionBuilder.genericBeanDefinition(FileTransferringMessageHandler.class);

		// This whole block must be refactored once cache-session attribute is removed
		String sessionFactoryName = element.getAttribute("session-factory");
		BeanDefinition sessionFactoryDefinition = parserContext.getReaderContext().getRegistry().getBeanDefinition(sessionFactoryName);
		String sessionFactoryClassName = sessionFactoryDefinition.getBeanClassName();
		if (StringUtils.hasText(sessionFactoryClassName) && sessionFactoryClassName.endsWith(CachingSessionFactory.class.getName())){
			handlerBuilder.addConstructorArgValue(sessionFactoryDefinition);
		}
		else {
			String cacheSessions = element.getAttribute("cache-sessions");
			if (StringUtils.hasText(cacheSessions)){
				logger.warn("The 'cache-sessions' attribute is deprecated since v2.1. Consider configuring CachingSessionFactory explicitly");	
			}
			if ("false".equalsIgnoreCase(cacheSessions)) {
				handlerBuilder.addConstructorArgReference(element.getAttribute("session-factory"));
			}
			else {		
				BeanDefinitionBuilder sessionFactoryBuilder = BeanDefinitionBuilder.genericBeanDefinition(CachingSessionFactory.class);		
				sessionFactoryBuilder.addConstructorArgReference(sessionFactoryName);
				handlerBuilder.addConstructorArgValue(sessionFactoryBuilder.getBeanDefinition());
			}
		}
				// end of what needs to be refactored once cache-session is removed

		// configure MessageHandler properties
		IntegrationNamespaceUtils.setValueIfAttributeDefined(handlerBuilder, element, "temporary-file-suffix");
		IntegrationNamespaceUtils.setValueIfAttributeDefined(handlerBuilder, element, "auto-create-directory");

		this.configureRemoteDirectories(element, handlerBuilder);
		
		// configure remote FileNameGenerator
		String remoteFileNameGenerator = element.getAttribute("remote-filename-generator");
		String remoteFileNameGeneratorExpression = element.getAttribute("remote-filename-generator-expression");
		boolean hasRemoteFileNameGenerator = StringUtils.hasText(remoteFileNameGenerator);
		boolean hasRemoteFileNameGeneratorExpression = StringUtils.hasText(remoteFileNameGeneratorExpression);
		if (hasRemoteFileNameGenerator || hasRemoteFileNameGeneratorExpression) {
			if (hasRemoteFileNameGenerator && hasRemoteFileNameGeneratorExpression) {
				throw new BeanDefinitionStoreException("at most one of 'remote-filename-generator-expression' or 'remote-filename-generator' " +
						"is allowed on a remote file outbound adapter");
			}
			if (hasRemoteFileNameGenerator) {
				handlerBuilder.addPropertyReference("fileNameGenerator", remoteFileNameGenerator);
			}
			else {
				BeanDefinitionBuilder fileNameGeneratorBuilder = BeanDefinitionBuilder.genericBeanDefinition(DefaultFileNameGenerator.class);
				fileNameGeneratorBuilder.addPropertyValue("expression", remoteFileNameGeneratorExpression);
				handlerBuilder.addPropertyValue("fileNameGenerator", fileNameGeneratorBuilder.getBeanDefinition());
			}
		}
		IntegrationNamespaceUtils.setValueIfAttributeDefined(handlerBuilder, element, "charset");
		IntegrationNamespaceUtils.setValueIfAttributeDefined(handlerBuilder, element, "remote-file-separator");
		return handlerBuilder.getBeanDefinition();
	}
	
	private void configureRemoteDirectories(Element element, BeanDefinitionBuilder handlerBuilder){
		this.doConfigureRemoteDirectory(element, handlerBuilder, "remote-directory", "remote-directory-expression", "remoteDirectoryExpression", true);
		this.doConfigureRemoteDirectory(element, handlerBuilder, "temporary-remote-directory", "temporary-remote-directory-expression", "temporaryRemoteDirectoryExpression", false);
	}
	
	private void doConfigureRemoteDirectory(Element element, BeanDefinitionBuilder handlerBuilder, 
			                                String directoryAttribute, String directoryExpressionAttribute, 
			                                String directoryExpressionPropertyName, boolean atLeastOneRequired){
		String remoteDirectory = element.getAttribute(directoryAttribute);
		String remoteDirectoryExpression = element.getAttribute(directoryExpressionAttribute);
		boolean hasRemoteDirectory = StringUtils.hasText(remoteDirectory);
		boolean hasRemoteDirectoryExpression = StringUtils.hasText(remoteDirectoryExpression);
		if (atLeastOneRequired){
			if (!(hasRemoteDirectory ^ hasRemoteDirectoryExpression)) {
				throw new BeanDefinitionStoreException("exactly one of '" + directoryAttribute + "' or '" + directoryExpressionAttribute + "' " +
						"is required on a remote file outbound adapter");
			}
		}
		
		BeanDefinition remoteDirectoryExpressionDefinition = null;
		if (hasRemoteDirectory) {
			remoteDirectoryExpressionDefinition = new RootBeanDefinition(LiteralExpression.class);
			remoteDirectoryExpressionDefinition.getConstructorArgumentValues().addGenericArgumentValue(remoteDirectory);
		}
		else if (hasRemoteDirectoryExpression) {
			remoteDirectoryExpressionDefinition = new RootBeanDefinition(ExpressionFactoryBean.class);	
			remoteDirectoryExpressionDefinition.getConstructorArgumentValues().addGenericArgumentValue(remoteDirectoryExpression);
		}
		if (remoteDirectoryExpressionDefinition != null){
			handlerBuilder.addPropertyValue(directoryExpressionPropertyName, remoteDirectoryExpressionDefinition);
		}
	}

}
