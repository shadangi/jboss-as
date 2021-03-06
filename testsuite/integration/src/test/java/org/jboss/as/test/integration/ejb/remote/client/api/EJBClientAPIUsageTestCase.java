/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.ejb.remote.client.api;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.test.integration.ejb.remote.common.AnonymousCallbackHandler;
import org.jboss.as.test.integration.ejb.remote.common.EJBRemoteManagementUtil;
import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.client.EJBClientTransactionContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.client.remoting.IoFutureHelper;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.Remoting;
import org.jboss.remoting3.remote.RemoteConnectionProviderFactory;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Options;

/**
 * Tests the various common use cases of the EJB remote client API
 * <p/>
 * User: Jaikiran Pai
 */
@RunWith(Arquillian.class)
@RunAsClient
public class EJBClientAPIUsageTestCase {

    private static final Logger logger = Logger.getLogger(EJBClientAPIUsageTestCase.class);

    private static Connection connection;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String APP_NAME = "ejb-remote-client-api-test";

    private static final String MODULE_NAME = "ejb";

    private EJBClientContext ejbClientContext;

    /**
     * Creates an EJB deployment
     *
     * @return
     */
    @Deployment
    public static Archive<?> createDeployment() {
        final EnterpriseArchive ear = ShrinkWrap.create(EnterpriseArchive.class, APP_NAME + ".ear");

        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, MODULE_NAME + ".jar");
        jar.addPackage(EJBClientAPIUsageTestCase.class.getPackage());
        jar.addClass(AnonymousCallbackHandler.class);

        ear.addAsModule(jar);

