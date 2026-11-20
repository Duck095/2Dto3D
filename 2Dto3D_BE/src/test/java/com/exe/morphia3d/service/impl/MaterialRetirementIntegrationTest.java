package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.controller.MaterialController;
import com.exe.morphia3d.dto.response.MaterialResponse;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.mapper.MaterialMapper;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.service.MaterialService;
import jakarta.persistence.EntityNotFoundException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MaterialRetirementIntegrationTest {

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private MaterialService materialService;
    private AtomicInteger physicalDeleteCalls;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:material_retirement_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        physicalDeleteCalls = new AtomicInteger();

        MaterialRepository materialRepository = repositoryBackedByH2();
        MaterialMapper materialMapper = mapperForResponse();
        materialService = new MaterialServiceImpl(materialRepository, null, null, materialMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new MaterialController(materialService)).build();
    }

    @Test
    void deleteRouteRetiresMaterialAndPreservesStockAndHistoricalReferences() throws Exception {
        insertHistoryFixture();

        mockMvc.perform(delete("/api/materials/7/remove"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(jdbc.queryForObject("SELECT is_available FROM materials WHERE material_id = 7", String.class))
                .isEqualTo("UNAVAILABLE");
        assertThat(count("SELECT COUNT(*) FROM materials WHERE material_id = 7")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM material_stock_logs WHERE material_id = 7")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT change_type FROM material_stock_logs WHERE material_id = 7", String.class))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM material_stock_logs WHERE material_id = 7", Double.class)).isEqualTo(12.0);
        assertThat(count("SELECT COUNT(*) FROM material_colors WHERE material_id = 7")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM material_colors WHERE material_id = 7", Double.class)).isEqualTo(12.0);
        assertThat(count("SELECT COUNT(*) FROM price_estimations WHERE material_id = 7")).isEqualTo(1L);
        assertThat(physicalDeleteCalls.get()).isZero();

        mockMvc.perform(delete("/api/materials/7/remove"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        assertThat(count("SELECT COUNT(*) FROM materials WHERE material_id = 7")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM material_stock_logs WHERE material_id = 7")).isEqualTo(1L);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM materials WHERE material_id = 7"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT COUNT(*) FROM material_stock_logs WHERE material_id = 7")).isEqualTo(1L);

        mockMvc.perform(get("/api/materials/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 7)].isAvailable")
                        .value(hasItem(MaterialStatus.UNAVAILABLE.name())));
    }

    @Test
    void missingMaterialKeepsExistingEntityNotFoundBehavior() {
        assertThatThrownBy(() -> materialService.removeMaterial(99))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Material không tồn tại: 99");
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE materials (material_id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                + "is_available VARCHAR(255))");
        jdbc.execute("CREATE TABLE users (user_id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE colors (color_id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE model_3d (model_id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE printers (printer_id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE material_stock_logs (log_id INTEGER PRIMARY KEY, material_id INTEGER NOT NULL, "
                + "created_by INTEGER NOT NULL, change_type VARCHAR(20) NOT NULL, quantity DOUBLE PRECISION NOT NULL, "
                + "CONSTRAINT fk_stock_material FOREIGN KEY (material_id) REFERENCES materials(material_id) "
                + "ON DELETE RESTRICT, CONSTRAINT fk_stock_user FOREIGN KEY (created_by) "
                + "REFERENCES users(user_id))");
        jdbc.execute("CREATE TABLE material_colors (material_id INTEGER NOT NULL, color_id INTEGER NOT NULL, "
                + "quantity DOUBLE PRECISION NOT NULL, PRIMARY KEY (material_id, color_id), "
                + "FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT, "
                + "FOREIGN KEY (color_id) REFERENCES colors(color_id))");
        jdbc.execute("CREATE TABLE price_estimations (estimate_id INTEGER PRIMARY KEY, material_id INTEGER NOT NULL, "
                + "model_id INTEGER NOT NULL, printer_id INTEGER NOT NULL, "
                + "FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT, "
                + "FOREIGN KEY (model_id) REFERENCES model_3d(model_id), "
                + "FOREIGN KEY (printer_id) REFERENCES printers(printer_id))");
    }

    private void insertHistoryFixture() {
        jdbc.update("INSERT INTO materials (material_id, name, is_available) VALUES (7, 'PLA', 'AVAILABLE')");
        jdbc.update("INSERT INTO users (user_id) VALUES (3)");
        jdbc.update("INSERT INTO colors (color_id) VALUES (5)");
        jdbc.update("INSERT INTO model_3d (model_id) VALUES (11)");
        jdbc.update("INSERT INTO printers (printer_id) VALUES (13)");
        jdbc.update("INSERT INTO material_stock_logs (log_id, material_id, created_by, change_type, quantity) "
                + "VALUES (17, 7, 3, 'RECEIVED', 12)");
        jdbc.update("INSERT INTO material_colors (material_id, color_id, quantity) VALUES (7, 5, 12)");
        jdbc.update("INSERT INTO price_estimations (estimate_id, material_id, model_id, printer_id) "
                + "VALUES (19, 7, 11, 13)");
    }

    private MaterialRepository repositoryBackedByH2() {
        return (MaterialRepository) Proxy.newProxyInstance(
                MaterialRepository.class.getClassLoader(),
                new Class<?>[]{MaterialRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById", "findByIdForUpdate" -> findMaterial((Integer) args[0]);
                    case "save" -> saveMaterial((Material) args[0]);
                    case "findAllWithColors" -> findAllMaterials();
                    case "delete" -> {
                        physicalDeleteCalls.incrementAndGet();
                        jdbc.update("DELETE FROM materials WHERE material_id = ?", ((Material) args[0]).getId());
                        yield null;
                    }
                    case "toString" -> "H2-backed material repository test adapter";
                    default -> throw new UnsupportedOperationException("Unexpected repository call: " + method);
                });
    }

    private Optional<Material> findMaterial(Integer id) {
        List<Material> rows = jdbc.query("SELECT material_id, name, is_available FROM materials "
                        + "WHERE material_id = ?", (rs, row) -> Material.builder()
                        .id(rs.getInt("material_id"))
                        .name(rs.getString("name"))
                        .isAvailable(MaterialStatus.valueOf(rs.getString("is_available")))
                        .build(), id);
        return rows.stream().findFirst();
    }

    private Material saveMaterial(Material material) {
        jdbc.update("UPDATE materials SET is_available = ? WHERE material_id = ?",
                material.getIsAvailable().name(), material.getId());
        return material;
    }

    private List<Material> findAllMaterials() {
        return jdbc.query("SELECT material_id, name, is_available FROM materials", (rs, row) -> Material.builder()
                .id(rs.getInt("material_id"))
                .name(rs.getString("name"))
                .isAvailable(MaterialStatus.valueOf(rs.getString("is_available")))
                .build());
    }

    private MaterialMapper mapperForResponse() {
        return (MaterialMapper) Proxy.newProxyInstance(
                MaterialMapper.class.getClassLoader(),
                new Class<?>[]{MaterialMapper.class},
                (proxy, method, args) -> {
                    if ("toResponse".equals(method.getName())) {
                        Material material = (Material) args[0];
                        return MaterialResponse.builder()
                                .id(material.getId())
                                .name(material.getName())
                                .isAvailable(material.getIsAvailable())
                                .build();
                    }
                    throw new UnsupportedOperationException("Unexpected mapper call: " + method);
                });
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
