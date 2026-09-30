package kr.ac.skuniv.coopradar.spike;

import java.util.List;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * 운영계획서 PDF 1건을 추출하는 요청을 만든다. 실제 호출 없이 테스트에서도 쓴다.
 *
 * 호출은 2번이다(ADR-0003). 기관·직무·불일치를 한 스키마에 담으면 문법이 한도를 넘는다.
 * 시스템 프롬프트는 두 파트가 똑같이 쓰고(파이썬 쪽과 같은 파일이어야 한다 — PromptSyncTest),
 * 어느 파트를 뽑는지는 사용자 메시지로만 알린다.
 */
public final class OperationPlanRequests {

    private OperationPlanRequests() {
    }

    /** 1/2 — 기관 현황과 문서 내부 불일치 */
    public static StructuredMessageCreateParams<OperationPlanInstitutionPart> institutionPart(
            String systemPrompt, String fileName, String pdfBase64, String model, long maxTokens) {

        return base(systemPrompt, fileName, pdfBase64, model, maxTokens,
                "이 운영계획서에서 기관 현황과 문서 내부 불일치를 스키마에 맞게 추출하세요."
                        + " 직무([붙임1] 운영 계획 및 직무기술서)는 다른 호출에서 따로 뽑으니 여기서는 다루지 않습니다.")
                .outputConfig(OperationPlanInstitutionPart.class)
                .build();
    }

    /** 2/2 — 직무 */
    public static StructuredMessageCreateParams<OperationPlanJobsPart> jobsPart(
            String systemPrompt, String fileName, String pdfBase64, String model, long maxTokens) {

        return base(systemPrompt, fileName, pdfBase64, model, maxTokens,
                "이 운영계획서의 [붙임1] 운영 계획 및 직무기술서에서 직무를 스키마에 맞게 추출하세요."
                        + " 기관 현황과 문서 내부 불일치는 다른 호출에서 따로 뽑으니 여기서는 다루지 않습니다.")
                .outputConfig(OperationPlanJobsPart.class)
                .build();
    }

    private static MessageCreateParams.Builder base(
            String systemPrompt, String fileName, String pdfBase64, String model, long maxTokens, String instruction) {

        ContentBlockParam document = ContentBlockParam.ofDocument(
                DocumentBlockParam.builder()
                        .base64Source(pdfBase64)
                        .title(fileName)
                        .build());
        ContentBlockParam text = ContentBlockParam.ofText("파일명: " + fileName + "\n" + instruction);

        // outputConfig(...) 에서 레코드의 JSON 스키마를 만든다. 지원되지 않는 스키마면 호출 전에 예외가 난다
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .addUserMessageOfBlockParams(List.of(document, text));
    }
}