        return ear;
    }


    /**
     * Create and setup the remoting connection
     *
     * @throws Exception
     */
    @BeforeClass
    public static void beforeTestClass() throws Exception {
        final Endpoint endpoint = Remoting.createEndpoint("ejb-remote-client-endpoint", OptionMap.EMPTY);
        endpoint.addConnectionProvider("remote", new RemoteConnectionProviderFactory(), OptionMap.create(Options.SSL_ENABLED, Boolean.FALSE));


        // open a connection
        final int ejbRemotingPort = EJBRemoteManagementUtil.getEJBRemoteConnectorPort("localhost", 9999);
        final IoFuture<Connection> futureConnection = endpoint.connect(new URI("remote://localhost:" + ejbRemotingPort), OptionMap.create(Options.SASL_POLICY_NOANONYMOUS, Boolean.FALSE), new AnonymousCallbackHandler());
        connection = IoFutureHelper.get(futureConnection, 5, TimeUnit.SECONDS);
    }

    @AfterClass
    public static void afterTestClass() throws Exception {
        executor.shutdown();
    }

    /**
     * Create and setup the EJB client context backed by the remoting receiver
     *
     * @throws Exception
     */
    @Before
    public void beforeTest() throws Exception {
        this.ejbClientContext = EJBClientContext.create();
        this.ejbClientContext.registerConnection(connection);
        final EJBClientTransactionContext localUserTxContext = EJBClientTransactionContext.createLocal();
        // set the tx context
        EJBClientTransactionContext.setGlobalContext(localUserTxContext);

    }

    @After
    public void afterTest() throws Exception {
        if (this.ejbClientContext != null) {
            EJBClientContext.suspendCurrent();
        }
    }

    /**
     * Test a simple invocation on a remote view of a Stateless session bean method
     *
     * @throws Exception
     */
    @Test
    public void testRemoteSLSBInvocation() throws Exception {
        final StatelessEJBLocator<EchoRemote> locator = new StatelessEJBLocator(EchoRemote.class, APP_NAME, MODULE_NAME, EchoBean.class.getSimpleName(), "");
        final EchoRemote proxy = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", proxy);
        final String message = "Hello world from a really remote client";
        final String echo = proxy.echo(message);
        Assert.assertEquals("Unexpected echo message", message, echo);
    }

    /**
     * Test a invocation on the remote view of a stateless bean which is configured for user interceptors
     *
     * @throws Exception
     */
    @Test
    public void testRemoteSLSBWithInterceptors() throws Exception {
        final StatelessEJBLocator<EchoRemote> locator = new StatelessEJBLocator(EchoRemote.class, APP_NAME, MODULE_NAME, InterceptedEchoBean.class.getSimpleName(), "");
        final EchoRemote proxy = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", proxy);
        final String message = "Hello world from a really remote client";
        final String echo = proxy.echo(message);
        final String expectedEcho = message + InterceptorTwo.MESSAGE_SEPARATOR + InterceptorOne.class.getSimpleName() + InterceptorOne.MESSAGE_SEPARATOR + InterceptorTwo.class.getSimpleName();
        Assert.assertEquals("Unexpected echo message", expectedEcho, echo);
    }

    /**
     * Test a invocation on a stateless bean method which accepts and returns custom objectss
     *
     * @throws Exception
     */
    @Test
    public void testRemoteSLSBWithCustomObjects() throws Exception {
        final StatelessEJBLocator<EmployeeManager> locator = new StatelessEJBLocator(EmployeeManager.class, APP_NAME, MODULE_NAME, EmployeeBean.class.getSimpleName(), "");
        final EmployeeManager proxy = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", proxy);
        final String[] nickNames = new String[]{"java-programmer", "ruby-programmer", "php-programmer"};
        final Employee employee = new Employee(1, "programmer");
        // invoke on the bean
        final AliasedEmployee employeeWithNickNames = proxy.addNickNames(employee, nickNames);

        // check the id of the returned employee
        Assert.assertEquals("Unexpected employee id", 1, employeeWithNickNames.getId());
        // check the name of the returned employee
        Assert.assertEquals("Unexpected employee name", "programmer", employeeWithNickNames.getName());
        // check the number of nicknames
        Assert.assertEquals("Unexpected number of nick names", nickNames.length, employeeWithNickNames.getNickNames().size());
        // make sure the correct nick names are present
        for (int i = 0; i < nickNames.length; i++) {
            Assert.assertTrue("Employee was expected to have nick name: " + nickNames[i], employeeWithNickNames.getNickNames().contains(nickNames[i]));
        }
    }

    /**
     * Tests that invocations on a stateful session bean work after a session is created and the stateful
     * session bean really acts as a stateful bean
     *
     * @throws Exception
     */
    @Test
    public void testSFSBInvocation() throws Exception {
        // open a session for the SFSB
        final SessionID sessionID = EJBClient.createSession(APP_NAME, MODULE_NAME, CounterBean.class.getSimpleName(), "");
        final StatefulEJBLocator<Counter> locator = new StatefulEJBLocator<Counter>(Counter.class, APP_NAME, MODULE_NAME, CounterBean.class.getSimpleName(), "", sessionID);
        final Counter counter = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", counter);
        // invoke the bean
        final int initialCount = counter.getCount();
        logger.info("Got initial count " + initialCount);
        Assert.assertEquals("Unexpected initial count from stateful bean", 0, initialCount);
        final int NUM_TIMES = 50;
        for (int i = 1; i <= NUM_TIMES; i++) {
            final int count = counter.incrementAndGetCount();
            logger.info("Got next count " + count);
            Assert.assertEquals("Unexpected count after increment", i, count);
        }
        final int finalCount = counter.getCount();
        logger.info("Got final count " + finalCount);
        Assert.assertEquals("Unexpected final count", NUM_TIMES, finalCount);
    }


    /**
     * Tests that invocation on a stateful session bean fails, if a session hasn't been created
     *
     * @throws Exception
     */
    @Test
    @Ignore ("No longer appropriate, since a proxy can no longer be created without a session, for a SFSB. " +
            "Need to think if there's a different way to test this. Else just remove this test")
    public void testSFSBAccessFailureWithoutSession() throws Exception {
        // create a locator without a session
        final StatefulEJBLocator<Counter> locator = new StatefulEJBLocator<Counter>(Counter.class, APP_NAME, MODULE_NAME, CounterBean.class.getSimpleName(), "", null);
        final Counter counter = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", counter);
        // invoke the bean without creating a session
        try {
            final int initialCount = counter.getCount();
            Assert.fail("Expected a EJBException for calling a stateful session bean without creating a session");
        } catch (EJBException ejbe) {
            // expected
            logger.info("Received the expected exception", ejbe);

        }
    }

    /**
     * Tests that invoking a non-existent EJB leads to a {@link NoSuchEJBException}
     *
     * @throws Exception
     */
    @Test
    public void testNonExistentEJBAccess() throws Exception {
        final StatelessEJBLocator<NotAnEJBInterface> locator = new StatelessEJBLocator<NotAnEJBInterface>(NotAnEJBInterface.class, "non-existen-app-name", MODULE_NAME, "blah", "");
        final NotAnEJBInterface nonExistentBean = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", nonExistentBean);
        // invoke on the (non-existent) bean
        try {
            nonExistentBean.echo("Hello world to a non-existent bean");
            Assert.fail("Expected a NoSuchEJBException");
        } catch (NoSuchEJBException nsee) {
            // expected
            logger.info("Received the expected exception", nsee);
        }
    }

    /**
     * Tests that the invocation on a non-existent view of an (existing) EJB leads to a {@link NoSuchEJBException}
     *
     * @throws Exception
     */
    @Test
    public void testNonExistentViewForEJB() throws Exception {
        final StatelessEJBLocator<NotAnEJBInterface> locator = new StatelessEJBLocator<NotAnEJBInterface>(NotAnEJBInterface.class, APP_NAME, MODULE_NAME, EchoBean.class.getSimpleName(), "");
        final NotAnEJBInterface nonExistentBean = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", nonExistentBean);
        // invoke on the (non-existent) view of a bean
        try {
            nonExistentBean.echo("Hello world to a non-existent view of a bean");
            Assert.fail("Expected a NoSuchEJBException");
        } catch (NoSuchEJBException nsee) {
            // expected
            logger.info("Received the expected exception", nsee);
        }
    }

    /**
     * Tests that an {@link javax.ejb.ApplicationException} thrown by a SLSB method is returned back to the
     * client correctly
     *
     * @throws Exception
     */
    @Test
    public void testApplicationExceptionOnSLSBMethod() throws Exception {
        final StatelessEJBLocator<ExceptionThrowingRemote> locator = new StatelessEJBLocator<ExceptionThrowingRemote>(ExceptionThrowingRemote.class, APP_NAME, MODULE_NAME, ExceptionThrowingBean.class.getSimpleName(), "");
        final ExceptionThrowingRemote exceptionThrowingBean = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", exceptionThrowingBean);
        final String exceptionState = "2342348723Dsbjlfjal#";
        try {
            exceptionThrowingBean.alwaysThrowApplicationException(exceptionState);
            Assert.fail("Expected a " + StatefulApplicationException.class.getName() + " exception");
        } catch (StatefulApplicationException sae) {
            // expected
            logger.info("Received the expected exception", sae);
            Assert.assertEquals("Unexpected state in the application exception", exceptionState, sae.getState());
        }
    }

    /**
     * Tests that a system exception thrown from a SLSB method is conveyed back to the client
     *
     * @throws Exception
     */
    @Test
    public void testSystemExceptionOnSLSBMethod() throws Exception {
        final StatelessEJBLocator<ExceptionThrowingRemote> locator = new StatelessEJBLocator<ExceptionThrowingRemote>(ExceptionThrowingRemote.class, APP_NAME, MODULE_NAME, ExceptionThrowingBean.class.getSimpleName(), "");
        final ExceptionThrowingRemote exceptionThrowingBean = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", exceptionThrowingBean);
        final String exceptionState = "bafasfaj;l";
        try {
            exceptionThrowingBean.alwaysThrowSystemException(exceptionState);
            Assert.fail("Expected a " + EJBException.class.getName() + " exception");
        } catch (EJBException ejbe) {
            // expected
            logger.info("Received the expected exception", ejbe);
            final Throwable cause = ejbe.getCause();
            Assert.assertTrue("Unexpected cause in EJBException", cause instanceof RuntimeException);
            Assert.assertEquals("Unexpected state in the system exception", exceptionState, cause.getMessage());
        }
    }

    /**
     * Tests that a SLSB method which is marked as asynchronous and returns a {@link java.util.concurrent.Future}
     * is invoked asynchronously and the client isn't blocked for the lifetime of the method
     *
     * @throws Exception
     */
    @Test
    public void testAsyncFutureMethodOnSLSB() throws Exception {
        final StatelessEJBLocator<EchoRemote> locator = new StatelessEJBLocator<EchoRemote>(EchoRemote.class, APP_NAME, MODULE_NAME, EchoBean.class.getSimpleName(), "");
        final EchoRemote echoRemote = EJBClient.createProxy(locator);
        Assert.assertNotNull("Received a null proxy", echoRemote);
        final String message = "You are supposed to be an asynchronous method";
        final long DELAY = 5000;
        final long start = System.currentTimeMillis();
        // invoke the asynchronous method
        final Future<String> futureEcho = echoRemote.asyncEcho(message, DELAY);
        final long end = System.currentTimeMillis();
        logger.info("Asynchronous invocation returned a Future: " + futureEcho + " in " + (end - start) + " milli seconds");
        // test that the invocation did not act like a synchronous invocation and instead returned "immediately"
        Assert.assertFalse("Asynchronous invocation behaved like a synchronous invocation", (end - start) >= DELAY);
        Assert.assertNotNull("Future is null", futureEcho);
        // Check if the result is marked as complete (it shouldn't be this soon)
        Assert.assertFalse("Future result is unexpectedly completed", futureEcho.isDone());
        // wait for the result
        final String echo = futureEcho.get();
        Assert.assertEquals("Unexpected echo message", message, echo);
    }


    /**
     * Test a simple invocation on a remote view of a Stateless session bean method
     *
     * @throws Exception
     */
    @Test
    public void testGetBusinessObjectRemote() throws Exception {
        final StatelessEJBLocator<EchoRemote> locator = new StatelessEJBLocator(EchoRemote.class, APP_NAME, MODULE_NAME, EchoBean.class.getSimpleName(), "");
        final EchoRemote proxy = EJBClient.createProxy(locator);
        final EchoRemote getBusinessObjectProxy = proxy.getBusinessObject();
        Assert.assertNotNull("Received a null proxy", getBusinessObjectProxy);
        final String message = "Hello world from a really remote client";
        final String echo = getBusinessObjectProxy.echo(message);
        Assert.assertEquals("Unexpected echo message", message, echo);
    }
}
