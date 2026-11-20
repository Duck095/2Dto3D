package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.dto.admin3d.mesh.MeshStructureResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.service.admin3d.mesh.AdminProductMeshService;
import com.exe.morphia3d.service.admin3d.mesh.MeshConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.sql.SQLException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_mesh_http;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        "media.lifecycle.reconciliation-enabled=false"})
@ActiveProfiles("test")
class AdminProductMeshHttpIntegrationTest {
    @Autowired private WebApplicationContext context;
    @MockitoBean private AdminProductMeshService service;
    @MockitoBean private UserDetailsServiceImpl accounts;
    @Autowired private JwtUtil jwt;
    private MockMvc mockMvc;
    private UserPrincipal admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = new UserPrincipal(User.builder().id(71).name("Mesh admin").email("mesh-admin@example.invalid")
                .password("test-only").role(Role.ADMIN).build());
    }

    @Test
    void exposesOnlyTheThreeAuthorizedMeshOperations() throws Exception {
        when(service.getStructure(7)).thenReturn(new MeshStructureResponse(7, 41L, UUID.randomUUID(), "glb-structure-1",
                0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(service.getConfiguration(7)).thenReturn(unconfigured());

        mockMvc.perform(get("/api/admin/products/7/mesh-structure").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaAssetId").value(41));
        mockMvc.perform(get("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mockMvc.perform(post("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void putUsesAuthenticatedPrincipalAndReturnsExistingServiceResult() throws Exception {
        String snapshot = UUID.randomUUID().toString();
        String body = "{\"mediaAssetId\":41,\"structureSnapshotId\":\"" + snapshot
                + "\",\"expectedRevision\":0,\"materialId\":3,\"meshes\":[{\"nodeIndex\":0,\"meshIndex\":0,\"primitiveIndex\":0,\"customizable\":true,\"defaultColorId\":5,\"allowedColorIds\":[5]}]}";
        when(service.putConfiguration(eq(7), eq(71), any(MeshConfigurationRequest.class))).thenReturn(unconfigured());
        mockMvc.perform(put("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service).putConfiguration(eq(7), eq(71), any(MeshConfigurationRequest.class));
    }

    @Test
    void securityReturnsScopedJsonForAnonymousAndNonAdminAndLeavesOtherAdminRoutesAlone() throws Exception {
        mockMvc.perform(get("/api/admin/products/7/mesh-structure"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .contains("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("regular").roles("USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isUnauthorized()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
    }

    @Test
    void validAdminJwtWithDatabaseResourceFailureReturnsSanitized503OnlyForMeshEndpoints() throws Exception {
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException(
                "jdbc:postgresql://private-host/admin?password=private",
                new SQLException("private connection detail", "08006"));
        when(accounts.loadUserByUsername(admin.getUsername())).thenThrow(outage);
        String token = jwt.generateToken(admin);

        assertMesh503(get("/api/admin/products/7/mesh-structure"), token);
        assertMesh503(get("/api/admin/products/7/mesh-configurations"), token);
        assertMesh503(put("/api/admin/products/7/mesh-configurations")
                .contentType(MediaType.APPLICATION_JSON).content(validPutBody()), token);

        verify(accounts, org.mockito.Mockito.times(3)).loadUserByUsername(admin.getUsername());
        verifyNoInteractions(service);

        assertThatThrownBy(() -> mockMvc.perform(get("/api/admin/products")
                .header("Authorization", "Bearer " + token)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThatThrownBy(() -> mockMvc.perform(get("/api/admin/products/7/mesh-configurations/")
                .header("Authorization", "Bearer " + token)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> mockMvc.perform(get("/api/admin/products/7/mesh-configurations/extra")
                .header("Authorization", "Bearer " + token)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> mockMvc.perform(post("/api/admin/products/7/mesh-configurations")
                .header("Authorization", "Bearer " + token)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(accounts, org.mockito.Mockito.times(7)).loadUserByUsername(admin.getUsername());
        verifyNoInteractions(service);
    }

    @Test
    void invalidAndExpiredJwtReturn401WithoutDatabaseLookup() throws Exception {
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .header("Authorization", "Bearer " + expiredAdminJwt()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts, service);
    }

    @Test
    void missingAccountReturns401AndHealthyAdminAndNonAdminLookupsKeepExistingBehavior() throws Exception {
        when(accounts.loadUserByUsername(admin.getUsername()))
                .thenThrow(new UsernameNotFoundException("account missing"));
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .header("Authorization", "Bearer " + jwt.generateToken(admin)))
                .andExpect(status().isUnauthorized());
        verify(service, never()).getStructure(any());

        org.springframework.security.core.userdetails.UserDetails regular = new UserPrincipal(
                User.builder().id(72).name("Mesh user").email("mesh-user@example.invalid")
                        .password("test-only").role(Role.USER).status(com.exe.morphia3d.enums.UserStatus.ACTIVE).build());
        when(accounts.loadUserByUsername(regular.getUsername())).thenReturn(regular);
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .header("Authorization", "Bearer " + jwt.generateToken(regular)))
                .andExpect(status().isForbidden());
        verify(service, never()).getStructure(any());

        org.mockito.Mockito.doReturn(admin).when(accounts).loadUserByUsername(admin.getUsername());
        when(service.getStructure(7)).thenReturn(new MeshStructureResponse(7, 41L, UUID.randomUUID(),
                "glb-structure-1", 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(service.getConfiguration(7)).thenReturn(unconfigured());
        mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                        .header("Authorization", "Bearer " + jwt.generateToken(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/products/7/mesh-configurations")
                        .header("Authorization", "Bearer " + jwt.generateToken(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/products/7/mesh-configurations")
                        .header("Authorization", "Bearer " + jwt.generateToken(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(validPutBody()))
                .andExpect(status().isOk());
        verify(service).getStructure(7);
        verify(service).getConfiguration(7);
        verify(service).putConfiguration(eq(7), eq(71), any(MeshConfigurationRequest.class));
    }

    @Test
    void nonDatabaseAccountLookupFailureIsNotConvertedTo503() {
        when(accounts.loadUserByUsername(admin.getUsername())).thenThrow(new IllegalStateException("unexpected"));
        String token = jwt.generateToken(admin);
        assertThatThrownBy(() -> mockMvc.perform(get("/api/admin/products/7/mesh-structure")
                .header("Authorization", "Bearer " + token)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("temporarily unavailable");
    }

    private void assertMesh503(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token) throws Exception {
        var result = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"code\":\"PERSISTENCE_UNAVAILABLE\",\"message\":\"The persistence service is temporarily unavailable.\"}", true))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("jdbc:", "private-host", "password", "08006", token, "connection detail");
    }

    private String expiredAdminJwt() {
        Key key = Keys.hmacShaKeyFor("test-only-secret-key-that-is-long-enough-for-hs256-signing"
                .getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder().setSubject(admin.getUsername()).claim("role", "ROLE_ADMIN")
                .setIssuedAt(new Date(now.getTime() - 120_000L)).setExpiration(new Date(now.getTime() - 60_000L))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }

    @Test
    void readerRejectsMalformedOversizedAndUnsupportedEncodingBeforeCallingService() throws Exception {
        mockMvc.perform(put("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        byte[] oversized = new byte[33_554_433];
        mockMvc.perform(put("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        mockMvc.perform(put("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth()))
                        .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).putConfiguration(any(), any(), any());
    }

    @Test
    void scopedErrorsDoNotExposeInternalDetails() throws Exception {
        when(service.getConfiguration(7)).thenThrow(new MeshConfigurationException.PersistenceFailure(
                "database password and SQL must not be returned", new IllegalStateException("private SQL details")));
        mockMvc.perform(get("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The persistence service is temporarily unavailable."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
    }

    @Test
    void serviceConflictsAndSemanticValidationMapToScopedCodes() throws Exception {
        when(service.getStructure(7)).thenThrow(new MeshConfigurationException.Conflict("GLB structure snapshot is required"));
        mockMvc.perform(get("/api/admin/products/7/mesh-structure").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GLB_STRUCTURE_SNAPSHOT_REQUIRED"));
        when(service.getConfiguration(7)).thenThrow(new MeshConfigurationException.Invalid("Allowed color is not compatible"));
        mockMvc.perform(get("/api/admin/products/7/mesh-configurations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MATERIAL_COLOR_INCOMPATIBLE"));
    }

    @Test
    void missingMaterialMapsToSanitized422WithoutDatabaseDetails() throws Exception {
        when(service.putConfiguration(eq(7), eq(71), any(MeshConfigurationRequest.class)))
                .thenThrow(new MeshConfigurationException.MaterialNotFound(
                        "Material lock failed with SQLSTATE P0002 and private database detail"));

        mockMvc.perform(put("/api/admin/products/7/mesh-configurations")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(validPutBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The selected material was not found."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("P0002"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }

    @Test
    void missingAndWrongTypeProductsAreNotFoundAndStaleRevisionIsConflict() throws Exception {
        when(service.getStructure(404)).thenThrow(new MeshConfigurationException.NotFound("Product was not found"));
        when(service.getConfiguration(405)).thenThrow(new MeshConfigurationException.NotFound("Product was not found"));
        when(service.getConfiguration(406)).thenThrow(new MeshConfigurationException.Conflict("expectedRevision is stale"));
        mockMvc.perform(get("/api/admin/products/404/mesh-structure").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/products/405/mesh-configurations").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/products/406/mesh-configurations").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_REVISION_CONFLICT"));
        mockMvc.perform(get("/api/admin/products/not-an-id/mesh-structure").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(adminAuth())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void rejectsNonPositiveProductPathsOnAllOperationsBeforeCallingService() throws Exception {
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(adminAuth());
        mockMvc.perform(get("/api/admin/products/0/mesh-structure").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        mockMvc.perform(get("/api/admin/products/-1/mesh-configurations").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        mockMvc.perform(put("/api/admin/products/0/mesh-configurations").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(validPutBody()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        verify(service, never()).getStructure(any());
        verify(service, never()).getConfiguration(any());
        verify(service, never()).putConfiguration(any(), any(), any());
    }

    @Test
    void rejectsInvalidEnvelopeIdsAndRevisionBeforeCallingService() throws Exception {
        String valid = validPutBody();
        String[] invalidBodies = {
                valid.replace("\"mediaAssetId\":41", "\"mediaAssetId\":0"),
                valid.replace("\"materialId\":3", "\"materialId\":0"),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":-1")
        };
        for (String body : invalidBodies) {
            mockMvc.perform(put("/api/admin/products/7/mesh-configurations")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                    .authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        }
        verify(service, never()).putConfiguration(any(), any(), any());
    }

    private static String validPutBody() {
        return "{\"mediaAssetId\":41,\"structureSnapshotId\":\"" + UUID.randomUUID()
                + "\",\"expectedRevision\":0,\"materialId\":3,\"meshes\":[{\"nodeIndex\":0,\"meshIndex\":0,\"primitiveIndex\":0,\"customizable\":true,\"defaultColorId\":5,\"allowedColorIds\":[5]}]}";
    }

    private UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(admin, "test-only",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static MeshConfigurationResponse unconfigured() {
        return new MeshConfigurationResponse(7, 41L, UUID.randomUUID(), 0L, false, null, List.of());
    }
}

