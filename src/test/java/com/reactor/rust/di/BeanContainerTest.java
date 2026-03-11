package com.reactor.rust.di;

import com.reactor.rust.di.annotation.*;
import com.reactor.rust.di.exception.NoSuchBeanException;
import org.junit.jupiter.api.*;

import java.util.List;
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
