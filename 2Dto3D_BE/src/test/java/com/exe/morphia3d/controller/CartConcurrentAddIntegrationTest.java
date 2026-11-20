package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.PrinterStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.CartItemRepository;
import com.exe.morphia3d.repository.CartRepository;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.OrderItemRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.features.cart.enabled=true", "app.features.order.enabled=true",
        "app.features.checkout.enabled=true"})
class CartConcurrentAddIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private Model3dRepository modelRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PrinterRepository printerRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private MockMvc mockMvc;
    private ExecutorService executor;
    private User owner;
    private Integer modelId;
    private Integer estimateId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        executor = Executors.newFixedThreadPool(2);
        String suffix = UUID.randomUUID().toString();
        owner = userRepository.saveAndFlush(User.builder()
                .name("Concurrent cart owner")
                .email("cart-" + suffix + "@example.com")
                .password("encoded")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
        Model3d model = modelRepository.saveAndFlush(Model3d.builder()
                .name("Concurrent model")
                .sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.ACTIVE)
                .build());
        Material material = materialRepository.saveAndFlush(Material.builder()
                .name("Concurrent PLA")
                .pricePerGram(100.0)
                .stockQuantity(1000.0)
                .unit("g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build());
        Printer printer = printerRepository.saveAndFlush(Printer.builder()
                .name("Concurrent printer")
                .status(PrinterStatus.ACTIVE)
                .build());
        modelId = model.getId();
        estimateId = new TransactionTemplate(transactionManager).execute(transaction -> {
            PriceEstimation estimate = new PriceEstimation();
            estimate.setModel(model);
            estimate.setMaterial(material);
            estimate.setPrinter(printer);
            estimate.setTotalPrice(15000.0);
            entityManager.persist(estimate);
            entityManager.flush();
            return estimate.getId();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @Timeout(30)
    void simultaneousAddsMergeToOneLineWithoutLosingQuantity() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> addAfterBarrier(ready, start));
        Future<Integer> second = executor.submit(() -> addAfterBarrier(ready, start));
        assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get()).isEqualTo(200);
        assertThat(second.get()).isEqualTo(200);
        assertThat(cartRepository.findByUserId(owner.getId())).isPresent();
        var cart = cartRepository.findByUserId(owner.getId()).orElseThrow();
        var items = cartItemRepository.findAllByCartId(cart.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    @Timeout(30)
    void concurrentUpdateAndCheckoutSerializeWithoutLosingOrDuplicatingTheLine() throws Exception {
        int addStatus = mockMvc.perform(post("/api/cart/items").with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + ",\"estimateId\":" + estimateId
                                + ",\"quantity\":1}"))
                .andReturn().getResponse().getStatus();
        assertThat(addStatus).isEqualTo(200);
        var cart = cartRepository.findByUserId(owner.getId()).orElseThrow();
        Integer cartItemId = cartItemRepository.findAllByCartId(cart.getId()).get(0).getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> update = executor.submit(() -> updateAfterBarrier(cartItemId, ready, start));
        Future<Integer> checkout = executor.submit(() -> checkoutAfterBarrier(ready, start));
        assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int updateStatus = update.get();
        assertThat(updateStatus).isIn(200, 404);
        assertThat(checkout.get()).isEqualTo(201);
        assertThat(cartItemRepository.findAllByCartId(cart.getId())).isEmpty();

        var persisted = new TransactionTemplate(transactionManager).execute(transaction -> {
            Order order = orderRepository.findAll().stream()
                    .filter(candidate -> candidate.getUser().getId().equals(owner.getId()))
                    .max(java.util.Comparator.comparing(Order::getId))
                    .orElseThrow();
            return orderItemRepository.findAllByOrderId(order.getId());
        });
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getQuantity()).isIn(1, 5);
    }

    private int updateAfterBarrier(Integer cartItemId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent cart requests did not start");
        }
        return mockMvc.perform(patch("/api/cart/items/{id}", cartItemId).with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andReturn().getResponse().getStatus();
    }

    private int checkoutAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent cart requests did not start");
        }
        return mockMvc.perform(post("/api/orders").with(user(new UserPrincipal(owner))))
                .andReturn().getResponse().getStatus();
    }

    private int addAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent cart requests did not start");
        }
        return mockMvc.perform(post("/api/cart/items").with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + ",\"estimateId\":" + estimateId
                                + ",\"quantity\":1}"))
                .andReturn().getResponse().getStatus();
    }
}
