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

/** 기관 로고(/logos/{id}.png, ADR-0019)·소개서 사진(/photos/{기관 id}/{순번}.jpg, ADR-0030) 정적 파일. 로그인 없이 받는다. */
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

    @Test
    void 소개서_사진도_로그인_없이_JPEG로_받고_하루_캐시한다() throws Exception {
        mvc.perform(get("/photos/7/1.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mvc.perform(get("/photos/7/99.jpg")).andExpect(status().isNotFound());
    }
}
