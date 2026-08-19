import com.messagingagent.security.JwtService;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import com.messagingagent.MessagingAgentCoreApplication;

public class TestToken {
    public static void main(String[] args) throws Exception {
        JwtService jwtService = new JwtService("change-me-in-production-this-must-be-at-least-32-chars", 3600000);
        String token = jwtService.generateToken("admin", "ADMIN");
        System.out.println("TOKEN=" + token);
    }
}
