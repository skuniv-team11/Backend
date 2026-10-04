package kr.ac.skuniv.coopradar.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.skuniv.coopradar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 기관 로고 정적 파일(/logos/{id}.png, ADR-0019). 로그인 없이 받는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LogoResourceTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 로고는_로그인_없이_PNG로_받고_하루_캐시한다() throws Exception {
        mvc.perform(get("/logos/3.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
    }

    @Test
    void 없는_로고는_404() throws Exception {
        mvc.perform(get("/logos/999.png")).andExpect(status().isNotFound());
    }
}
