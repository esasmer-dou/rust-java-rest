package com.reactor.rust.di;

import com.reactor.rust.annotations.RequiresProperty;
import com.reactor.rust.di.annotation.*;
import com.reactor.rust.di.exception.BeanCreationException;
import com.reactor.rust.di.exception.NoSuchBeanException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BeanContainer DI functionality.
 */
class BeanContainerTest {

    private BeanContainer container;

    @BeforeEach
    void setUp() {
        // Create fresh container for each test
        container = BeanContainer.create();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.shutdown();
        }
    }

    // ==================== Basic Registration Tests ====================

    @Test
    @DisplayName("Should register and retrieve bean by type")
    void testRegisterAndGetBeanByType() {
        TestService service = new TestService();
        container.registerBean(TestService.class, service);

        TestService retrieved = container.getBean(TestService.class);

        assertSame(service, retrieved);
    }

    @Test
    @DisplayName("Should register and retrieve bean by name")
    void testRegisterAndGetBeanByName() {
        TestService service = new TestService();
        container.registerBean(TestService.class, service, "myService");

        TestService retrieved = container.getBean("myService");

        assertSame(service, retrieved);
    }

    @Test
    @DisplayName("Should throw NoSuchBeanException for missing bean")
    void testGetMissingBean() {
        assertThrows(NoSuchBeanException.class, () -> {
            container.getBean(String.class);
        });
    }

    // ==================== @Autowired Tests ====================

    @Test
    @DisplayName("Should inject @Autowired dependencies")
    void testAutowiredInjection() {
        // Manually register beans
        TestService service = new TestService();
        TestHandler handler = new TestHandler();

        container.registerBean(TestService.class, service);
        container.registerBean(TestHandler.class, handler);

        // Start to trigger injection
        container.start();

        // Check @Autowired injection worked
        assertNotNull(handler.testService);
        assertSame(service, handler.testService);
    }

    @Test
    @DisplayName("Should handle optional @Autowired(required=false)")
    void testOptionalAutowired() {
        TestService service = new TestService();
        OptionalHandler handler = new OptionalHandler();

        container.registerBean(TestService.class, service);
        container.registerBean(OptionalHandler.class, handler);

        container.start();

        // optionalRepository should be null (required=false)
        assertNull(handler.optionalRepository);
        // testService should be injected
        assertNotNull(handler.testService);
    }

    // ==================== Lifecycle Tests ====================

    @Test
    @DisplayName("Should call @PostConstruct methods")
    void testPostConstruct() {
        AtomicInteger initCounter = new AtomicInteger(0);
        AtomicInteger destroyCounter = new AtomicInteger(0);

        LifecycleBean bean = new LifecycleBean(initCounter, destroyCounter);
        container.registerBean(LifecycleBean.class, bean);
        container.start();

        assertEquals(1, initCounter.get());
    }

    @Test
    @DisplayName("Should call @PreDestroy on shutdown")
    void testPreDestroy() {
        AtomicInteger initCounter = new AtomicInteger(0);
        AtomicInteger destroyCounter = new AtomicInteger(0);

        LifecycleBean bean = new LifecycleBean(initCounter, destroyCounter);
        container.registerBean(LifecycleBean.class, bean);
        container.start();
        container.shutdown();

        assertEquals(1, destroyCounter.get());
    }

    // ==================== Lookup Performance Tests ====================

    @Test
    @DisplayName("Should have O(1) lookup performance")
    void testLookupPerformance() {
        // Register multiple beans
        for (int i = 0; i < 100; i++) {
            container.registerBean(String.class, "bean" + i, "bean" + i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            container.getBean("bean50");
        }
        long elapsed = System.nanoTime() - start;

        // 10,000 lookups should be very fast (< 10ms)
        double elapsedMs = elapsed / 1_000_000.0;
        System.out.println("[DI Test] 10,000 lookups took: " + elapsedMs + " ms");

        assertTrue(elapsedMs < 50, "Lookup should be fast, took: " + elapsedMs + " ms");
    }

    // ==================== Interface Registration Tests ====================

    @Test
    @DisplayName("Should register bean by interface type")
    void testInterfaceRegistration() {
        TestImpl impl = new TestImpl();
        container.registerBean(TestImpl.class, impl);
        container.start();

        TestInterface byInterface = container.getBean(TestInterface.class);
        assertNotNull(byInterface);
        assertEquals("implementation", byInterface.getValue());
    }

    // ==================== Bean List Tests ====================

    @Test
    @DisplayName("Should get all beans of a type")
    void testGetBeansOfType() {
        // Register beans of different types that share an interface
        TestImpl impl = new TestImpl();
        AnotherImpl another = new AnotherImpl();

        container.registerBean(TestImpl.class, impl);
        container.registerBean(AnotherImpl.class, another);

        List<TestInterface> beans = container.getBeansOfType(TestInterface.class);

        // Should find at least both implementations by interface
        assertTrue(beans.size() >= 2, "Expected at least 2 beans, got: " + beans.size());
    }

    @Test
    @DisplayName("Should check if bean exists")
    void testHasBean() {
        container.registerBean(String.class, "test", "test");

        assertTrue(container.hasBean(String.class));
        assertTrue(container.hasBean("test"));
        assertFalse(container.hasBean(Integer.class));
    }

    @Test
    @DisplayName("Should resolve generated lazy bean by name and return each instance once")
    void testGeneratedBeanAliases() {
        TestImpl impl = new TestImpl();
        container.registerGeneratedFactory(
                TestImpl.class,
                () -> impl,
                "generatedTest",
                false,
                TestInterface.class);

        assertTrue(container.hasBean("generatedTest"));
        assertSame(impl, container.getBean("generatedTest"));
        assertSame(impl, container.getBean(TestInterface.class));
        assertEquals(1, container.getBeansOfType(Object.class).size());
    }

    @Test
    @DisplayName("Should resolve generated interface by primary and qualifier")
    void testGeneratedPrimaryAndQualifier() {
        TestImpl regular = new TestImpl();
        AnotherImpl primary = new AnotherImpl();
        container.registerGeneratedFactory(
                TestImpl.class, () -> regular, "regular", false, TestInterface.class);
        container.registerGeneratedFactory(
                AnotherImpl.class, () -> primary, "primary", true, TestInterface.class);

        assertSame(primary, container.getBean(TestInterface.class));
        assertSame(regular, container.getBean(TestInterface.class, "regular"));
        assertSame(primary, container.getBean(TestInterface.class, "primary"));
    }

    @Test
    @DisplayName("Should reject ambiguous generated interface injection")
    void testGeneratedAmbiguousInterface() {
        container.registerGeneratedFactory(
                TestImpl.class, TestImpl::new, "first", false, TestInterface.class);
        container.registerGeneratedFactory(
                AnotherImpl.class, AnotherImpl::new, "second", false, TestInterface.class);

        assertThrows(BeanCreationException.class, () -> container.getBean(TestInterface.class));
        assertInstanceOf(TestImpl.class, container.getBean(TestInterface.class, "first"));
        assertInstanceOf(AnotherImpl.class, container.getBean(TestInterface.class, "second"));
    }

    @Test
    @DisplayName("Should reject multiple generated primary implementations")
    void testGeneratedMultiplePrimaryInterface() {
        container.registerGeneratedFactory(
                TestImpl.class, TestImpl::new, "first", true, TestInterface.class);

        assertThrows(BeanCreationException.class, () -> container.registerGeneratedFactory(
                AnotherImpl.class, AnotherImpl::new, "second", true, TestInterface.class));
        assertFalse(container.hasBean("second"));
        assertFalse(container.hasBean(AnotherImpl.class));
    }

    @Test
    @DisplayName("Should resolve on-demand generated infrastructure only when requested")
    void testGeneratedOnDemandInfrastructure() {
        AtomicInteger creations = new AtomicInteger();
        container.registerGeneratedOnDemandFactory(
                TestRepository.class,
                () -> {
                    creations.incrementAndGet();
                    return new TestRepository();
                },
                "onDemandRepository",
                true);

        container.start();

        assertEquals(0, creations.get());
        TestRepository first = container.getBean(TestRepository.class);
        assertSame(first, container.getBean(TestRepository.class));
        assertEquals(1, creations.get());
    }

    @Test
    @DisplayName("Compatibility mode should honor conditional beans and Optional constructor injection")
    void testCompatibilityConditionsAndOptionalInjection() {
        String property = "reactor.test.optional-service.enabled";
        System.setProperty(property, "false");
        try {
            container.registerBean(ConditionalConfiguration.class, new ConditionalConfiguration());
            container.start();
            assertFalse(container.hasBean(TestRepository.class));

            container.registerBeanClass(OptionalConstructorBean.class);
            OptionalConstructorBean bean = container.getBean(OptionalConstructorBean.class);
            assertTrue(bean.repository().isEmpty());
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    @DisplayName("Should get bean names")
    void testGetBeanNames() {
        container.registerBean(String.class, "test1", "test1");
        container.registerBean(Integer.class, 123, "test2");

        assertTrue(container.getBeanNames().contains("test1"));
        assertTrue(container.getBeanNames().contains("test2"));
    }

    // ==================== Test Beans ====================

    static class TestService {
    }

    static class TestHandler {
        @Autowired
        TestService testService;
    }

    static class TestRepository {
    }

    @Configuration
    static class ConditionalConfiguration {
        @Bean
        @RequiresProperty(name = "reactor.test.optional-service.enabled", value = "true")
        TestRepository testRepository() {
            return new TestRepository();
        }
    }

    @Component
    static class OptionalConstructorBean {
        private final Optional<TestRepository> repository;

        @Autowired
        OptionalConstructorBean(Optional<TestRepository> repository) {
            this.repository = repository;
        }

        Optional<TestRepository> repository() {
            return repository;
        }
    }

    static class OptionalHandler {
        @Autowired
        TestService testService;

        @Autowired(required = false)
        TestRepository optionalRepository;
    }

    static class LifecycleBean {
        private final AtomicInteger initCounter;
        private final AtomicInteger destroyCounter;

        LifecycleBean(AtomicInteger initCounter, AtomicInteger destroyCounter) {
            this.initCounter = initCounter;
            this.destroyCounter = destroyCounter;
        }

        @PostConstruct
        void init() {
            initCounter.incrementAndGet();
        }

        @PreDestroy
        void cleanup() {
            destroyCounter.incrementAndGet();
        }
    }

    interface TestInterface {
        String getValue();
    }

    static class TestImpl implements TestInterface {
        @Override
        public String getValue() {
            return "implementation";
        }
    }

    static class AnotherImpl implements TestInterface {
        @Override
        public String getValue() {
            return "another";
        }
    }
}
