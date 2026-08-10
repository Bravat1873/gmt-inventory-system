package com.internalops.productimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql("/product-image-schema.sql")
@Sql(statements = "DROP TABLE IF EXISTS product_image", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
class ProductImageApiTest {
    private static final long PRODUCT_ID = 1L;

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void productImageStorage(DynamicPropertyRegistry registry) {
        registry.add("internal-ops.product-image-root", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @SpyBean ProductImageStorage storage;

    private Cookie session;

    @BeforeEach
    void login() throws Exception {
        session = loginAs("admin");
    }

    @AfterEach
    void resetAndCleanStorage() throws IOException {
        reset(storage);
        try (var paths = Files.walk(storageRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(storageRoot)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void first_valid_upload_becomes_primary_and_is_readable() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("camera.jpg", jpeg())).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].originalFilename").value("camera.jpg"))
                .andExpect(jsonPath("$.data[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data[0].fileSize").value(4))
                .andExpect(jsonPath("$.data[0].primary").value(true))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].contentUrl").isNotEmpty())
                .andReturn();
        long imageId = imageId(result);

        mvc.perform(get("/api/product-images/{imageId}/content", imageId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, max-age=3600"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(jpeg()));
    }

    @Test
    void accepts_png_and_webp_magic() throws Exception {
        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("one.png", png()))
                        .file(file("two.webp", webp()))
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.data[1].contentType").value("image/webp"));
    }

    @Test
    void rejects_executable_renamed_to_jpg() throws Exception {
        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("danger.jpg", new byte[]{'M', 'Z', 0, 0})).cookie(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejects_a_file_larger_than_five_megabytes() throws Exception {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        tooLarge[0] = (byte) 0xFF;
        tooLarge[1] = (byte) 0xD8;
        tooLarge[2] = (byte) 0xFF;

        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("large.jpg", tooLarge)).cookie(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejects_the_eleventh_image() throws Exception {
        for (int index = 0; index < 10; index++) {
            upload("image-" + index + ".jpg", jpeg(), session);
        }

        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("eleven.jpg", jpeg())).cookie(session))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?", Integer.class, PRODUCT_ID))
                .isEqualTo(10);
    }

    @Test
    void changing_primary_leaves_exactly_one_primary_image() throws Exception {
        upload("first.jpg", jpeg(), session);
        long second = upload("second.png", png(), session);

        mvc.perform(put("/api/products/{productId}/images/{imageId}/primary", PRODUCT_ID, second).cookie(session))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=? AND is_primary=TRUE", Integer.class, PRODUCT_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM product_image WHERE product_id=? AND is_primary=TRUE", Long.class, PRODUCT_ID))
                .isEqualTo(second);
    }

    @Test
    void deleting_primary_promotes_the_lowest_sort_order() throws Exception {
        long first = upload("first.jpg", jpeg(), session);
        long second = upload("second.png", png(), session);
        upload("third.webp", webp(), session);

        mvc.perform(delete("/api/products/{productId}/images/{imageId}", PRODUCT_ID, first).cookie(session))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=? AND is_primary=TRUE", Integer.class, PRODUCT_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM product_image WHERE product_id=? AND is_primary=TRUE", Long.class, PRODUCT_ID))
                .isEqualTo(second);
    }

    @Test
    void all_three_authenticated_roles_can_maintain_images() throws Exception {
        for (String username : List.of("admin", "finance", "regular-user")) {
            mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                            .file(file(username + ".jpg", jpeg())).cookie(loginAs(username)))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?", Integer.class, PRODUCT_ID))
                .isEqualTo(3);
    }

    @Test
    void unauthenticated_requests_are_rejected() throws Exception {
        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("anonymous.jpg", jpeg())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reorders_images_without_violating_the_unique_sort_order() throws Exception {
        long first = upload("first.jpg", jpeg(), session);
        long second = upload("second.png", png(), session);

        mvc.perform(put("/api/products/{productId}/images/order", PRODUCT_ID).cookie(session)
                        .contentType("application/json")
                        .content("{\"imageIds\":[" + second + "," + first + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0));

        assertThat(jdbc.queryForObject("SELECT sort_order FROM product_image WHERE id=?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT sort_order FROM product_image WHERE id=?", Integer.class, first)).isEqualTo(1);
    }

    @Test
    void uploads_after_deleting_a_non_final_image_without_reusing_its_sort_order() throws Exception {
        upload("first.jpg", jpeg(), session);
        long second = upload("second.png", png(), session);
        upload("third.webp", webp(), session);

        mvc.perform(delete("/api/products/{productId}/images/{imageId}", PRODUCT_ID, second).cookie(session))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("fourth.jpg", jpeg())).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sortOrder").value(3));

        assertThat(jdbc.queryForList(
                "SELECT sort_order FROM product_image WHERE product_id=? ORDER BY sort_order", Integer.class, PRODUCT_ID))
                .containsExactly(0, 2, 3);
    }

    @Test
    void rejects_an_order_that_does_not_contain_the_complete_current_id_set() throws Exception {
        long first = upload("first.jpg", jpeg(), session);
        upload("second.png", png(), session);

        mvc.perform(put("/api/products/{productId}/images/order", PRODUCT_ID).cookie(session)
                        .contentType("application/json")
                        .content("{\"imageIds\":[" + first + ",999999]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missing_content_on_disk_returns_not_found() throws Exception {
        long imageId = upload("missing.jpg", jpeg(), session);
        String storageKey = jdbc.queryForObject(
                "SELECT storage_key FROM product_image WHERE id=?", String.class, imageId);
        storage.delete(storageKey);

        mvc.perform(get("/api/product-images/{imageId}/content", imageId).cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void database_insert_failure_removes_the_stored_file_and_returns_internal_error() throws Exception {
        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("x".repeat(256) + ".jpg", jpeg())).cookie(session))
                .andExpect(status().isInternalServerError());

        assertThat(storedFiles()).isZero();
    }

    @Test
    void storage_write_failure_returns_internal_error_instead_of_conflict() throws Exception {
        doThrow(new IOException("storage offline"))
                .when(storage).store(anyLong(), anyString(), any(byte[].class));

        mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file("camera.jpg", jpeg())).cookie(session))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void failed_compensation_logs_the_storage_key_and_exception() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductImageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        doThrow(new IOException("cleanup offline")).when(storage).delete(anyString());

        try {
            mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                            .file(file("x".repeat(256) + ".jpg", jpeg())).cookie(session))
                    .andExpect(status().isInternalServerError());

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("Failed to delete compensated product image storage key " + PRODUCT_ID + "/");
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IOException.class.getName());
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("cleanup offline");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void maps_only_the_dedicated_conflict_exception_to_conflict() throws Exception {
        ProductImageService service = mock(ProductImageService.class);
        when(service.list(PRODUCT_ID)).thenThrow(new ProductImageService.ConflictException("stale image version"));
        MockMvc standalone = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ProductImageController(service)).build();

        standalone.perform(get("/api/products/{productId}/images", PRODUCT_ID))
                .andExpect(status().isConflict());
    }

    private long storedFiles() throws IOException {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private long upload(String filename, byte[] bytes, Cookie userSession) throws Exception {
        return imageId(mvc.perform(multipart("/api/products/{productId}/images", PRODUCT_ID)
                        .file(file(filename, bytes)).cookie(userSession))
                .andExpect(status().isOk()).andReturn());
    }

    private long imageId(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return body.path("data").get(0).path("id").asLong();
    }

    private Cookie loginAs(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }

    private MockMultipartFile file(String filename, byte[] bytes) {
        return new MockMultipartFile("files", filename, "application/octet-stream", bytes);
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private static byte[] webp() {
        return "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII);
    }
}
