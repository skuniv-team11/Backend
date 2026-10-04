package kr.ac.skuniv.coopradar.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 기관 로고 경로와 파일(ADR-0019). */
class InstitutionLogosTest {

    @Test
    void 시드_기관_18곳은_모두_로고_경로가_있다() {
        for (int id = 1; id <= 18; id++) {
            assertThat(InstitutionLogos.pathFor(id)).as("기관 %d", id).isEqualTo("/logos/" + id + ".png");
        }
    }

    @Test
    void 로고_파일이_없는_기관은_null() {
        assertThat(InstitutionLogos.pathFor(999)).isNull();
        assertThat(new InstitutionRef(999, "(가상)기관").logoPath()).isNull();
    }

    @Test
    void 기관_요약은_id로_로고_경로를_채운다() {
        assertThat(new InstitutionRef(3, "(가상)기관")).isEqualTo(new InstitutionRef(3, "(가상)기관", "/logos/3.png"));
    }

    @Test
    void 로고는_투명_배경_PNG이고_600x200_안이다() throws Exception {
        for (int id = 1; id <= 18; id++) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("static/logos/" + id + ".png")) {
                assertThat(in).as("기관 %d", id).isNotNull();
                BufferedImage img = ImageIO.read(in);
                assertThat(img).as("기관 %d PNG", id).isNotNull();
                assertThat(img.getColorModel().hasAlpha()).as("기관 %d 투명 배경", id).isTrue();
                assertThat(img.getWidth()).as("기관 %d 가로", id).isBetween(1, 600);
                assertThat(img.getHeight()).as("기관 %d 세로", id).isBetween(1, 200);
            }
        }
    }
}
