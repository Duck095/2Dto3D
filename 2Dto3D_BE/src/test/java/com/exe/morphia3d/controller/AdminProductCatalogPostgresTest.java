package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.ColorQuantityRequest;
import com.exe.morphia3d.dto.request.MaterialRequest;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.repository.PriceEstimationRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.service.MaterialService;
import jakarta.persistence.EntityNotFoundException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(classes = Morphia3dApplication.class)
class AdminProductCatalogPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("admin_catalog_qa")
            .withUsername("admin_catalog_qa")
            .withPassword("admin_catalog_qa_only");

    private MockMvc mvc;
    @Autowired private WebApplicationContext context;
    @Autowired private Model3dRepository models;
    @Autowired private MaterialRepository materials;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ColorRepository colors;
    @Autowired private PriceEstimationRepository estimates;
    @Autowired private PrinterRepository printers;
    @Autowired private MaterialService materialService;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Integer> fixtureIds = new ArrayList<>();
    private final List<Integer> materialFixtureIds = new ArrayList<>();
    private final List<Integer> colorFixtureIds = new ArrayList<>();
    private final List<Integer> estimateFixtureIds = new ArrayList<>();
    private final List<Integer> printerFixtureIds = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        JdbcTemplate bootstrap = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        if (!POSTGRES.isRunning()) POSTGRES.start();
        bootstrap.execute("CREATE ROLE admin3d_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_lock_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_command_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE payos_trust_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE app_runtime NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14")).load().migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @AfterEach
    void removeOnlyThisTestFixtures() {
        estimates.deleteAllById(estimateFixtureIds);
        estimateFixtureIds.clear();
        models.deleteAllById(fixtureIds);
        fixtureIds.clear();
        for (Integer materialId : materialFixtureIds) {
            jdbc.update("delete from material_colors where material_id = ?", materialId);
        }
        materials.deleteAllById(materialFixtureIds);
        materialFixtureIds.clear();
        colors.deleteAllById(colorFixtureIds);
        colorFixtureIds.clear();
        printers.deleteAllById(printerFixtureIds);
        printerFixtureIds.clear();
    }

    @Test
    void postgresAdminCanEditAndArchiveDesignerProductWhilePublicCatalogHidesIt() throws Exception {
        Model3d draft = save("Postgres admin draft", ModelType.DESIGNER_MODEL, ModelStatus.PENDING);
        save("AI model remains outside admin designer catalog", ModelType.AI_MODEL, ModelStatus.ACTIVE);

        mvc.perform(get("/api/admin/products").with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sourceType").value("DESIGNER_MODEL"));

        mvc.perform(patch("/api/admin/products/{id}", draft.getId())
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edited from PostgreSQL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Edited from PostgreSQL"));

        mvc.perform(post("/api/admin/products/{id}/archive", draft.getId())
                        .with(user("catalog-admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(models.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(ModelStatus.INACTIVE);
        mvc.perform(get("/api/products").param("q", "Edited from PostgreSQL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/products/{id}", draft.getId())).andExpect(status().isNotFound());

        mvc.perform(post("/api/colors/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PG Blue\",\"hexCode\":\"#0000FF\"}"))
                .andExpect(status().isOk());
        Integer colorId = jdbc.queryForObject("select color_id from colors where name = 'PG Blue'", Integer.class);
        colorFixtureIds.add(colorId);
        mvc.perform(post("/api/materials/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PLA\",\"type\":\"PLA\",\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\",\"colors\":[{\"colorId\":" + colorId + ",\"quantity\":100}]}"))
                .andExpect(status().isOk());
        Integer materialId = jdbc.queryForObject("select material_id from materials where name = 'PLA'", Integer.class);
        materialFixtureIds.add(materialId);
        mvc.perform(put("/api/materials/{id}", materialId)
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[{\"colorId\":" + colorId + ",\"quantity\":75}]}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select type from materials where material_id = ?", String.class, materialId))
                .isEqualTo("PETG");
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, colorId)).isEqualTo(75.0);
    }

    @Test
    void conflictingPostgresColorReplacementsWaitOnTheMaterialRowAndCommitAsWholeSets() throws Exception {
        Color red = saveColor("Race Red", "#AA0000");
        Color blue = saveColor("Race Blue", "#0000AA");
        Integer materialId = addMaterial("Race PLA", List.of(new ColorQuantityRequest(red.getId(), 1d)));

        CountDownLatch firstHasRowLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> first = null;
        Future<?> second = null;
        try {
            first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("select set_config('application_name', 'be-admin-material-t1', false)", String.class);
                jdbc.queryForObject("select material_id from materials where material_id = ? for update",
                        Integer.class, materialId);
                firstHasRowLock.countDown();
                await(releaseFirst);
                materialService.updateMaterial(materialId,
                        material("Race First", List.of(new ColorQuantityRequest(red.getId(), 11d))));
            }));
            assertThat(firstHasRowLock.await(5, TimeUnit.SECONDS)).isTrue();

            second = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("select set_config('application_name', 'be-admin-material-t2', false)", String.class);
                materialService.updateMaterial(materialId,
                        material("Race Second", List.of(new ColorQuantityRequest(blue.getId(), 22d))));
            }));

            assertThat(awaitMaterialLockQueryBlocked("be-admin-material-t2")).isTrue();
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            if (first != null && !first.isDone()) first.cancel(true);
            if (second != null && !second.isDone()) second.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo("Race Second");
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                materialId)).containsExactly(blue.getId());
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, blue.getId())).isEqualTo(22d);
    }

    @Test
    void failedPostgresColorReplacementRollsBackMetadataAndOldAssociations() {
        Color red = saveColor("Rollback Red", "#AA1100");
        Color blue = saveColor("Rollback Blue", "#0011AA");
        Integer materialId = addMaterial("Rollback PLA", List.of(new ColorQuantityRequest(red.getId(), 3d)));

        jdbc.execute("create or replace function qa_reject_material_color() returns trigger language plpgsql as $$ "
                + "begin if new.quantity = 999 then raise exception 'injected QA insert failure'; end if; "
                + "return new; end $$");
        jdbc.execute("create trigger qa_reject_material_color before insert on material_colors "
                + "for each row execute function qa_reject_material_color()");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> materialService.updateMaterial(materialId,
                    material("Should Roll Back", List.of(new ColorQuantityRequest(blue.getId(), 999d)))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger if exists qa_reject_material_color on material_colors");
            jdbc.execute("drop function if exists qa_reject_material_color()");
        }

        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo("Rollback PLA");
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                materialId)).containsExactly(red.getId());
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, red.getId())).isEqualTo(3d);
    }

    @Test
    void invalidPostgresColorReplacementsLeaveMaterialAndAssociationsUntouched() {
        Color red = saveColor("Validation Red", "#AA2200");
        Color blue = saveColor("Validation Blue", "#0022AA");
        Integer materialId = addMaterial("Validation PLA", List.of(new ColorQuantityRequest(red.getId(), 4d)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> materialService.updateMaterial(materialId,
                material("Duplicate Replacement", List.of(new ColorQuantityRequest(red.getId(), 5d),
                        new ColorQuantityRequest(red.getId(), 6d)))))
                .isInstanceOf(ResponseStatusException.class);
        assertMaterialReplacementUnchanged(materialId, red.getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> materialService.updateMaterial(materialId,
                material("Invalid Quantity", List.of(new ColorQuantityRequest(blue.getId(), -1d)))))
                .isInstanceOf(ResponseStatusException.class);
        assertMaterialReplacementUnchanged(materialId, red.getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> materialService.updateMaterial(materialId,
                material("Unknown Color", List.of(new ColorQuantityRequest(Integer.MAX_VALUE, 7d)))))
                .isInstanceOf(EntityNotFoundException.class);
        assertMaterialReplacementUnchanged(materialId, red.getId());
    }

    @Test
    void concurrentMaterialRetirementThenUpdateKeepsUnavailableAndHistoricalEstimate() throws Exception {
        Color red = saveColor("Retire Race Red", "#B10000");
        Color blue = saveColor("Retire Race Blue", "#0000B1");
        Integer materialId = addMaterial("Retire Race PLA", List.of(
                new ColorQuantityRequest(red.getId(), 10d), new ColorQuantityRequest(blue.getId(), 20d)));
        Model3d historicalModel = save("Retirement history model", ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE);
        Printer printer = printers.saveAndFlush(Printer.builder().name("Retirement history printer").build());
        printerFixtureIds.add(printer.getId());
        PriceEstimation estimate = new PriceEstimation();
        estimate.setModel(historicalModel);
        estimate.setMaterial(materials.findById(materialId).orElseThrow());
        estimate.setPrinter(printer);
        estimate.setTotalPrice(91d);
        estimate = estimates.saveAndFlush(estimate);
        Integer estimateId = estimate.getId();
        estimateFixtureIds.add(estimateId);

        CountDownLatch firstHasRowLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> retirement = null;
        Future<?> update = null;
        try {
            retirement = startLockedOperation(executor, materialId, "be-admin-retire-t1", firstHasRowLock,
                    releaseFirst, () -> materialService.removeMaterial(materialId));
            assertThat(firstHasRowLock.await(5, TimeUnit.SECONDS)).isTrue();
            update = startTransaction(executor, "be-admin-retire-t2", () -> materialService.updateMaterial(
                    materialId, material("Retire Race Updated", List.of(new ColorQuantityRequest(blue.getId(), 25d)))));
            assertThat(awaitMaterialLockQueryBlocked("be-admin-retire-t2")).isTrue();
            releaseFirst.countDown();
            retirement.get(10, TimeUnit.SECONDS);
            update.get(10, TimeUnit.SECONDS);
        } finally {
            stopExecutor(executor, releaseFirst);
        }

        assertThat(jdbc.queryForObject("select is_available from materials where material_id = ?", String.class,
                materialId)).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo("Retire Race Updated");
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                materialId)).containsExactly(blue.getId());
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, blue.getId())).isEqualTo(25d);
        assertThat(estimates.existsById(estimateId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from price_estimations where material_id = ?", Integer.class,
                materialId)).isEqualTo(1);
    }

    @Test
    void concurrentAddColorThenBatchReplacementSerializesToTheReplacementSet() throws Exception {
        Color red = saveColor("Add Race Red", "#C10000");
        Color blue = saveColor("Add Race Blue", "#0000C1");
        Color green = saveColor("Add Race Green", "#00C100");
        Integer materialId = addMaterial("Add Race PLA", List.of(new ColorQuantityRequest(red.getId(), 10d)));

        CountDownLatch firstHasRowLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> add = null;
        Future<?> replace = null;
        try {
            add = startLockedOperation(executor, materialId, "be-admin-add-t1", firstHasRowLock, releaseFirst,
                    () -> materialService.addMaterialColor(materialId, new ColorQuantityRequest(blue.getId(), 30d)));
            assertThat(firstHasRowLock.await(5, TimeUnit.SECONDS)).isTrue();
            replace = startTransaction(executor, "be-admin-add-t2", () -> materialService.updateMaterial(
                    materialId, material("Add Race Replaced", List.of(
                            new ColorQuantityRequest(blue.getId(), 40d),
                            new ColorQuantityRequest(green.getId(), 50d)))));
            assertThat(awaitMaterialLockQueryBlocked("be-admin-add-t2")).isTrue();
            releaseFirst.countDown();
            add.get(10, TimeUnit.SECONDS);
            replace.get(10, TimeUnit.SECONDS);
        } finally {
            stopExecutor(executor, releaseFirst);
        }

        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ? order by color_id",
                Integer.class, materialId)).containsExactlyElementsOf(List.of(blue.getId(), green.getId()).stream()
                .sorted().toList());
        assertThat(jdbc.queryForObject("select count(*) from material_colors where material_id = ?", Integer.class,
                materialId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, blue.getId())).isEqualTo(40d);
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, green.getId())).isEqualTo(50d);
        assertThat(jdbc.queryForObject("select count(*) from colors where color_id = ?", Integer.class,
                blue.getId())).isEqualTo(1);
    }

    @Test
    void concurrentRemoveColorThenFailedBatchReplacementRollsBackToCommittedRemoval() throws Exception {
        Color red = saveColor("Remove Race Red", "#D10000");
        Color blue = saveColor("Remove Race Blue", "#0000D1");
        Color green = saveColor("Remove Race Green", "#00D100");
        Integer materialId = addMaterial("Remove Race PLA", List.of(
                new ColorQuantityRequest(red.getId(), 10d), new ColorQuantityRequest(blue.getId(), 20d)));
        jdbc.execute("create or replace function qa_reject_concurrent_color() returns trigger language plpgsql as $$ "
                + "begin if new.quantity = 999 then raise exception 'injected concurrent QA failure'; end if; "
                + "return new; end $$");
        jdbc.execute("create trigger qa_reject_concurrent_color before insert on material_colors "
                + "for each row execute function qa_reject_concurrent_color()");

        CountDownLatch firstHasRowLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> remove = null;
        Future<?> replace = null;
        try {
            remove = startLockedOperation(executor, materialId, "be-admin-remove-t1", firstHasRowLock, releaseFirst,
                    () -> materialService.removeMaterialColor(materialId, red.getId()));
            assertThat(firstHasRowLock.await(5, TimeUnit.SECONDS)).isTrue();
            replace = startTransaction(executor, "be-admin-remove-t2", () -> materialService.updateMaterial(
                    materialId, material("Must Roll Back", List.of(
                            new ColorQuantityRequest(blue.getId(), 999d),
                            new ColorQuantityRequest(green.getId(), 70d)))));
            assertThat(awaitMaterialLockQueryBlocked("be-admin-remove-t2")).isTrue();
            releaseFirst.countDown();
            remove.get(10, TimeUnit.SECONDS);
            Future<?> replacement = replace;
            assertThatThrownBy(() -> replacement.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        } finally {
            stopExecutor(executor, releaseFirst);
            jdbc.execute("drop trigger if exists qa_reject_concurrent_color on material_colors");
            jdbc.execute("drop function if exists qa_reject_concurrent_color()");
        }

        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo("Remove Race PLA");
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                materialId)).containsExactly(blue.getId());
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, blue.getId())).isEqualTo(20d);
        assertThat(jdbc.queryForObject("select count(*) from material_colors where material_id = ? and color_id = ?",
                Integer.class, materialId, green.getId())).isZero();
    }

    private void assertMaterialReplacementUnchanged(Integer materialId, Integer expectedColorId) {
        assertThat(jdbc.queryForObject("select name from materials where material_id = ?", String.class, materialId))
                .isEqualTo("Validation PLA");
        assertThat(jdbc.queryForList("select color_id from material_colors where material_id = ?", Integer.class,
                materialId)).containsExactly(expectedColorId);
        assertThat(jdbc.queryForObject("select quantity from material_colors where material_id = ? and color_id = ?",
                Double.class, materialId, expectedColorId)).isEqualTo(4d);
    }

    private Model3d save(String name, ModelType type, ModelStatus status) {
        Model3d saved = models.saveAndFlush(Model3d.builder().sourceType(type).status(status)
                .name(name).slug("qa-" + fixtureIds.size() + "-" + type.name().toLowerCase())
                .description("PostgreSQL catalog fixture").category("QA").build());
        fixtureIds.add(saved.getId());
        return saved;
    }

    private MaterialRequest material(String name, List<ColorQuantityRequest> colorRows) {
        return MaterialRequest.builder().name(name).type("PLA").pricePerGram(0.05).stockQuantity(100d)
                .unit("g").colors(colorRows).build();
    }

    private Future<?> startLockedOperation(ExecutorService executor, Integer materialId, String applicationName,
                                           CountDownLatch locked, CountDownLatch release, Runnable operation) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('application_name', ?, false)", String.class, applicationName);
            jdbc.queryForObject("select material_id from materials where material_id = ? for update",
                    Integer.class, materialId);
            locked.countDown();
            await(release);
            operation.run();
        }));
    }

    private Future<?> startTransaction(ExecutorService executor, String applicationName, Runnable operation) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('application_name', ?, false)", String.class, applicationName);
            operation.run();
        }));
    }

    private void stopExecutor(ExecutorService executor, CountDownLatch release) throws InterruptedException {
        release.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Integer addMaterial(String name, List<ColorQuantityRequest> colorRows) {
        materialService.addMaterial(material(name, colorRows));
        Integer materialId = jdbc.queryForObject("select material_id from materials where name = ?", Integer.class,
                name);
        materialFixtureIds.add(materialId);
        return materialId;
    }

    private Color saveColor(String name, String hexCode) {
        Color color = colors.saveAndFlush(Color.builder().name(name).hexCode(hexCode).build());
        colorFixtureIds.add(color.getId());
        return color;
    }

    private boolean awaitMaterialLockQueryBlocked(String applicationName) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("select exists (select 1 from pg_stat_activity " +
                    "where application_name = ? and wait_event_type = 'Lock' " +
                    "and (lower(query) like '%for update%' or lower(query) like '%for no key update%'))",
                    Boolean.class, applicationName);
            if (Boolean.TRUE.equals(blocked)) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test thread was interrupted", exception);
        }
    }
}
