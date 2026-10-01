package kr.ac.skuniv.coopradar.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Swagger UI가 계약·구현 두 스펙을 띄우는지(ADR-0011). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SwaggerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 계약_스펙을_정적_파일로_내보낸다() throws Exception {
        mvc.perform(get("/openapi/contract.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.paths['/api/ping'].get.operationId").value("ping"))
                .andExpect(jsonPath("$.paths['/api/center/board'].get.security[0].bearerAuth").isArray());
    }

    @Test
    void 구현_스펙은_api_경로만_담는다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/ping'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void 드롭다운에_계약이_먼저_구현이_다음() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[0].name").value("계약"))
                .andExpect(jsonPath("$.urls[0].url").value("/openapi/contract.json"))
                .andExpect(jsonPath("$.urls[1].url").value("/v3/api-docs"))
                .andExpect(jsonPath("$['urls.primaryName']").value("계약"));
    }

    @Test
    void Swagger_UI_화면() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}
