package com.example.inventix;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the app with the server profile (application-server.properties). The PostgreSQL connection is swapped for
 * its own in-memory H2 database so this runs anywhere; CI runs the other tests against a real PostgreSQL.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventix-server-mode;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa"
})
@ActiveProfiles("server")
@AutoConfigureMockMvc
class ServerModeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startsEmpty_withoutSampleProducts() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void backups_areLeftToTheHostedDatabase() throws Exception {
        mockMvc.perform(get("/api/backup")).andExpect(status().isConflict());
        mockMvc.perform(post("/api/backup")).andExpect(status().isConflict());
    }

    @Test
    void h2Console_isOff() throws Exception {
        mockMvc.perform(get("/h2-console")).andExpect(status().isNotFound());
    }
}
