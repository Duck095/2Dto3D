package com.exe.morphia3d;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class SecurityAndAuthIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        colorRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void protectedMutationRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/colors/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked\",\"hexCode\":\"#010101\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminMutationRejectsUserRoleAndAllowsAdminRole() throws Exception {
        String body = "{\"name\":\"admin-only\",\"hexCode\":\"#020202\"}";

        mockMvc.perform(post("/api/colors/create")
                        .with(user("customer@example.com").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/colors/create")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void selectedCatalogReadRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/materials/"))
                .andExpect(status().isOk());
    }

    @Test
    void registerPersistsSafeDefaultsAndEncodedPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New User\",\"email\":\"new@example.com\",\"phone\":\"123\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        User saved = userRepository.findByEmail("new@example.com").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo("secret1");
        assertThat(passwordEncoder.matches("secret1", saved.getPassword())).isTrue();
    }

    @Test
    void loginReturnsJwtForRegisteredCredentials() throws Exception {
        userRepository.save(User.builder()
                .name("Login User")
                .email("login@example.com")
                .password(passwordEncoder.encode("secret1"))
                .build());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.com"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void accountStatusAndRoleUpdatesHaveSeparateSemantics() throws Exception {
        User target = userRepository.save(User.builder()
                .name("Target")
                .email("target@example.com")
                .password("encoded")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());

        mockMvc.perform(put("/api/accounts/{id}/status", target.getId())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"UNACTIVE\""))
                .andExpect(status().isOk());

        User statusUpdated = userRepository.findById(target.getId()).orElseThrow();
        assertThat(statusUpdated.getStatus()).isEqualTo(UserStatus.UNACTIVE);
        assertThat(statusUpdated.getRole()).isEqualTo(Role.USER);

        mockMvc.perform(put("/api/accounts/{id}/role", target.getId())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"DESIGNER\""))
                .andExpect(status().isOk());

        User roleUpdated = userRepository.findById(target.getId()).orElseThrow();
        assertThat(roleUpdated.getRole()).isEqualTo(Role.DESIGNER);
        assertThat(roleUpdated.getStatus()).isEqualTo(UserStatus.UNACTIVE);
    }
}
