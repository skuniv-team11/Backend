package kr.ac.skuniv.coopradar.spike;

import java.util.List;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/** 운영계획서 PDF 1건을 추출하는 요청을 만든다. 실제 호출 없이 테스트에서도 쓴다. */
public final class OperationPlanRequests {

    private OperationPlanRequests() {
    }

    public static StructuredMessageCreateParams<OperationPlanLite> build(
            String systemPrompt, String fileName, String pdfBase64, String model, long maxTokens) {

        ContentBlockParam document = ContentBlockParam.ofDocument(
                DocumentBlockParam.builder()
                        .base64Source(pdfBase64)
                        .title(fileName)
                        .build());
        ContentBlockParam instruction = ContentBlockParam.ofText(
                "파일명: " + fileName + "\n이 운영계획서를 스키마에 맞게 추출하세요.");

        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .addUserMessageOfBlockParams(List.of(document, instruction))
                // 레코드에서 JSON 스키마를 만들고, 지원되지 않는 스키마면 호출 전에 여기서 예외가 난다
                .outputConfig(OperationPlanLite.class)
                .build();
    }
}
