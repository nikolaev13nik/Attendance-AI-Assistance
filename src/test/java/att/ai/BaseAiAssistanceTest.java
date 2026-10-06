package att.ai;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "att.ai.llm.provider=stub")
public abstract class BaseAiAssistanceTest extends AnalysisFixtures {
    @MockitoBean
    protected JavaMailSender mailSender;

    @BeforeEach
    void stubMimeMessageCreation() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
    }
}
