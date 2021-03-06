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

package org.springframework.integration.channel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.integration.Message;
import org.springframework.integration.MessageRejectedException;
import org.springframework.integration.core.MessageHandler;
import org.springframework.integration.dispatcher.RoundRobinLoadBalancingStrategy;
import org.springframework.integration.dispatcher.UnicastingDispatcher;
import org.springframework.integration.message.GenericMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * @author Oleg Zhurakousky
 */
@RunWith(MockitoJUnitRunner.class)
public class MixedDispatcherConfigurationScenarioTests {

	private static final int TOTAL_EXECUTIONS = 40;

	private ThreadPoolTaskExecutor scheduler = new ThreadPoolTaskExecutor();

    private CountDownLatch allDone;
    private CountDownLatch start;
    private AtomicBoolean failed;

	@Mock
	private  List<Exception> exceptionRegistry;

	private ApplicationContext ac;

	@Mock
	private MessageHandler handlerA;

	@Mock
	private MessageHandler handlerB;

	@Mock
	private MessageHandler handlerC;

	private Message<?> message = new GenericMessage<String>("test");


	@Before
	public void initialize() throws Exception {
		ac = new ClassPathXmlApplicationContext("MixedDispatcherConfigurationScenarioTests-context.xml",
                MixedDispatcherConfigurationScenarioTests.class);
		allDone = new CountDownLatch(TOTAL_EXECUTIONS);
		start = new CountDownLatch(1);
		failed = new AtomicBoolean(false);
		scheduler.setCorePoolSize(10);
		scheduler.setMaxPoolSize(10);
		scheduler.initialize();
	}

