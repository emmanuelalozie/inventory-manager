package com.example.inventix.controller;

import com.example.inventix.dto.StocktakeResponse;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.service.StocktakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StocktakeController.class)
class StocktakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StocktakeService stocktakeService;

    @Test
    void returnsTheChanges() throws Exception {
        when(stocktakeService.applyCount(any())).thenReturn(new StocktakeResponse(2,
                List.of(new StocktakeResponse.Change(1L, "MS-1", "Mouse", 10, 8, -2))));

        mockMvc.perform(post("/api/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"October\",\"lines\":[{\"productId\":1,\"countedQuantity\":8,\"expectedQuantity\":10},"
                                + "{\"productId\":2,\"countedQuantity\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counted").value(2))
                .andExpect(jsonPath("$.changes[0].delta").value(-2));

        verify(stocktakeService).applyCount(argThat(r -> r.lines().size() == 2
                && r.lines().get(1).expectedQuantity() == null));
    }

    @Test
    void returns400_forNoNoteNoLinesOrANegativeCount() throws Exception {
        mockMvc.perform(post("/api/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\" \",\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        mockMvc.perform(post("/api/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Count\",\"lines\":[{\"productId\":1,\"countedQuantity\":-1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].countedQuantity"));

        verify(stocktakeService, never()).applyCount(any());
    }

    @Test
    void returns409_whenTheSheetIsStale() throws Exception {
        when(stocktakeService.applyCount(any())).thenThrow(new ConflictException("Stock changed since the count sheet was loaded"));

        mockMvc.perform(post("/api/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Count\",\"lines\":[{\"productId\":1,\"countedQuantity\":3,\"expectedQuantity\":4}]}"))
                .andExpect(status().isConflict());
    }
}
