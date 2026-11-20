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
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.features.cart.enabled=true")
@Transactional
class CartApiIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private Model3dRepository modelRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PrinterRepository printerRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @PersistenceContext private EntityManager entityManager;

    private MockMvc mockMvc;
    private User owner;
    private User otherUser;
    private Model3d model;
    private Model3d otherModel;
    private Material material;
    private Printer printer;
    private PriceEstimation estimate;
    private PriceEstimation secondEstimate;
    private PriceEstimation otherModelEstimate;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = userRepository.saveAndFlush(User.builder()
                .name("Cart owner")
                .email("cart-owner@example.com")
                .password("encoded")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
        otherUser = userRepository.saveAndFlush(User.builder()
                .name("Other user")
                .email("other-cart-user@example.com")
                .password("encoded")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
        model = saveModel(ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE, null, "Cart model");
        otherModel = saveModel(ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE, null, "Other model");
        material = materialRepository.saveAndFlush(Material.builder()
                .name("PLA")
                .pricePerGram(100.0)
                .stockQuantity(1000.0)
                .unit("g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build());
        printer = printerRepository.saveAndFlush(Printer.builder()
                .name("Printer")
                .status(PrinterStatus.ACTIVE)
                .build());
        estimate = saveEstimate(model, material, printer, 15000.0);
        secondEstimate = saveEstimate(model, material, printer, 16000.0);
        otherModelEstimate = saveEstimate(otherModel, material, printer, 18000.0);
    }

    @Test
    void authenticatedOwnerCanReadAnEmptyCart() throws Exception {
        mockMvc.perform(get("/api/cart").with(user(new UserPrincipal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void anonymousUserCannotReadOrModifyCart() throws Exception {
        mockMvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(model.getId(), estimate.getId(), 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedAddMergesOnlyTheSameModelAndEstimate() throws Exception {
        add(owner, model, estimate, 2).andExpect(status().isOk());
        add(owner, model, estimate, 3).andExpect(status().isOk());
        add(owner, model, secondEstimate, 4).andExpect(status().isOk());

        mockMvc.perform(get("/api/cart").with(user(new UserPrincipal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").value(org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.items[0].modelId").value(model.getId()))
                .andExpect(jsonPath("$.items[0].estimateId").value(estimate.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[1].estimateId").value(secondEstimate.getId()))
                .andExpect(jsonPath("$.items[1].quantity").value(4));
    }

    @Test
    void cartMutationsAlwaysUseTheAuthenticatedOwner() throws Exception {
        add(owner, model, estimate, 1).andExpect(status().isOk());
        Cart cart = cartRepository.findByUserId(owner.getId()).orElseThrow();
        CartItem item = cartItemRepository.findAllByCartId(cart.getId()).get(0);

        mockMvc.perform(get("/api/cart").with(user(new UserPrincipal(otherUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(patch("/api/cart/items/{id}", item.getId())
                        .with(user(new UserPrincipal(otherUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CART_ITEM_NOT_FOUND"));
        mockMvc.perform(delete("/api/cart/items/{id}", item.getId())
                        .with(user(new UserPrincipal(otherUser))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(get("/api/cart").with(user(new UserPrincipal(owner))))
                .andExpect(jsonPath("$.items").value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    @Test
    void invalidQuantityIsRejectedAndLimitIsConfigurableWithDefaultNinetyNine() throws Exception {
        add(owner, model, estimate, 0).andExpect(status().isBadRequest());
        add(owner, model, estimate, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CART_QUANTITY_LIMIT_EXCEEDED"));

        add(owner, model, estimate, 99).andExpect(status().isOk());
        add(owner, model, estimate, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CART_QUANTITY_LIMIT_EXCEEDED"));
    }

    @Test
    void patchSetsQuantityAndDeleteRemovesLine() throws Exception {
        add(owner, model, estimate, 2).andExpect(status().isOk());
        Cart cart = cartRepository.findByUserId(owner.getId()).orElseThrow();
        Integer itemId = cartItemRepository.findAllByCartId(cart.getId()).get(0).getId();

        mockMvc.perform(patch("/api/cart/items/{id}", itemId)
                        .with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(7));
        mockMvc.perform(patch("/api/cart/items/{id}", itemId)
                        .with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/cart/items/{id}", itemId)
                        .with(user(new UserPrincipal(owner))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cart").with(user(new UserPrincipal(owner))))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void estimateMustExistAndBelongToTheRequestedModel() throws Exception {
        mockMvc.perform(post("/api/cart/items").with(user(new UserPrincipal(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(model.getId(), Integer.MAX_VALUE, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CART_ESTIMATE_NOT_FOUND"));
        add(owner, model, otherModelEstimate, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CART_ESTIMATE_MODEL_MISMATCH"));
    }

    @Test
    void modelAndPrintConfigurationMustBeUsable() throws Exception {
        Model3d unavailableModel = saveModel(ModelType.DESIGNER_MODEL, ModelStatus.PENDING, null, "Draft model");
        PriceEstimation unavailableModelEstimate = saveEstimate(unavailableModel, material, printer, 100.0);
        add(owner, unavailableModel, unavailableModelEstimate, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CART_MODEL_UNAVAILABLE"));

        material.setIsAvailable(MaterialStatus.UNAVAILABLE);
        add(owner, model, estimate, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CART_CONFIGURATION_UNAVAILABLE"));
    }

    @Test
    void privateAiAndCustomerModelsHideEveryStatusFromOtherUsers() throws Exception {
        for (ModelType privateType : List.of(ModelType.AI_MODEL, ModelType.CUSTOMER_CUSTOMIZED)) {
            for (ModelStatus privateStatus : ModelStatus.values()) {
                Model3d privateModel = saveModel(privateType, privateStatus, otherUser,
                        "Private " + privateType + " " + privateStatus);
                PriceEstimation privateEstimate = saveEstimate(privateModel, material, printer, 12000.0);

                add(owner, privateModel, privateEstimate, 1)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error").value("CART_MODEL_NOT_FOUND"));
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions add(
            User principalUser, Model3d selectedModel, PriceEstimation selectedEstimate, int quantity) throws Exception {
        return mockMvc.perform(post("/api/cart/items").with(user(new UserPrincipal(principalUser)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(selectedModel.getId(), selectedEstimate == null ? 0 : selectedEstimate.getId(), quantity)));
    }

    private String addBody(Integer modelId, Integer estimateId, int quantity) {
        return "{\"modelId\":" + modelId + ",\"estimateId\":" + estimateId
                + ",\"quantity\":" + quantity + "}";
    }

    private Model3d saveModel(ModelType type, ModelStatus status, User modelOwner, String name) {
        return modelRepository.saveAndFlush(Model3d.builder()
                .sourceType(type).status(status).user(modelOwner).name(name).build());
    }

    private PriceEstimation saveEstimate(Model3d selectedModel, Material selectedMaterial,
                                        Printer selectedPrinter, double totalPrice) {
        PriceEstimation value = new PriceEstimation();
        value.setModel(selectedModel);
        value.setMaterial(selectedMaterial);
        value.setPrinter(selectedPrinter);
        value.setTotalPrice(totalPrice);
        entityManager.persist(value);
        entityManager.flush();
        return value;
    }
}
