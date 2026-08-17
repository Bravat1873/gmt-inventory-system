package com.internalops.importing;

import com.internalops.api.ApiExceptionHandler;
import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderImportAuthorizationApiTest {
    private ImportPreviewService previews;
    private ImportCommitService commits;
    private ImportErrorWorkbookService errors;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        previews = mock(ImportPreviewService.class);
        commits = mock(ImportCommitService.class);
        errors = mock(ImportErrorWorkbookService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ImportController(previews, commits, errors))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(previews.get(7L)).thenReturn(batch(ImportType.ORDER, "PREVIEW"));
        when(previews.preview(eq(ImportType.ORDER), any())).thenReturn(batch(ImportType.ORDER, "PREVIEW"));
        when(commits.commit(eq(7L), any(), any(), any())).thenReturn(batch(ImportType.ORDER, "COMMITTED"));
        when(errors.create(7L)).thenReturn(new byte[]{1});
    }

    @AfterEach
    void clearUser() {
        CurrentUser.clear();
    }

    @Test
    void financeCannotUseAnyOrderImportEndpointIncludingProgrammaticCommit() throws Exception {
        CurrentUser.set(new CurrentUser(3, "finance", "财务", UserRole.FINANCE));
        MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});

        mvc.perform(multipart("/api/imports/preview").file(file).param("type", "ORDER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("财务用户不能导入销售订单"));
        mvc.perform(get("/api/imports/7"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/imports/7/rows").contentType(MediaType.APPLICATION_JSON).content("{\"data\":{}}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/imports/7/rows/8").contentType(MediaType.APPLICATION_JSON).content("{\"data\":{}}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/imports/7/errors.xlsx"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/imports/7/commit"))
                .andExpect(status().isForbidden());

        verify(previews, never()).preview(eq(ImportType.ORDER), any());
        verify(previews, never()).add(anyLong(), any());
        verify(previews, never()).update(anyLong(), anyLong(), any());
        verify(errors, never()).create(anyLong());
        verify(commits, never()).commit(anyLong(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADMIN", "USER"})
    void adminAndOrderUserCanPreviewAndCommitOrders(UserRole role) throws Exception {
        CurrentUser.set(new CurrentUser(9, role.name().toLowerCase(), role.name(), role));
        MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});

        mvc.perform(multipart("/api/imports/preview").file(file).param("type", "ORDER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.importType").value("ORDER"));
        mvc.perform(post("/api/imports/7/commit"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));
    }

    @Test
    void financeKeepsExistingAccessToNonOrderImports() throws Exception {
        CurrentUser.set(new CurrentUser(3, "finance", "财务", UserRole.FINANCE));
        when(previews.get(11L)).thenReturn(batch(ImportType.COST, "PREVIEW"));
        when(commits.commit(eq(11L), any(), any(), any())).thenReturn(batch(ImportType.COST, "COMMITTED"));

        mvc.perform(post("/api/imports/11/commit"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.importType").value("COST"));
    }

    private ImportBatchView batch(ImportType type, String status) {
        return new ImportBatchView(type == ImportType.ORDER ? 7 : 11, type, "import.xlsx", status,
                0, 0, 0, 0, "COMMITTED".equals(status) ? 1 : 0, null, List.of());
    }
}
