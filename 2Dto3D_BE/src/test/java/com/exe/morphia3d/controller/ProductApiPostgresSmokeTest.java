package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelPart;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelPartRepository;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiPostgresSmokeTest {
    private static final String DATABASE = "product_api_qa";
    private static final String SEARCH_TERM = "SYS03QA";
    private static final int ACTIVE_PRODUCT_COUNT = 15;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DATABASE)
            .withUsername("product_api_qa")
            .withPassword("product_api_qa_only");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Model3dRepository modelRepository;
    @Autowired private ModelPartRepository partRepository;

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Integer> fixtureModelIds = new ArrayList<>();
    private final List<Integer> fixturePartIds = new ArrayList<>();

    @LocalServerPort
    private int port;

    @AfterEach
    void removeOnlyThisTestFixtures() {
        partRepository.deleteAllById(fixturePartIds);
        modelRepository.deleteAllById(fixtureModelIds);
        fixturePartIds.clear();
        fixtureModelIds.clear();
    }

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        JdbcTemplate bootstrap = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, POSTGRES.getUsername());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("14"))
                .load().migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Test
    void postgresBackedBackendServesFilteredPagedAndVisibleCatalogOverHttp() throws Exception {
        seedCatalogFixture();
        assertThat(port).isPositive();
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20");

        JsonNode firstPage = getJson("/api/products?page=0&size=12&sort=newest", HttpStatus.OK);
        JsonNode secondPage = getJson("/api/products?page=1&size=12&sort=newest", HttpStatus.OK);
        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(12);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(ACTIVE_PRODUCT_COUNT);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("items")).hasSize(12);
        assertThat(secondPage.path("page").asInt()).isEqualTo(1);
        assertThat(secondPage.path("totalElements").asLong()).isEqualTo(ACTIVE_PRODUCT_COUNT);
        assertThat(secondPage.path("items")).hasSize(3);

        List<Integer> pageIds = new ArrayList<>();
        List<String> newestNames = new ArrayList<>();
        firstPage.path("items").forEach(item -> {
            pageIds.add(item.path("id").asInt());
            newestNames.add(item.path("name").asText());
        });
        secondPage.path("items").forEach(item -> {
            pageIds.add(item.path("id").asInt());
            newestNames.add(item.path("name").asText());
        });
        assertThat(new HashSet<>(pageIds)).hasSize(ACTIVE_PRODUCT_COUNT);
        assertThat(newestNames).containsExactlyElementsOf(expectedNamesDescending());

        JsonNode search = getJson("/api/products?page=0&size=12&q=" + SEARCH_TERM, HttpStatus.OK);
        assertThat(search.path("totalElements").asLong()).isEqualTo(ACTIVE_PRODUCT_COUNT);
        assertThat(search.path("items")).hasSize(12);
        search.path("items").forEach(item -> assertThat(item.path("name").asText()).contains(SEARCH_TERM));

        JsonNode nameSorted = getJson("/api/products?page=0&size=12&sort=name_asc", HttpStatus.OK);
        assertThat(nameSorted.path("items")).hasSize(12);
        assertThat(names(nameSorted)).containsExactlyElementsOf(expectedNamesAscending().subList(0, 12));

        int publicId = fixtureModelIds.get(0);
        JsonNode detail = getJson("/api/products/" + publicId, HttpStatus.OK);
        assertThat(detail.path("id").asInt()).isEqualTo(publicId);
        assertThat(detail.path("name").asText()).isEqualTo(expectedName(1));
        assertThat(detail.path("slug").asText()).isEqualTo("sys03qa-product-01");
        assertThat(detail.path("description").asText()).isEqualTo("Public catalog detail fixture");
        assertThat(detail.path("category").asText()).isEqualTo("CatalogQA");
        assertThat(detail.path("sizeXMm").asDouble()).isEqualTo(120.5);
        assertThat(detail.path("sizeYMm").asDouble()).isEqualTo(80.25);
        assertThat(detail.path("sizeZMm").asDouble()).isEqualTo(42.75);
        assertThat(detail.path("images")).isEmpty();
        assertThat(detail.path("model3dUrl").isNull()).isTrue();
        assertThat(detail.path("modelParts")).hasSize(2);
        assertThat(detail.path("modelParts").get(0).path("name").asText()).isEqualTo("Base");
        assertThat(detail.path("modelParts").get(0).path("key").asText()).isEqualTo("base");

        getJson("/api/products/2000000000", HttpStatus.NOT_FOUND);
        getJson("/api/products/" + fixtureModelIds.get(ACTIVE_PRODUCT_COUNT), HttpStatus.NOT_FOUND);
        getJson("/api/products/" + fixtureModelIds.get(ACTIVE_PRODUCT_COUNT + 1), HttpStatus.NOT_FOUND);
        getJson("/api/products/" + fixtureModelIds.get(ACTIVE_PRODUCT_COUNT + 2), HttpStatus.NOT_FOUND);
    }

    private void seedCatalogFixture() {
        List<Model3d> publicModels = IntStream.rangeClosed(1, ACTIVE_PRODUCT_COUNT)
                .mapToObj(index -> model(expectedName(index), "sys03qa-product-%02d".formatted(index),
                        ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .map(modelRepository::saveAndFlush)
                .toList();
        publicModels.forEach((model) -> {
            fixtureModelIds.add(model.getId());
            int index = Integer.parseInt(model.getSlug().substring(model.getSlug().length() - 2));
            jdbc.update("UPDATE model_3d SET created_at = ? WHERE model_id = ?",
                    Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(index)), model.getId());
        });

        Model3d detail = publicModels.get(0);
        jdbc.update("UPDATE model_3d SET description = ?, size_x_mm = ?, size_y_mm = ?, size_z_mm = ? " +
                        "WHERE model_id = ?",
                "Public catalog detail fixture", 120.5, 80.25, 42.75, detail.getId());
        fixturePartIds.add(partRepository.saveAndFlush(ModelPart.builder()
                .model(detail).partName("Base").partKey("base").build()).getId());
        fixturePartIds.add(partRepository.saveAndFlush(ModelPart.builder()
                .model(detail).partName("Top").partKey("top").build()).getId());

        fixtureModelIds.add(modelRepository.saveAndFlush(model("Hidden inactive SYS03QA fixture",
                "sys03qa-inactive", ModelType.DESIGNER_MODEL, ModelStatus.INACTIVE)).getId());
        fixtureModelIds.add(modelRepository.saveAndFlush(model("Hidden pending SYS03QA fixture",
                "sys03qa-pending", ModelType.DESIGNER_MODEL, ModelStatus.PENDING)).getId());
        fixtureModelIds.add(modelRepository.saveAndFlush(model("Private AI SYS03QA fixture",
                "sys03qa-private-ai", ModelType.AI_MODEL, ModelStatus.ACTIVE)).getId());
    }

    private Model3d model(String name, String slug, ModelType type, ModelStatus status) {
        return Model3d.builder()
                .name(name)
                .slug(slug)
                .description("Public catalog detail fixture")
                .category("CatalogQA")
                .sourceType(type)
                .status(status)
                .build();
    }

    private JsonNode getJson(String path, HttpStatus expectedStatus) throws Exception {
        ResponseEntity<String> response;
        try {
            response = http.getForEntity("http://127.0.0.1:" + port + path, String.class);
        } catch (HttpStatusCodeException exception) {
            response = ResponseEntity.status(exception.getStatusCode())
                    .body(exception.getResponseBodyAsString());
        }
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        if (expectedStatus == HttpStatus.NOT_FOUND) {
            assertThat(response.getBody()).doesNotContain("Internal Server Error");
            return objectMapper.createObjectNode();
        }
        assertThat(response.getBody()).isNotBlank();
        return objectMapper.readTree(response.getBody());
    }

    private List<String> names(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.path("items").forEach(item -> names.add(item.path("name").asText()));
        return names;
    }

    private String expectedName(int index) {
        return SEARCH_TERM + " Product %02d".formatted(index);
    }

    private List<String> expectedNamesAscending() {
        return IntStream.rangeClosed(1, ACTIVE_PRODUCT_COUNT).mapToObj(this::expectedName).toList();
    }

    private List<String> expectedNamesDescending() {
        List<String> names = new ArrayList<>(expectedNamesAscending());
        java.util.Collections.reverse(names);
        return names;
    }
}