	@Test
	public void noFailoverNoLoadBalancing() {
		DirectChannel channel = (DirectChannel) ac.getBean("noLoadBalancerNoFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA).handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		verify(handlerA, times(2)).handleMessage(message);
		verify(handlerB, times(0)).handleMessage(message);
	}

	@Test(timeout = 5000)
	public void noFailoverNoLoadBalancingConcurrent() throws Exception {
		final DirectChannel channel = (DirectChannel) ac.getBean("noLoadBalancerNoFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA).handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);

		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				boolean sent = false;
				try {
					sent = channel.send(message);
				} catch (Exception e) {
					exceptionRegistry.add(e);
				}
				if (!sent) {
					failed.set(true);
				}
				allDone.countDown();
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();
		allDone.await();
		assertTrue("not all messages were accepted", failed.get());
		verify(handlerA, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerB, times(0)).handleMessage(message);
		verify(exceptionRegistry, times(TOTAL_EXECUTIONS)).add((Exception) anyObject());
	}

	@Test(timeout = 5000)
	public void noFailoverNoLoadBalancingWithExecutorConcurrent()
			throws Exception {
		final ExecutorChannel channel = (ExecutorChannel) ac.getBean("noLoadBalancerNoFailoverExecutor");
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);

		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {			
				RuntimeException e = new RuntimeException();
				allDone.countDown();
				failed.set(true);
				exceptionRegistry.add(e);
				throw e;
			}
		}).when(handlerA).handleMessage(message);
		
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {			
				allDone.countDown();
				return null;
			}
		}).when(handlerB).handleMessage(message);
		
		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				channel.send(message);
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();
		allDone.await();
		// Mockito threads might still be lingering, so wait till they are all finished to avoid 
		// Mockito concurrency issues
		this.waitTillAllFinished((SimpleAsyncTaskExecutor) ac.getBean("taskExecutor"));
		assertTrue("not all messages were accepted", failed.get());
		verify(handlerA, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerB, times(0)).handleMessage(message);
		verify(exceptionRegistry, times(TOTAL_EXECUTIONS)).add((Exception) anyObject());
	}

	@Test
	public void noFailoverLoadBalancing() {
		DirectChannel channel = (DirectChannel) ac.getBean("loadBalancerNoFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA).handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.setLoadBalancingStrategy(new RoundRobinLoadBalancingStrategy());
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		dispatcher.addHandler(handlerC);
		InOrder inOrder = inOrder(handlerA, handlerB, handlerC);
		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		inOrder.verify(handlerA).handleMessage(message);
		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		inOrder.verify(handlerB).handleMessage(message);
		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		inOrder.verify(handlerC).handleMessage(message);

		verify(handlerA, times(1)).handleMessage(message);
		verify(handlerB, times(1)).handleMessage(message);
		verify(handlerC, times(1)).handleMessage(message);
	}

	@Test(timeout = 5000)
	public void noFailoverLoadBalancingConcurrent() throws Exception {
		final DirectChannel channel = (DirectChannel) ac.getBean("loadBalancerNoFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA).handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		dispatcher.addHandler(handlerC);

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch allDone = new CountDownLatch(TOTAL_EXECUTIONS);
		final Message<?> message = this.message;
		final AtomicBoolean failed = new AtomicBoolean(false);
		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				boolean sent = false;
				try {
					sent = channel.send(message);
				} catch (Exception e) {
					exceptionRegistry.add(e);
				}
				if (!sent) {
					failed.set(true);
				}
				allDone.countDown();
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();
		allDone.await();
		assertTrue("not all messages were accepted", failed.get());
		verify(handlerA, times(14)).handleMessage(message);
		verify(handlerB, times(13)).handleMessage(message);
		verify(handlerC, times(13)).handleMessage(message);
		verify(exceptionRegistry, times(14)).add((Exception) anyObject());
	}

	@Test(timeout = 5000)
	public void noFailoverLoadBalancingWithExecutorConcurrent() throws Exception {
		final ExecutorChannel channel = (ExecutorChannel) ac.getBean("loadBalancerNoFailoverExecutor");
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		dispatcher.addHandler(handlerC);

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch allDone = new CountDownLatch(TOTAL_EXECUTIONS);
		final Message<?> message = this.message;
		final AtomicBoolean failed = new AtomicBoolean(false);
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {		
				failed.set(true);
				RuntimeException e = new RuntimeException();
				exceptionRegistry.add(e);
				allDone.countDown();
				throw e;
			}
		}).when(handlerA).handleMessage(message);
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {
				allDone.countDown();
				return null;
			}
		}).when(handlerB).handleMessage(message);
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {
				allDone.countDown();
				return null;
			}
		}).when(handlerC).handleMessage(message);

		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				channel.send(message);
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();
		allDone.await();
		// Mockito threads might still be lingering, so wait till they are all finished to avoid 
		// Mockito concurrency
		this.waitTillAllFinished((SimpleAsyncTaskExecutor) ac.getBean("taskExecutor"));
		assertTrue("not all messages were accepted", failed.get());
		verify(handlerA, times(14)).handleMessage(message);
		verify(handlerB, times(13)).handleMessage(message);
		verify(handlerC, times(13)).handleMessage(message);
		verify(exceptionRegistry, times(14)).add((Exception) anyObject());
	}

	@Test
	public void failoverNoLoadBalancing() {
		DirectChannel channel = (DirectChannel) ac
				.getBean("noLoadBalancerFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA)
				.handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		InOrder inOrder = inOrder(handlerA, handlerB);

		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		inOrder.verify(handlerA).handleMessage(message);
		inOrder.verify(handlerB).handleMessage(message);

		try {
			channel.send(message);
		} catch (Exception e) {/* ignore */
		}
		inOrder.verify(handlerA).handleMessage(message);
		inOrder.verify(handlerB).handleMessage(message);

		verify(handlerA, times(2)).handleMessage(message);
		verify(handlerB, times(2)).handleMessage(message);
	}

	@Test(timeout = 5000)
	public void failoverNoLoadBalancingConcurrent()
			throws Exception {
		final DirectChannel channel = (DirectChannel) ac
				.getBean("noLoadBalancerFailover");
		doThrow(new MessageRejectedException(message)).when(handlerA).handleMessage(message);
		UnicastingDispatcher dispatcher = channel.getDispatcher();
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);
		dispatcher.addHandler(handlerC);

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch allDone = new CountDownLatch(TOTAL_EXECUTIONS);
		final Message<?> message = this.message;
		final AtomicBoolean failed = new AtomicBoolean(false);
		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				boolean sent = false;
				try {
					sent = channel.send(message);
				} catch (Exception e) {
					exceptionRegistry.add(e);
				}
				if (!sent) {
					failed.set(true);
				}
				allDone.countDown();
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();
		allDone.await();
		assertFalse("not all messages were accepted", failed.get());
		verify(handlerA, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerB, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerC, never()).handleMessage(message);
		verify(exceptionRegistry, never()).add((Exception) anyObject());
	}

	@Test(timeout = 5000)
	public void failoverNoLoadBalancingWithExecutorConcurrent() throws Exception {
		final ExecutorChannel channel = (ExecutorChannel) ac.getBean("noLoadBalancerFailoverExecutor");
		final UnicastingDispatcher dispatcher = channel.getDispatcher();	
		dispatcher.addHandler(handlerA);
		dispatcher.addHandler(handlerB);	
		dispatcher.addHandler(handlerC);	
		
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {			
				RuntimeException e = new RuntimeException();		
				failed.set(true);
				allDone.countDown();
				throw e;
			}
		}).when(handlerA).handleMessage(message);
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {		
				allDone.countDown();
				return null;
			}
		}).when(handlerB).handleMessage(message);
		doAnswer(new Answer<Object>() {
			public Object answer(InvocationOnMock invocation) {		
				allDone.countDown();
				return null;
			}
		}).when(handlerC).handleMessage(message);
		
		Runnable messageSenderTask = new Runnable() {
			public void run() {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				channel.send(message);
			}
		};
		for (int i = 0; i < TOTAL_EXECUTIONS; i++) {
			scheduler.execute(messageSenderTask);
		}
		start.countDown();	
		allDone.await();
		// Mockito threads might still be lingering, so wait till they are all finished to avoid 
		// Mockito concurrency
		this.waitTillAllFinished((SimpleAsyncTaskExecutor) ac.getBean("taskExecutor"));
	
		verify(handlerA, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerB, times(TOTAL_EXECUTIONS)).handleMessage(message);
		verify(handlerC, never()).handleMessage(message);
	}

	/**
	 * 
	 * @param taskExecutor
	 */
	private void waitTillAllFinished(SimpleAsyncTaskExecutor taskExecutor){
		for (int i = 0; i < 1000; i++) {
			if (taskExecutor.getThreadGroup().activeCount() == 0){
				break;
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {/*ignore*/}
		}
	}

}
