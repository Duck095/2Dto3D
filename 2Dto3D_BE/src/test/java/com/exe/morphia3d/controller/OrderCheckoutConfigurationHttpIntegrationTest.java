package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.Model3d;
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
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PriceEstimationRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Morphia3dApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.features.order.enabled=true", "app.features.checkout.enabled=true"})
class OrderCheckoutConfigurationHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private Model3dRepository modelRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PrinterRepository printerRepository;
    @Autowired private PriceEstimationRepository estimateRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JwtUtil jwtUtil;
    @PersistenceContext private EntityManager entityManager;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Fixture> fixtures = new ArrayList<>();

    @ParameterizedTest(name = "{0} checkout rejects {1}")
    @MethodSource("configurationCases")
    void unavailableConfigurationReturnsConflictWithoutConsumingCart(
            CheckoutRoute route, FailureCase failureCase) throws Exception {
        Fixture fixture = createFixture(failureCase);
        HttpResponse<String> response = send(route.path(fixture.cartItemId()), fixture.bearerToken());

        assertThat(response.statusCode()).as("HTTP response body: %s", response.body()).isEqualTo(409);
        assertThat(response.body()).contains("\"status\":409")
                .contains("\"error\":\"ORDER_CONFIGURATION_UNAVAILABLE\"")
                .contains("\"message\":\"Model or print configuration is unavailable for checkout\"");
        assertNoCheckoutMutation(fixture);
    }

    @ParameterizedTest(name = "{0} checkout keeps private model transfer forbidden")
    @MethodSource("checkoutRoutes")
    void privateModelOwnershipFailureRemainsForbiddenWithoutConsumingCart(CheckoutRoute route) throws Exception {
        Fixture fixture = createFixture(FailureCase.PRIVATE_MODEL_REASSIGNED);
        HttpResponse<String> response = send(route.path(fixture.cartItemId()), fixture.bearerToken());

        assertThat(response.statusCode()).as("HTTP response body: %s", response.body()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("Model fixture", "Private AI model");
        assertNoCheckoutMutation(fixture);
    }

    private Fixture createFixture(FailureCase failureCase) {
        Fixture fixture = java.util.Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            User buyer = saveUser("buyer");
            User newOwner = saveUser("new-owner");
            boolean privateModel = failureCase == FailureCase.PRIVATE_MODEL_REASSIGNED;
            Model3d model = modelRepository.saveAndFlush(Model3d.builder()
                    .sourceType(privateModel ? ModelType.AI_MODEL : ModelType.DESIGNER_MODEL)
                    .user(privateModel ? buyer : null)
                    .name("Model fixture")
                    .status(ModelStatus.ACTIVE)
                    .build());
            Material material = materialRepository.saveAndFlush(Material.builder()
                    .name("PLA")
                    .pricePerGram(100.0)
                    .stockQuantity(1000.0)
                    .unit("g")
                    .isAvailable(MaterialStatus.AVAILABLE)
                    .build());
            Printer printer = printerRepository.saveAndFlush(
                    Printer.builder().name("Printer fixture").status(PrinterStatus.ACTIVE).build());
            PriceEstimation estimate = new PriceEstimation();
            estimate.setModel(model);
            estimate.setMaterial(material);
            estimate.setPrinter(printer);
            estimate.setTotalPrice(15000.0);
            estimateRepository.saveAndFlush(estimate);

            Cart cart = new Cart();
            cart.setUser(buyer);
            cartRepository.saveAndFlush(cart);
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setModel(model);
            item.setEstimate(estimate);
            item.setQuantity(1);
            cartItemRepository.saveAndFlush(item);

            switch (failureCase) {
                case DESIGNER_MODEL_UNAVAILABLE -> model.setStatus(ModelStatus.PENDING);
                case MATERIAL_UNAVAILABLE -> material.setIsAvailable(MaterialStatus.UNAVAILABLE);
                case PRINTER_UNAVAILABLE -> printer.setStatus(PrinterStatus.INACTIVE);
                case PRIVATE_MODEL_REASSIGNED -> model.setUser(newOwner);
            }
            entityManager.flush();
            return new Fixture(buyer.getId(), newOwner.getId(), model.getId(), material.getId(), printer.getId(),
                    estimate.getId(), cart.getId(), item.getId(),
                    jwtUtil.generateToken(new UserPrincipal(buyer)), orderRepository.count());
        }));
        fixtures.add(fixture);
        return fixture;
    }

    private User saveUser(String label) {
        return userRepository.saveAndFlush(User.builder()
                .name(label)
                .email(label + "-" + UUID.randomUUID() + "@example.invalid")
                .password("encoded")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private HttpResponse<String> send(String path, String bearerToken) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearerToken)
                .POST(noBody())
                .build();
        return http.send(request, ofString());
    }

    private void assertNoCheckoutMutation(Fixture fixture) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(orderRepository.count()).isEqualTo(fixture.orderCountBefore());
            assertThat(cartItemRepository.findById(fixture.cartItemId())).isPresent();
        });
    }

    static Stream<Arguments> configurationCases() {
        return Stream.of(CheckoutRoute.values()).flatMap(route -> Stream.of(
                Arguments.of(route, FailureCase.DESIGNER_MODEL_UNAVAILABLE),
                Arguments.of(route, FailureCase.MATERIAL_UNAVAILABLE),
                Arguments.of(route, FailureCase.PRINTER_UNAVAILABLE)));
    }

    static Stream<Arguments> checkoutRoutes() {
        return Stream.of(CheckoutRoute.values()).map(Arguments::of);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUpFixtures() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Fixture fixture : fixtures) {
                cartItemRepository.deleteById(fixture.cartItemId());
                cartRepository.deleteById(fixture.cartId());
                estimateRepository.deleteById(fixture.estimateId());
                modelRepository.deleteById(fixture.modelId());
                materialRepository.deleteById(fixture.materialId());
                printerRepository.deleteById(fixture.printerId());
                userRepository.deleteById(fixture.newOwnerId());
                userRepository.deleteById(fixture.buyerId());
            }
        });
        fixtures.clear();
    }

    private enum CheckoutRoute {
        FULL_CART {
            @Override String path(Integer cartItemId) { return "/api/orders"; }
        },
        SINGLE_ITEM {
            @Override String path(Integer cartItemId) { return "/api/orders/from-cart-item/" + cartItemId; }
        };

        abstract String path(Integer cartItemId);
    }

    private enum FailureCase {
        DESIGNER_MODEL_UNAVAILABLE,
        MATERIAL_UNAVAILABLE,
        PRINTER_UNAVAILABLE,
        PRIVATE_MODEL_REASSIGNED
    }

    private record Fixture(Integer buyerId, Integer newOwnerId, Integer modelId, Integer materialId,
                           Integer printerId, Integer estimateId, Integer cartId, Integer cartItemId,
                           String bearerToken, long orderCountBefore) { }
}
